"""android-mic-cli: the PC side of AndroidMic in a terminal, standalone.

No AndroidMic desktop app needed, this script replaces it. The phone runs the AndroidMic
Android app 2.2.x and connects over its hotspot (or any Wi-Fi), no adb.
- TCP (phone mode WIFI): handshake "AndroidMic1" -> "AndroidMic2", then frames of
  4-byte big-endian length + protobuf AudioPacketMessage.
- UDP (phone mode UDP): datagrams with the same length prefix + MessageWrapper
  (connect request, or audio packet with a sequence number).
Settings are kept in config.toml next to this script.
"""
import argparse
import collections
import contextlib
import errno
import ipaddress
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import time
import tomllib

import numpy as np
import sounddevice as sd

CONFIG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.toml")
DEFAULTS = {"mode": "tcp", "ip": "auto", "port": 54345, "device": "default", "latency_ms": 30}
CHECK_1, CHECK_2 = b"AndroidMic1", b"AndroidMic2"
PHONE_MODE = {"tcp": "WIFI", "udp": "UDP"}
# Android AudioFormat encoding -> (name, dtype, scale, offset)
FORMATS = {
    2: ("i16", "<i2", 32768.0, 0),
    4: ("f32", "<f4", 1.0, 0),
    22: ("i32", "<i4", 2.0**31, 0),
    3: ("u8", "u1", 128.0, 128),
}
HELP = """commands:
  start                 (re)start listening
  stop                  stop listening
  status                connection, audio format, level, buffer, settings
  watch                 live level meter, Enter to leave
  mode tcp|udp          tcp = phone mode WIFI, udp = phone mode UDP
  ip [auto|address]     listen address, no argument lists this PC's addresses
  port <number>         listen port
  devices               list output devices
  device <number|name|default>   output device
  latency <ms>          jitter buffer target, raise it if audio crackles
  help, quit
mode, ip, port, device and latency are saved to config.toml"""
PRINT = threading.Lock()
prompt = ""


def width():
    return shutil.get_terminal_size().columns - 1


def log(msg):
    with PRINT:
        print(("\r" + time.strftime("%H:%M:%S ") + msg).ljust(width()), flush=True)
        if prompt:
            print(prompt, end="", flush=True)


def varint(b, i):
    v = shift = 0
    while True:
        c = b[i]
        i += 1
        v |= (c & 0x7F) << shift
        shift += 7
        if c < 0x80:
            return v, i


def fields(b):
    """Minimal proto3 parser: {field number: int (varint) or bytes (length-delimited)}."""
    out, i = {}, 0
    while i < len(b):
        key, i = varint(b, i)
        if key & 7 == 0:
            out[key >> 3], i = varint(b, i)
        elif key & 7 == 2:
            n, i = varint(b, i)
            out[key >> 3], i = b[i:i + n], i + n
        else:
            raise ValueError(f"unexpected protobuf wire type {key & 7}")
    return out


def decode_audio(b):
    f = fields(b)
    rate, ch, enc = f.get(2, 0), f.get(3, 1), f.get(4, 2)
    if enc not in FORMATS:
        raise ValueError(f"audio format {enc} not supported, pick 16-bit, 32-bit, float or 8-bit on the phone")
    name, dtype, scale, offset = FORMATS[enc]
    x = (np.frombuffer(f.get(1, b""), dtype).astype(np.float32) - offset) / scale
    return rate, ch, name, x.reshape(-1, ch)


class Player:
    """One output stream kept open, silent while idle, so a new connection never waits on the device."""

    def __init__(self, device, rate, target_ms):
        self.lock, self.reopen = threading.Lock(), threading.Lock()
        self.q, self.stream = collections.deque(), None
        self.peak, self.underruns, self.dropped = 0.0, 0, 0
        self._open(device, rate, target_ms)

    def open(self, device=None, rate=None, target_ms=None):
        """Reopen with new settings (left out = unchanged), back to the old ones if the device refuses."""
        with self.reopen:
            old = (self.device, self.rate, self.target_ms)
            self.stream.close()  # outside self.lock, close waits for the callback
            try:
                self._open(old[0] if device is None else device, rate or old[1], target_ms or old[2])
            except sd.PortAudioError:
                self._open(*old)
                raise

    def _open(self, device, rate, target_ms):
        info = sd.query_devices(device)
        with self.lock:
            self.q.clear()
            self.queued, self.primed = 0, False
            self.device, self.rate, self.target_ms = device, rate, target_ms
            self.name, self.out_ch = info["name"], min(2, info["max_output_channels"])
            self.target = rate * target_ms // 1000
            # ponytail: drops the oldest chunk past the cap instead of resampling clock drift
            self.cap = self.target + rate * 80 // 1000
        self.stream = sd.OutputStream(
            device=device, samplerate=rate, channels=self.out_ch, dtype="float32", latency="low",
            callback=self._fill, extra_settings=sd.WasapiSettings(auto_convert=True),
        )
        self.stream.start()

    def feed(self, rate, x):
        if rate != self.rate:
            self.open(rate=rate)
        with self.lock:
            if x.shape[1] != self.out_ch:
                x = x.mean(axis=1, keepdims=True)  # (n, 1) broadcasts to any channel count
            self.peak = max(self.peak, float(np.abs(x).max(initial=0)))
            self.q.append(x)
            self.queued += len(x)
            while self.queued > self.cap:
                self.queued -= len(self.q.popleft())
                self.dropped += 1

    def _fill(self, out, frames, _time, _status):
        with self.lock:
            if not self.primed and self.queued < self.target:
                out.fill(0)
                return
            self.primed, i = True, 0
            while i < frames and self.q:
                chunk = self.q[0]
                k = min(len(chunk), frames - i)
                out[i:i + k] = chunk[:k]
                i += k
                self.queued -= k
                if k == len(chunk):
                    self.q.popleft()
                else:
                    self.q[0] = chunk[k:]
            if i < frames:
                out[i:] = 0
                self.primed = False
                self.underruns += 1

    def take_peak(self):
        with self.lock:
            p, self.peak = self.peak, 0.0
            return p

    def reset_stats(self):
        self.underruns = self.dropped = 0


class Receiver:
    """Listener and connection state, driven by the start/stop commands."""

    def __init__(self, player, mode, ip, port):
        self.player, self.mode, self.ip, self.port = player, mode, ip, port  # ip "auto" = IP on the hotspot
        self.sock = self.conn = self.thread = None
        self.host = ""
        self.stopping = threading.Event()
        self.state, self.where, self.fmt, self.t0 = "STOPPED", "", "", None

    def start(self):
        self.stop()
        self.host = primary_ip() if self.ip == "auto" else self.ip  # picked on every start, hotspot IPs change
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM if self.mode == "udp" else socket.SOCK_STREAM)
        try:
            sock.bind((self.host, self.port))
            if self.mode == "tcp":
                sock.listen()
        except OSError as e:
            sock.close()
            in_use = e.errno in (errno.EADDRINUSE, 10048)
            hint = ", already in use: is another android-mic window still open?" if in_use else ""
            return log(f"Can't listen on {self.host}:{self.port}: {e.strerror or e}{hint}")
        sock.settimeout(1)  # lets the loop notice stop(), and for udp it is the 1 s disconnect timeout
        self.sock = sock
        self.stopping.clear()
        self.thread = threading.Thread(target=self.serve_udp if self.mode == "udp" else self.serve_tcp, daemon=True)
        self.thread.start()

    def stop(self):
        if not self.thread:
            return
        self.stopping.set()
        if conn := self.conn:
            with contextlib.suppress(OSError):
                conn.shutdown(socket.SHUT_RDWR)  # wakes the blocked read
        self.sock.close()
        self.thread.join(3)
        self.sock = self.thread = None
        self.state, self.where = "STOPPED", ""
        log("Stopped")

    def hint(self):
        ip = primary_ip() if self.host == "0.0.0.0" else self.host
        log(f"On the phone: mode {PHONE_MODE[self.mode]}, IP {ip}, port {self.port}, then tap Connect")

    def listening(self):
        self.state, self.where, self.fmt, self.t0 = "LISTENING", f"{self.host}:{self.port} ({self.mode})", "", None
        log(f"Listening on {self.where}")
        if self.ip == "auto" and (now := primary_ip()) != self.host:
            log(f"This PC's IP changed to {now} (hotspot restarted?), type start to listen there")

    def connected(self, addr, handshake_ms=None):
        self.state, self.where, self.t0 = "CONNECTED", f"{addr[0]}:{addr[1]}", time.perf_counter()
        self.player.reset_stats()
        log(f"Connected on {self.where}" + (f" (handshake {handshake_ms:.0f} ms)" if handshake_ms is not None else ""))

    def audio(self, msg):
        rate, ch, fmt, x = decode_audio(msg)
        if self.t0 is not None:
            self.fmt = f"{rate} Hz {'mono' if ch == 1 else 'stereo'} {fmt}"
            ms = (time.perf_counter() - self.t0) * 1000
            log(f"First audio {ms:.0f} ms after connect ({self.fmt}), that gap is the phone starting its mic")
            self.t0 = None
        self.player.feed(rate, x)

    def accept(self):
        """Wait for the phone, None once stop() was called."""
        while not self.stopping.is_set():
            try:
                return self.sock.accept()
            except TimeoutError:
                continue
            except OSError:
                break  # socket closed by stop()
        return None

    def serve_tcp(self):
        self.hint()
        while not self.stopping.is_set():
            self.listening()
            client = self.accept()
            if client is None:
                return
            self.session(*client)

    def session(self, conn, addr):
        conn.settimeout(1)  # a stray client that sends nothing can't hold the listener
        with conn, conn.makefile("rb") as f:  # the socket only really closes once f is closed too
            try:
                t0 = time.perf_counter()
                if (hello := f.read(len(CHECK_1))) != CHECK_1:
                    raise ValueError(f"bad handshake {hello!r}")
                conn.sendall(CHECK_2)
                self.conn = conn
                self.connected(addr, (time.perf_counter() - t0) * 1000)
                conn.settimeout(5)  # a phone that vanished without closing (hotspot off)
                while len(head := f.read(4)) == 4:
                    self.audio(f.read(int.from_bytes(head, "big")))
                reason = "phone closed the connection"
            except Exception as e:  # a broken session ends here, the listener keeps going
                reason = str(e) or type(e).__name__
        if self.conn is None:
            return log(f"Rejected {addr[0]}:{addr[1]}: {reason}")
        self.conn = None
        log(f"Disconnected: {'stopped' if self.stopping.is_set() else reason}")

    def serve_udp(self):
        self.hint()
        self.listening()
        peer = expect = None
        while not self.stopping.is_set():
            try:
                data, addr = self.sock.recvfrom(65535)
            except TimeoutError:
                if peer:
                    peer = expect = None
                    log("Disconnected: no packets for 1 s")
                    self.listening()
                continue
            except ConnectionResetError:  # Windows reports ICMP unreachable from an earlier sendto here
                continue
            except OSError:
                return  # socket closed by stop()
            try:
                w = fields(data[4:])  # skip the 4-byte length prefix
                if 2 in w:
                    self.sock.sendto(CHECK_2, addr)
                if peer is None:
                    peer = addr
                    self.connected(addr)
                if 1 in w:
                    ordered = fields(w[1])
                    seq = ordered.get(1, 0)
                    if expect is not None and expect - 100 < seq < expect:
                        continue  # late, its slot already played
                    expect = seq + 1
                    self.audio(ordered.get(2, b""))
            except Exception as e:  # one bad datagram must not stop the listener
                log(f"Bad packet from {addr[0]}: {e}")


def parse_ip(value):
    return "auto" if value == "auto" else str(ipaddress.IPv4Address(value))


def parse_port(value):
    port = int(value) if str(value).isdigit() else 0
    if not 0 < port < 65536:
        raise ValueError(f"port must be 1-65535, not {value!r}")
    return port


def parse_latency(value):
    ms = int(value) if str(value).isdigit() else 0
    if ms < 1:
        raise ValueError(f"latency must be a number of ms, at least 1, not {value!r}")
    return ms


def load_settings(path=CONFIG):
    """config.toml over DEFAULTS, ValueError on a bad value."""
    try:
        with open(path, "rb") as f:
            saved = tomllib.load(f)
    except FileNotFoundError:
        saved = {}
    s = {k: saved.get(k, v) for k, v in DEFAULTS.items()}
    if s["mode"] not in PHONE_MODE:
        raise ValueError(f"mode must be tcp or udp, not {s['mode']!r}")
    s["ip"], s["port"], s["latency_ms"] = parse_ip(s["ip"]), parse_port(s["port"]), parse_latency(s["latency_ms"])
    s["device"] = str(s["device"])
    return s


def save_settings(s, path=CONFIG):
    with open(path, "w", encoding="utf-8") as f:
        f.write("# android-mic-cli settings, also updated by the mode/ip/port/device/latency commands\n")
        # a JSON string or number is valid TOML as well
        f.writelines(f"{k} = {json.dumps(v, ensure_ascii=False)}\n" for k, v in s.items())


def primary_ip():
    """This PC's IP on the network with the default route (the hotspot)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("8.8.8.8", 80))  # no packet is sent for a UDP connect
            return s.getsockname()[0]
        except OSError:
            return "0.0.0.0"


def local_ips():
    return sorted({a[4][0] for a in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)})


def wasapi():
    return next(h for h in sd.query_hostapis() if "WASAPI" in h["name"])


def pick_device(want):
    """WASAPI output by number, full or partial name, or "default" for the Windows default."""
    api = wasapi()
    if want == "default":
        return api["default_output_device"]
    names = {i: sd.query_devices(i)["name"] for i in api["devices"] if sd.query_devices(i)["max_output_channels"]}
    if want.isdigit() and int(want) in names:
        return int(want)
    hits = ([i for i, name in names.items() if name.lower() == want.lower()]
            or [i for i, name in names.items() if want.lower() in name.lower()])
    if len(hits) != 1:
        raise LookupError(f"{len(hits)} output devices match {want!r}, use the number from 'devices'")
    return hits[0]


def list_devices(current=None):
    api = wasapi()
    for i in api["devices"]:
        d = sd.query_devices(i)
        if d["max_output_channels"]:
            mark = ">" if i == current else "*" if i == api["default_output_device"] else " "
            print(f"{mark} {i:3}  {d['name']}")
    print("  (> in use, * Windows default)")


def status_line(rx):
    pl = rx.player
    p = pl.take_peak()
    db = 20 * np.log10(p) if p > 1e-5 else -100.0
    if rx.state != "CONNECTED":
        return f"[{rx.state}] {rx.where}".rstrip()
    bar = "#" * int(max(0.0, db + 60) / 3)  # -60..0 dB -> 0..20 chars
    return (f"[CONNECTED] {rx.where} | {rx.fmt or 'waiting for audio'} | [{bar:<20}] {db:4.0f} dB"
            f" | buf {pl.queued * 1000 / pl.rate:3.0f} ms | underrun {pl.underruns} | drop {pl.dropped}")


def watch(rx):
    done = threading.Event()

    def wait_enter():
        with contextlib.suppress(EOFError):
            input()
        done.set()

    threading.Thread(target=wait_enter, daemon=True).start()
    while not done.wait(0.2):
        with PRINT:
            sys.stdout.write("\r" + status_line(rx)[:width()].ljust(width()))
            sys.stdout.flush()
    print()


def run_command(rx, saved, line):
    """One typed command; setting changes also go to `saved` and config.toml."""
    cmd, _, arg = line.strip().partition(" ")
    arg = arg.strip()
    if cmd == "start":
        rx.start()
    elif cmd == "stop":
        rx.stop()
    elif cmd == "status":
        print(status_line(rx))
        print(f"  mode {rx.mode}, ip {rx.ip}, port {rx.port}, output {rx.player.name}, latency {rx.player.target_ms} ms")
    elif cmd == "watch":
        watch(rx)
    elif cmd == "ip" and not arg:
        print("this PC:", ", ".join(local_ips()), f"(auto picks {primary_ip()})")
    elif cmd in ("mode", "ip", "port"):
        if cmd == "mode" and arg not in PHONE_MODE:
            raise ValueError("mode is tcp or udp")
        value = parse_ip(arg) if cmd == "ip" else parse_port(arg) if cmd == "port" else arg
        setattr(rx, cmd, value)
        saved[cmd] = value
        save_settings(saved)
        if rx.thread:
            rx.start()
    elif cmd == "devices":
        list_devices(rx.player.device)
    elif cmd == "device":
        rx.player.open(device=pick_device(arg))
        saved["device"] = "default" if arg == "default" else rx.player.name  # a name survives replugging
        save_settings(saved)
        print(f"output: {rx.player.name}")
    elif cmd == "latency":
        rx.player.open(target_ms=parse_latency(arg))
        saved["latency_ms"] = rx.player.target_ms
        save_settings(saved)
    elif cmd in ("help", "?"):
        print(HELP)
    elif cmd:
        print(f"unknown command {cmd!r}, type help")


def selftest():
    """Settings round trip, then both modes: fake phone -> listener -> decoder, no audio device needed."""
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "config.toml")
        assert load_settings(path) == DEFAULTS
        s = {**DEFAULTS, "mode": "udp", "ip": "10.0.0.2", "port": 5555, "device": 'Speakers "Realtek®" \\ 1'}
        save_settings(s, path)
        assert load_settings(path) == s, load_settings(path)

    def ev(v):
        out = bytearray()
        while True:
            out.append((v & 0x7F) | (0x80 if v > 0x7F else 0))
            v >>= 7
            if not v:
                return bytes(out)

    def enc(no, v):
        if isinstance(v, bytes):
            return ev(no << 3 | 2) + ev(len(v)) + v
        return ev(no << 3) + ev(v)

    def framed(msg):
        return len(msg).to_bytes(4, "big") + msg

    class Sink:
        def __init__(self):
            self.got = []

        def feed(self, rate, x):
            self.got.append((rate, x))

        def reset_stats(self):
            pass

    audio = enc(1, np.array([0, 16384, -32768], "<i2").tobytes()) + enc(2, 44100) + enc(3, 1) + enc(4, 2)
    for mode in PHONE_MODE:
        rx = Receiver(Sink(), mode, "127.0.0.1", 0)
        rx.start()
        addr = rx.sock.getsockname()
        if mode == "tcp":
            with socket.create_connection(addr, timeout=2) as stray:
                stray.sendall(b"AndroidMic0")  # rejected, the listener keeps going
                assert stray.recv(11) == b""
            phone = socket.create_connection(addr, timeout=2)
            phone.sendall(CHECK_1)
            assert phone.recv(11) == CHECK_2
            phone.sendall(framed(audio))
        else:
            phone = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            phone.settimeout(2)
            phone.sendto(framed(enc(2, b"")), addr)
            assert phone.recv(11) == CHECK_2
            for seq in (7, 6):  # 6 arrives late and must be dropped
                phone.sendto(framed(enc(1, enc(1, seq) + enc(2, audio))), addr)
        time.sleep(0.3)
        got = rx.player.got
        assert len(got) == 1 and got[0][0] == 44100 and got[0][1].shape == (3, 1), got
        assert np.allclose(got[0][1][:, 0], [0, 0.5, -1]), got
        phone.close()
        thread = rx.thread
        rx.stop()
        assert not thread.is_alive(), f"{mode} listener did not stop"
    print("selftest ok")


def main():
    global prompt
    try:
        saved = load_settings()
    except ValueError as e:  # TOMLDecodeError is a ValueError too
        sys.exit(f"{CONFIG}: {e}")

    ap = argparse.ArgumentParser(
        description="AndroidMic phone mic in the terminal: standalone, no desktop app, no adb. "
                    "Settings are saved in config.toml, flags only change this run.",
        epilog=HELP, formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("-m", "--mode", choices=list(PHONE_MODE), help="tcp = phone mode WIFI, udp = phone mode UDP")
    ap.add_argument("-i", "--ip", type=parse_ip, help="listen address, auto = this PC's IP on the hotspot")
    ap.add_argument("-p", "--port", type=parse_port, help="listen port")
    ap.add_argument("-d", "--device", help="output device: number, name or default (see --list)")
    ap.add_argument("-l", "--latency", dest="latency_ms", type=parse_latency, metavar="MS",
                    help="jitter buffer target, raise it if audio crackles")
    ap.add_argument("--list", action="store_true", help="list output devices and exit")
    ap.add_argument("--selftest", action="store_true", help=argparse.SUPPRESS)
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if args.list:
        return list_devices()
    run = {**saved, **{k: v for k, v in vars(args).items() if k in DEFAULTS and v is not None}}
    try:
        device = pick_device(run["device"])
    except LookupError as e:  # e.g. the saved Bluetooth headset is off
        log(f"{e}, using the Windows default output")
        device = pick_device("default")
    try:
        player = Player(device, 44100, run["latency_ms"])  # 44100 = the phone app's default sample rate
    except sd.PortAudioError as e:
        sys.exit(f"output device: {e}, see --list")
    log(f"Output: {player.name}, latency {run['latency_ms']} ms. Type help for commands")
    rx = Receiver(player, run["mode"], run["ip"], run["port"])
    rx.start()
    try:
        while True:
            prompt = "mic> "
            try:
                line = input(prompt)
            except EOFError:  # no terminal attached: keep serving until Ctrl+C
                prompt = ""
                while True:
                    time.sleep(1)
            prompt = ""
            if line.strip() in ("quit", "exit", "q"):
                break
            try:
                run_command(rx, saved, line)
            except (ValueError, LookupError, OSError, sd.PortAudioError) as e:
                print(f"error: {e}")
    except KeyboardInterrupt:
        print()
    finally:
        prompt = ""
        rx.stop()


if __name__ == "__main__":
    main()

"""android-mic-cli: the PC side of AndroidMic in a terminal, standalone, plus the Mirror app's screen in a window.

No AndroidMic desktop app needed, this script replaces it. The phone runs the AndroidMic
Android app 2.2.x, or the Mirror app from this project, and connects over its hotspot (or any Wi-Fi), no adb.
- TCP (phone mode WIFI): handshake "AndroidMic1" -> "AndroidMic2", then frames of
  4-byte big-endian length + protobuf AudioPacketMessage.
- TCP, Mirror app: handshake "MirrorHello" -> "MirrorReady", then the same length-prefixed frames,
  each [type][data]: 2 = 44100 Hz mono 16-bit mic, 0/1 = H.264 for the screen window (1 = key frame).
  Frames back to the phone carry the window's input as JSON.
- UDP (phone mode UDP): datagrams with the same length prefix + MessageWrapper
  (connect request, or audio packet with a sequence number).
Settings are kept in config.toml next to this script, or next to Mirror.exe when installed.
"""
import argparse
import collections
import contextlib
import ctypes
import errno
import fractions
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

try:
    import sdl2dll  # sets PYSDL2_DLL_PATH first, so importing sdl2 doesn't warn about where its DLL came from
    import av
    import sdl2
    import segno
except ImportError:  # no screen window, the mic still plays
    sdl2 = None

HERE = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))  # frozen: Mirror.exe
CONFIG = os.path.join(HERE, "config.toml")
LOGFILE = os.path.join(HERE, "android-mic.log")
DEFAULTS = {"mode": "tcp", "ip": "auto", "port": 54345, "device": "default", "latency_ms": 30}
CHECK_1, CHECK_2 = b"AndroidMic1", b"AndroidMic2"
MIRROR_1, MIRROR_2 = b"MirrorHello", b"MirrorReady"  # same length as AndroidMic's, one tcp port serves both
MAX_PACKET = 8 << 20  # a key frame fits easily, a garbage length can't make us allocate gigabytes
SCROLL = 0.25  # fraction of the phone screen scrolled per mouse wheel notch
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
        if not sys.stdout.isatty():  # log file: no \r or padding, they only clear the prompt line on a terminal
            return print(time.strftime("%H:%M:%S ") + msg, flush=True)
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


def clamp(v):
    return min(1.0, max(0.0, v))


class Screen:
    """The Mirror app's screen in a window, decoded by FFmpeg and drawn by SDL2 like scrcpy, the window's
    clicks and keys back to the phone. Until a phone connects the window shows a QR code with this PC's
    address, for the app to scan. One thread owns the window, the decoder and every SDL call.
    While the window is closed or minimized the video is dropped, nothing gets decoded."""

    def __init__(self):
        if sdl2 is None:
            raise ImportError("needs av, pysdl2, pysdl2-dll and segno: python -m pip install -r requirements.txt")
        self.phone = None  # send(msg) to the connected Mirror phone
        self.packets = collections.deque()
        self.showing = False  # window open and not minimized, video() drops packets otherwise
        self.want = threading.Event()  # open the window, or bring it to the front
        self.window = self.renderer = self.texture = self.decoder = self.stroke = None
        self.title, self.size, self.mouse, self.wheel_at, self.error = "Mirror", (720, 1600), (0, 0), 0, None
        self.waiting = "Mirror - waiting for the phone"  # title while no phone is connected
        self.pair = self.qr = self.qr_for = None  # what the QR code says, its texture, what that texture says
        self.keys = {sdl2.SDLK_BACKSPACE: "del", sdl2.SDLK_RETURN: "enter", sdl2.SDLK_KP_ENTER: "enter",
                     sdl2.SDLK_ESCAPE: "back"}
        self.alt_keys = {sdl2.SDLK_b: "back", sdl2.SDLK_h: "home", sdl2.SDLK_s: "recents"}  # as in scrcpy
        ready = threading.Event()
        threading.Thread(target=self.run, args=(ready,), daemon=True).start()
        ready.wait()
        if self.error:
            raise RuntimeError(self.error)

    def run(self, ready):
        for hint, value in ((sdl2.SDL_HINT_WINDOWS_DPI_AWARENESS, b"permonitorv2"),  # sharp on scaled displays
                            (sdl2.SDL_HINT_RENDER_SCALE_QUALITY, b"linear"),
                            (sdl2.SDL_HINT_QUIT_ON_LAST_WINDOW_CLOSE, b"0"),  # closing the window only closes it
                            (sdl2.SDL_HINT_NO_SIGNAL_HANDLERS, b"1"),  # Ctrl+C stays with Python
                            (sdl2.SDL_HINT_FORCE_RAISEWINDOW, b"1")):  # in front when the phone connects
            sdl2.SDL_SetHint(hint, value)
        if sdl2.SDL_Init(sdl2.SDL_INIT_VIDEO):
            self.error = sdl2.SDL_GetError().decode()
        self.wake = sdl2.SDL_RegisterEvents(1)
        ready.set()
        event = sdl2.SDL_Event()
        while not self.error:
            if not self.window:
                self.want.wait()  # nothing to do until a phone connects or the tray asks
            if self.want.is_set():
                self.want.clear()
                self.open()
            if self.window and sdl2.SDL_WaitEvent(event):
                try:
                    self.handle(event)
                except Exception as e:  # one bad frame or event must not end the window's thread
                    log(f"Screen: {e}")

    def open(self):
        if self.window:
            if sdl2.SDL_GetWindowFlags(self.window) & sdl2.SDL_WINDOW_MINIMIZED:
                sdl2.SDL_RestoreWindow(self.window)
            return sdl2.SDL_RaiseWindow(self.window)
        w, h = self.fit(*self.size) if self.phone else self.fit(560, 560)  # the phone's shape, or the QR code's
        self.window = sdl2.SDL_CreateWindow(self.title.encode(), sdl2.SDL_WINDOWPOS_CENTERED,
                                            sdl2.SDL_WINDOWPOS_CENTERED, w, h, sdl2.SDL_WINDOW_RESIZABLE)
        if not self.window:
            return log(f"Screen: {sdl2.SDL_GetError().decode()}")
        self.renderer = sdl2.SDL_CreateRenderer(self.window, -1, 0)
        self.decoder = av.CodecContext.create("h264", "r")
        self.decoder.flags |= av.codec.context.Flags.low_delay  # each packet out as a frame right away
        self.shown_title = self.title
        sdl2.SDL_RaiseWindow(self.window)
        self.resume()

    def resume(self):
        """Take video again, starting at a fresh key frame."""
        self.packets.clear()
        self.need_key = self.showing = True
        self.to_phone(b'{"t":"sync"}')  # the key frame now, not at the next interval

    def close(self):
        self.showing = False
        sdl2.SDL_DestroyRenderer(self.renderer)  # frees the textures too
        sdl2.SDL_DestroyWindow(self.window)
        self.window = self.renderer = self.texture = self.decoder = self.stroke = self.qr = self.qr_for = None

    def fit(self, w, h):
        """Window size for a w x h video: what fits in 90% of the screen, never enlarged."""
        r = sdl2.SDL_Rect()
        sdl2.SDL_GetDisplayUsableBounds(sdl2.SDL_GetWindowDisplayIndex(self.window) if self.window else 0, r)
        s = min(1, r.w * 0.9 / w, r.h * 0.9 / h)
        return int(w * s), int(h * s)

    def handle(self, e):
        if e.type == self.wake:
            if self.title != self.shown_title:  # connected, disconnected or a new address: draw that too
                self.shown_title = self.title
                sdl2.SDL_SetWindowTitle(self.window, self.title.encode())
                self.draw()
            self.drain()
        elif e.type == sdl2.SDL_WINDOWEVENT:
            what = e.window.event
            if what == sdl2.SDL_WINDOWEVENT_CLOSE:
                self.close()
            elif what == sdl2.SDL_WINDOWEVENT_MINIMIZED:
                self.showing = False
            elif what == sdl2.SDL_WINDOWEVENT_RESTORED and not self.showing:
                self.resume()
            elif what in (sdl2.SDL_WINDOWEVENT_EXPOSED, sdl2.SDL_WINDOWEVENT_SIZE_CHANGED):
                self.draw()
        elif e.type == sdl2.SDL_MOUSEBUTTONDOWN:
            b = e.button
            if b.button == sdl2.SDL_BUTTON_LEFT:
                self.stroke, self.stroke_at = [self.pos(b.x, b.y)], b.timestamp
            elif b.button in (sdl2.SDL_BUTTON_RIGHT, sdl2.SDL_BUTTON_MIDDLE):
                self.send(t="key", k="back" if b.button == sdl2.SDL_BUTTON_RIGHT else "home")
        elif e.type == sdl2.SDL_MOUSEMOTION:
            self.mouse = (e.motion.x, e.motion.y)
            if self.stroke and (p := self.pos(*self.mouse)) != self.stroke[-1]:
                self.stroke.append(p)
        elif e.type == sdl2.SDL_MOUSEBUTTONUP and e.button.button == sdl2.SDL_BUTTON_LEFT and self.stroke:
            # the phone replays the whole stroke on release, with its duration: tap, long press or swipe
            self.stroke.append(self.pos(e.button.x, e.button.y))
            self.send(t="gesture", pts=self.stroke, ms=max(1, e.button.timestamp - self.stroke_at))
            self.stroke = None
        elif e.type == sdl2.SDL_MOUSEWHEEL and e.wheel.y and e.wheel.timestamp - self.wheel_at >= 250:
            self.wheel_at = e.wheel.timestamp
            x, y = self.pos(*self.mouse)
            up = (e.wheel.y > 0) != (e.wheel.direction == sdl2.SDL_MOUSEWHEEL_FLIPPED)
            self.send(t="gesture", pts=[[x, y], [x, clamp(y + SCROLL if up else y - SCROLL)]], ms=150)
        elif e.type == sdl2.SDL_KEYDOWN:
            key, mod = e.key.keysym.sym, e.key.keysym.mod
            if mod & sdl2.KMOD_CTRL and key == sdl2.SDLK_v:
                if text := (sdl2.SDL_GetClipboardText() or b"").decode(errors="replace"):
                    self.send(t="text", s=text)
            elif name := (self.alt_keys if mod & sdl2.KMOD_LALT else self.keys).get(key):
                self.send(t="key", k=name)
        elif e.type == sdl2.SDL_TEXTINPUT:
            self.send(t="text", s=e.text.text.decode(errors="replace"))

    def drain(self):
        """Decode what arrived, draw the newest frame."""
        frame = None
        while self.showing and self.packets:
            packet = self.packets.popleft()
            if self.need_key and packet[0] != 1:
                continue  # a picture can only start at a key frame
            self.need_key = False
            try:
                for frame in self.decoder.decode(av.Packet(packet[1:])):
                    pass
            except av.FFmpegError as e:
                log(f"Screen: {e}, waiting for a key frame")
                return self.resume()
        if frame:
            self.draw(frame)

    def draw(self, frame=None):
        if frame:
            if frame.format.name not in ("yuv420p", "yuvj420p"):
                frame = frame.reformat(format="yuv420p")
            if (frame.width, frame.height) != self.size or not self.texture:
                self.new_texture(frame.width, frame.height)
            # as the encoder tagged it: full range, BT.709, or else BT.601
            sdl2.SDL_SetYUVConversionMode(sdl2.surface.SDL_YUV_CONVERSION_JPEG if frame.color_range == 2 else
                                          sdl2.surface.SDL_YUV_CONVERSION_BT709 if frame.colorspace == 1 else
                                          sdl2.surface.SDL_YUV_CONVERSION_BT601)
            y, u, v = ((ctypes.cast(p.buffer_ptr, ctypes.POINTER(ctypes.c_ubyte)), p.line_size) for p in frame.planes)
            sdl2.SDL_UpdateYUVTexture(self.texture, None, *y, *u, *v)
        qr = not self.phone and self.pair
        sdl2.SDL_SetRenderDrawColor(self.renderer, *((255, 255, 255) if qr else (0, 0, 0)), 255)
        sdl2.SDL_RenderClear(self.renderer)
        if qr:  # no phone yet: the QR code for the Mirror app to scan
            self.draw_qr()
        elif self.texture:
            sdl2.SDL_RenderSetLogicalSize(self.renderer, *self.size)  # letterboxed, mouse events in video pixels
            sdl2.SDL_RenderCopy(self.renderer, self.texture, None, None)
        sdl2.SDL_RenderPresent(self.renderer)

    def draw_qr(self):
        if self.qr_for != self.pair:
            rows = list(segno.make(self.pair, error="m").matrix_iter(border=4))  # with the white margin scanners need
            n = len(rows)
            if self.qr:
                sdl2.SDL_DestroyTexture(self.qr)
            self.qr = sdl2.SDL_CreateTexture(self.renderer, sdl2.SDL_PIXELFORMAT_RGB24, sdl2.SDL_TEXTUREACCESS_STATIC, n, n)
            sdl2.SDL_UpdateTexture(self.qr, None, bytes(0 if dark else 255 for row in rows for dark in row for _ in "rgb"), n * 3)
            sdl2.SDL_SetTextureScaleMode(self.qr, sdl2.SDL_ScaleModeNearest)  # sharp modules, not blurred ones
            self.qr_for, self.qr_size = self.pair, n
        sdl2.SDL_RenderSetLogicalSize(self.renderer, self.qr_size, self.qr_size)
        sdl2.SDL_RenderCopy(self.renderer, self.qr, None, None)

    def new_texture(self, w, h):
        if self.texture:
            sdl2.SDL_DestroyTexture(self.texture)
        self.texture = sdl2.SDL_CreateTexture(self.renderer, sdl2.SDL_PIXELFORMAT_IYUV,
                                              sdl2.SDL_TEXTUREACCESS_STREAMING, w, h)
        self.size = (w, h)
        # The phone's shape (first frame, or it rotated), around the window's old centre and back inside the
        # screen, so a portrait window turning landscape doesn't grow off the right edge.
        x, y, old_w, old_h, top, left, bottom, right = (ctypes.c_int() for _ in range(8))
        sdl2.SDL_GetWindowPosition(self.window, x, y)
        sdl2.SDL_GetWindowSize(self.window, old_w, old_h)
        sdl2.SDL_GetWindowBordersSize(self.window, top, left, bottom, right)  # title bar and frame
        r = sdl2.SDL_Rect()
        sdl2.SDL_GetDisplayUsableBounds(sdl2.SDL_GetWindowDisplayIndex(self.window), r)
        w, h = self.fit(w, h)
        sdl2.SDL_SetWindowSize(self.window, w, h)
        sdl2.SDL_SetWindowPosition(self.window,
                                   min(max(x.value + (old_w.value - w) // 2, r.x + left.value), r.x + r.w - right.value - w),
                                   min(max(y.value + (old_h.value - h) // 2, r.y + top.value), r.y + r.h - bottom.value - h))

    def pos(self, x, y):
        """Point in video pixels -> 0..1 on the phone screen."""
        return [round(clamp(x / self.size[0]), 4), round(clamp(y / self.size[1]), 4)]

    def send(self, **msg):
        self.to_phone(json.dumps(msg, separators=(",", ":")).encode())

    def to_phone(self, msg):
        if send := self.phone:
            with contextlib.suppress(OSError):  # the phone just left
                send(msg)

    def attach(self, conn):
        """A Mirror phone connected: its screen comes up, the window's input goes to it."""
        self.phone = lambda msg: conn.sendall(len(msg).to_bytes(4, "big") + msg)  # only the screen thread writes
        self.title = f"Mirror - {conn.getpeername()[0]}"
        self.show()

    def detach(self):
        self.phone = None
        self.title = self.waiting
        self.poke()

    def pairing(self, address):
        """Where the Mirror app connects to: the QR code until a phone does, and the title for typing it in."""
        self.pair = f"mirror://{address}"
        self.waiting = f"Mirror - scan the QR code in the phone app, or enter {address}"
        if not self.phone:
            self.title = self.waiting
        self.poke()

    def show(self):
        """Open the window, or bring it to the front."""
        self.want.set()
        self.poke()

    def video(self, packet):
        if self.showing:
            self.packets.append(packet)
            self.poke()

    def poke(self):
        """Wake the screen thread, SDL_PushEvent is thread safe."""
        e = sdl2.SDL_Event()
        e.type = self.wake
        sdl2.SDL_PushEvent(e)


class Receiver:
    """Listener and connection state, driven by the start/stop commands."""

    def __init__(self, player, mode, ip, port, screen=None):
        self.player, self.mode, self.ip, self.port = player, mode, ip, port  # ip "auto" = IP on the hotspot
        self.screen = screen  # None: the Mirror app's mic still plays, its video is dropped
        self.sock = self.conn = self.thread = None
        self.host = self.address = ""  # address = ip:port for the phone
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
        self.address = f"{ip}:{self.port}"
        if self.screen and self.mode == "tcp":  # the Mirror app only speaks tcp
            self.screen.pairing(self.address)
        mirror = f"Mirror app {self.address} then Sambung, or " if self.mode == "tcp" else ""
        log(f"On the phone: {mirror}AndroidMic mode {PHONE_MODE[self.mode]}, IP {ip}, port {self.port}, then tap Connect")

    def listening(self):
        self.state, self.where, self.fmt, self.t0 = "LISTENING", f"{self.host}:{self.port} ({self.mode})", "", None
        log(f"Listening on {self.where}")
        if self.ip == "auto" and (now := primary_ip()) != self.host:
            log(f"This PC's IP changed to {now} (hotspot restarted?), type start to listen there")

    def connected(self, addr, handshake_ms=None, app="AndroidMic"):
        self.state, self.where, self.t0 = "CONNECTED", f"{addr[0]}:{addr[1]}", time.perf_counter()
        self.player.reset_stats()
        log(f"Connected on {self.where}, {app}" + (f" (handshake {handshake_ms:.0f} ms)" if handshake_ms is not None else ""))

    def audio(self, msg):
        self.play(*decode_audio(msg))

    def mirror(self, packet):
        """[type][data] from the Mirror app: 2 = 44100 Hz mono 16-bit mic, else H.264 for the window."""
        if packet[0] == 2:
            if len(packet) > 1:  # no samples: the phone keeping the connection while its screen is still, mic off
                self.play(44100, 1, "i16", (np.frombuffer(packet, "<i2", offset=1).astype(np.float32) / 32768).reshape(-1, 1))
        elif self.screen:
            self.screen.video(packet)

    def play(self, rate, ch, fmt, x):
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
                if (hello := f.read(len(CHECK_1))) not in (CHECK_1, MIRROR_1):
                    raise ValueError(f"bad handshake {hello!r}")
                mirror = hello == MIRROR_1
                conn.sendall(MIRROR_2 if mirror else CHECK_2)
                self.conn = conn
                self.connected(addr, (time.perf_counter() - t0) * 1000, "Mirror app" if mirror else "AndroidMic")
                conn.settimeout(5)  # a phone that vanished without closing (hotspot off)
                if mirror and self.screen:
                    self.screen.attach(conn)
                handle = self.mirror if mirror else self.audio
                while len(head := f.read(4)) == 4:
                    if (n := int.from_bytes(head, "big")) > MAX_PACKET:
                        raise ValueError(f"{n} byte packet, not a phone")
                    handle(f.read(n))
                reason = "phone closed the connection"
            except Exception as e:  # a broken session ends here, the listener keeps going
                reason = str(e) or type(e).__name__
            finally:
                if self.screen:
                    self.screen.detach()
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


TRAY_COLORS = {"STOPPED": (128, 128, 128), "LISTENING": (230, 150, 0), "CONNECTED": (40, 170, 70)}


def tray_image(color):
    """64x64 white mic on a disc in the state's color."""
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (64, 64))
    d = ImageDraw.Draw(img)
    d.ellipse((0, 0, 63, 63), fill=color)
    d.rounded_rectangle((24, 10, 40, 38), radius=8, fill="white")
    d.arc((16, 18, 48, 46), 0, 180, fill="white", width=4)
    d.line((32, 46, 32, 54), fill="white", width=4)
    d.line((23, 54, 41, 54), fill="white", width=4)
    return img


def tray(rx, saved, screen):
    """Notification area icon: color = state, tooltip = where, left click or Active = start/stop toggle,
    plus the Mirror window and the output device. Returns a function that removes it, or None when
    pystray isn't installed."""
    try:
        import pystray
    except ImportError:
        log("No tray icon: python -m pip install pystray pillow")
        return None
    images = {state: tray_image(color) for state, color in TRAY_COLORS.items()}
    done = threading.Event()

    def quit_app(icon):
        done.set()
        icon.stop()
        rx.stop()
        os._exit(0)  # the main thread is blocked in input(), nothing else wakes it

    def toggle():
        if rx.thread:
            rx.stop()
        else:
            rx.start()

    def command(line):
        try:
            run_command(rx, saved, line)
        except (ValueError, LookupError, OSError, sd.PortAudioError) as e:
            log(f"error: {e}")

    def output(i, name):  # pystray wants actions without extra parameters, hence the closures
        return pystray.MenuItem(name, lambda: command(f"device {i}"), checked=lambda _item: rx.player.device == i,
                                radio=True)

    def outputs():
        for i in wasapi()["devices"]:
            if (d := sd.query_devices(i))["max_output_channels"]:
                yield output(i, d["name"])

    def follow(icon):
        icon.visible = True
        shown = told = None
        while not done.wait(0.3):  # polled, the receiver changes state from its own thread
            if (now := (rx.state, rx.where)) != shown:
                shown = now
                icon.icon = images[rx.state]
                icon.title = f"Mirror: {rx.state} {rx.where}".rstrip()[:127]  # Windows tooltip limit
                icon.update_menu()  # refresh the Active check mark
                if rx.state == "LISTENING" and rx.address != told:  # the window may be closed, this always shows
                    told = rx.address
                    icon.notify(f"Scan the QR code in the Mirror window, or enter {rx.address} in the phone app", "Mirror")

    # default=True: a left click on the icon runs the toggle
    icon = pystray.Icon("android-mic", images["STOPPED"], "Mirror", pystray.Menu(
        pystray.MenuItem("Active", toggle, checked=lambda _item: rx.thread is not None, default=True),
        pystray.MenuItem("Show window", lambda: screen.show(), visible=screen is not None),  # QR code or phone
        pystray.MenuItem("Output", pystray.Menu(outputs)),
        pystray.MenuItem("Quit", quit_app),
    ))
    threading.Thread(target=icon.run, args=(follow,), daemon=True).start()

    def close():
        done.set()
        icon.stop()
    return close


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

    # Mirror app on the tcp listener: mic to the player, video to the window, the window's input back to the phone
    os.environ["SDL_VIDEODRIVER"] = "dummy"  # a window without a display
    screen = Screen()
    rx = Receiver(Sink(), "tcp", "127.0.0.1", 0, screen)
    rx.start()
    phone = socket.create_connection(rx.sock.getsockname(), timeout=2)
    phone.sendall(MIRROR_1)
    assert phone.recv(11) == MIRROR_2
    from_pc = phone.makefile("rb")

    def from_window():
        return json.loads(from_pc.read(int.from_bytes(from_pc.read(4), "big")))

    assert from_window() == {"t": "sync"}  # the window opened and asks for a key frame
    encoder = av.CodecContext.create("libx264", "w")
    encoder.width, encoder.height, encoder.pix_fmt = 64, 128, "yuv420p"
    encoder.time_base, encoder.options = fractions.Fraction(1, 30), {"tune": "zerolatency"}  # no B-frames, like phones
    key = b"".join(bytes(p) for p in encoder.encode(av.VideoFrame(64, 128, "yuv420p")))
    keepalive = framed(b"\x02")  # must not reach the player
    phone.sendall(keepalive + framed(b"\x02" + np.array([0, 16384], "<i2").tobytes()) + framed(b"\x01" + key))
    deadline = time.time() + 3
    while screen.size != (64, 128) and time.time() < deadline:
        time.sleep(0.02)
    assert screen.size == (64, 128), "key frame not decoded"
    e = sdl2.SDL_Event()
    for kind, button in ((sdl2.SDL_MOUSEBUTTONDOWN, sdl2.SDL_BUTTON_LEFT), (sdl2.SDL_MOUSEBUTTONUP, sdl2.SDL_BUTTON_LEFT),
                         (sdl2.SDL_MOUSEBUTTONDOWN, sdl2.SDL_BUTTON_RIGHT)):
        e.type, e.button.button, e.button.x, e.button.y = kind, button, 16, 96
        sdl2.SDL_PushEvent(e)
    e.type, e.text.text = sdl2.SDL_TEXTINPUT, "hé".encode()
    sdl2.SDL_PushEvent(e)
    tap = from_window()
    assert tap["t"] == "gesture" and tap["pts"] == [[0.25, 0.75]] * 2 and tap["ms"] >= 1, tap
    assert from_window() == {"t": "key", "k": "back"}
    assert from_window() == {"t": "text", "s": "hé"}
    got = rx.player.got
    assert len(got) == 1 and got[0][0] == 44100 and np.allclose(got[0][1][:, 0], [0, 0.5]), got
    for s in (from_pc, phone):  # a socket only really closes once its makefile is closed too
        s.close()
    assert screen.pair == "mirror://127.0.0.1:0", screen.pair  # the listen address, port 0 in this test
    deadline = time.time() + 3
    while screen.qr_for != screen.pair and time.time() < deadline:  # the phone left: the QR code is back
        time.sleep(0.02)
    assert screen.qr_for == screen.pair and screen.qr_size == 25 + 8, "QR code not drawn"  # version 2 + margin
    thread = rx.thread
    rx.stop()
    assert not thread.is_alive(), "mirror listener did not stop"
    print("selftest ok")


def already_running():
    """Mirror.exe runs once, a second copy would only fight over the port: point at the first one instead."""
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.CreateMutexW(None, False, "Local\\Mirror")  # held until this process exits
    if ctypes.get_last_error() != 183:  # ERROR_ALREADY_EXISTS
        return False
    ctypes.windll.user32.MessageBoxW(None, "Mirror is already running, its icon is in the taskbar notification area.",
                                     "Mirror", 0x40)
    return True


def main():
    global prompt
    if sys.stdout is None:  # pythonw (the startup shortcut): no console, log to a file, control via the tray
        sys.stdout = sys.stderr = open(LOGFILE, "a", encoding="utf-8", buffering=1)
    try:
        saved = load_settings()
    except ValueError as e:  # TOMLDecodeError is a ValueError too
        sys.exit(f"{CONFIG}: {e}")

    ap = argparse.ArgumentParser(
        description="Phone mic (AndroidMic or the Mirror app) and the Mirror app's screen: standalone, no adb. "
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
    ap.add_argument("--no-tray", action="store_true", help="no notification area (tray) icon")
    ap.add_argument("--background", action="store_true",
                    help="no window until a phone connects, for starting at sign-in")
    ap.add_argument("--selftest", action="store_true", help=argparse.SUPPRESS)
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    if args.list:
        return list_devices()
    if getattr(sys, "frozen", False) and already_running():
        return
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
    try:
        screen = Screen()
    except Exception as e:  # no av/pysdl2, or no display: the mic still works
        screen = None
        log(f"No screen window: {e}")
    rx = Receiver(player, run["mode"], run["ip"], run["port"], screen)
    close_tray = None if args.no_tray else tray(rx, saved, screen)
    rx.start()
    if screen and rx.mode == "tcp" and not args.background:
        screen.show()  # the QR code to pair the phone with
    try:
        while True:
            prompt = "mic> "
            try:
                line = input(prompt)
            except (EOFError, RuntimeError):  # no terminal (EOF) or pythonw (lost sys.stdin): serve until tray Quit or Ctrl+C
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
        if close_tray:
            close_tray()


if __name__ == "__main__":
    main()

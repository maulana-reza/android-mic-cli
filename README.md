# android-mic-cli

Sisi PC dari AndroidMic versi terminal, berdiri sendiri. **Aplikasi AndroidMic desktop tidak perlu
dipasang di laptop**, karena script ini sudah menggantikannya. HP mengirim suara mic lewat hotspot
HP, lalu PC memutarnya ke output device. Contohnya `CABLE Input` dari VB-Cable, supaya bisa dipakai
sebagai mic di Zoom atau Discord.

Yang dibutuhkan:
- Di laptop: Python 3.11+ dengan `numpy` dan `sounddevice` (`python -m pip install -r requirements.txt`).
- Di HP: app **AndroidMic** versi 2.2.x (APK) sebagai pengirim suara.
- Tanpa adb dan tanpa kabel.

## Cara menjalankan

1. Nyalakan hotspot HP, lalu sambungkan laptop ke hotspot itu.
2. Jalankan `android-mic.bat`, bisa dengan klik dua kali di Explorer atau lewat terminal:
   ```
   D:\PROJECT\android-mic-cli\android-mic.bat
   ```
3. Terminal akan menampilkan baris seperti ini:
   `On the phone: mode WIFI, IP 192.168.43.20, port 54345, then tap Connect`
4. Di app AndroidMic di HP, buka menu **Setting**:
   - **Connection mode** pilih `WIFI`.
   - **IP:PORT** isi dengan IP dan port dari baris tadi.

   Setelah itu tap **Connect**.
5. Kalau berhasil, terminal menampilkan `Connected on ...`. Ketik `watch` untuk melihat level suara,
   dan `quit` untuk keluar.

Untuk sekali jalan saja (tidak disimpan) bisa pakai flag: `-m tcp|udp`, `-i IP`, `-p PORT`,
`-d DEVICE`, `-l MS`, `--list`. Contoh: `android-mic.bat -d "cable input"`. Lihat `-h`.

## Perintah di terminal (prompt `mic>`)

| Perintah | Fungsi |
|---|---|
| `start` / `stop` | mulai / berhenti listen |
| `status` | status koneksi, format audio, level, buffer, dan setting |
| `watch` | level meter live, tekan Enter untuk keluar |
| `mode tcp\|udp` | `tcp` = mode `WIFI` di HP, `udp` = mode `UDP` di HP |
| `ip [auto\|alamat]` | alamat listen, tanpa argumen menampilkan IP laptop |
| `port <angka>` | port listen |
| `devices` / `device <no\|nama\|default>` | daftar / pilih output device |
| `latency <ms>` | target buffer, naikkan kalau suara kresek |
| `help` / `quit` | bantuan / keluar |

`mode`, `ip`, `port`, `device`, dan `latency` otomatis disimpan ke `config.toml` di folder ini.
File itu juga bisa diedit manual.

## Arti status

- `Connected on ... (handshake N ms)`: waktu jabat tangan jaringan.
- `First audio N ms after connect`: jeda sampai mic HP mulai merekam. Kalau angkanya besar, lag-nya ada di HP, bukan di laptop.
- `buf` = audio yang mengantre, `underrun` = audio telat datang (naikkan latency), `drop` = antrean kepenuhan sehingga dibuang.

## Catatan

- IP hotspot bisa berubah tiap hotspot dinyalakan ulang. Kalau muncul `This PC's IP changed`, ketik `start`, lalu ganti IP di HP.
- Output stream tetap terbuka saat idle, jadi tidak ada jeda membuka device ketika HP connect.
- Kalau output yang tersimpan tidak ada (misalnya headset Bluetooth mati), output pindah ke default Windows.
- Belum ada: denoise, amplify, efek, dan mode USB.
- Cek protokol tanpa HP dan tanpa audio device: `python android_mic_cli.py --selftest`.

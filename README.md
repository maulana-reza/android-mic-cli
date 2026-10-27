# android-mic-cli

HP Android jadi **mic dan layar kedua** untuk laptop lewat hotspot atau Wi-Fi. Tanpa adb, tanpa kabel,
dan tanpa aplikasi AndroidMic desktop.

- Suara mic HP diputar ke output device, misalnya `CABLE Input` dari VB-Cable, supaya bisa dipakai
  sebagai mic di Zoom atau Discord.
- Dengan app **Mirror** di HP, layar HP juga tampil di jendela laptop dan bisa dikontrol pakai mouse dan
  keyboard. Mic dan layar lewat satu koneksi yang sama. Decode-nya pakai FFmpeg dan render-nya pakai
  SDL2, seperti scrcpy.
- App **AndroidMic** 2.2.x tetap didukung sebagai pengirim mic saja.

| Folder | Isi |
|---|---|
| root | sisi PC: `android_mic_cli.py` (terminal), atau dibungkus jadi app Windows **Mirror** lewat `build.bat` |
| `android/` | app Android **Mirror** (Kotlin): kirim layar dan mic, jalankan klik/ketik dari laptop |

## Download

Ambil dari halaman [Releases](https://github.com/maulana-reza/android-mic-cli/releases):

- **Windows**: `Mirror-Setup-<versi>.exe`. Installer ini memasang untuk user ini saja (tanpa admin) dan
  membuat shortcut Start menu. Ada pilihan supaya Mirror jalan otomatis saat login. Installer belum
  ditandatangani, jadi SmartScreen mungkin menampilkan peringatan: klik **More info → Run anyway**.
- **Android 8+**: `Mirror-<versi>.apk`. Izinkan install dari sumber tidak dikenal. Di Android 13+, izin
  Accessibility untuk APK dari luar Play Store diblokir "restricted settings": buka **App info → ⋮ →
  Allow restricted settings**.

## Cara menjalankan

1. Sambungkan HP dan laptop ke jaringan yang sama (hotspot HP atau Wi-Fi yang sama).
2. Jalankan **Mirror** dari Start menu. Ikon mic muncul di tray, dan notifikasi menampilkan alamat yang
   harus diisi di HP, misalnya `On the phone connect to 192.168.43.20:54345`. Alamat ini juga terlihat
   kalau kursor diarahkan ke ikon tray.
3. Di app Mirror di HP, isi **IP:port** tersebut, tap **Sambung**, lalu izinkan mic dan rekam layar.
4. Jendela "Mirror - (IP HP)" terbuka dengan layar HP.
5. Supaya laptop bisa mengontrol HP, aktifkan sekali: di app tap **Izin kontrol** → **Mirror control** →
   aktifkan.

Kontrol di jendela:

| Laptop | HP |
|---|---|
| klik / tahan / drag | tap / long press / swipe |
| klik kanan, `Esc`, `Alt+B` | Back |
| klik tengah, `Alt+H` | Home |
| `Alt+S` | Recent apps |
| scroll wheel | scroll |
| ketik | ketik di kolom yang aktif |
| `Ctrl+V` | paste (pakai ini untuk password) |

Menutup jendela tidak memutus HP, mic tetap jalan. Untuk membukanya lagi, pakai menu tray **Show
screen**. Selama jendela ditutup atau di-minimize, video tidak di-decode sama sekali.

### Hanya mic, dengan app AndroidMic

Di HP, buka **Setting** AndroidMic. Pilih **Connection mode** `WIFI` (untuk `mode tcp`) atau `UDP`
(untuk `mode udp`), isi **IP:PORT** dari notifikasi atau baris `On the phone`, lalu tap **Connect**.

## Ikon tray

Warna ikon menunjukkan status:

- abu-abu = `STOPPED`
- oranye = `LISTENING`, menunggu HP connect
- hijau = `CONNECTED`

**Klik kiri** ikon untuk menyalakan/mematikan listener. Klik kanan untuk membuka menu:

- **Active**: centang berarti listener menyala.
- **Show screen**: buka jendela layar HP.
- **Output**: pilih output device (tersimpan).
- **Quit**: keluar.

Supaya ikon selalu terlihat, seret dari "hidden icons" (^) ke taskbar.

Pengaturan disimpan di `config.toml` di sebelah `Mirror.exe`, dan log ditulis ke `android-mic.log` di
folder yang sama.

## Lewat terminal

Butuh Python 3.11+. Pasang dependensinya sekali dengan `python -m pip install -r requirements.txt`,
lalu jalankan `android-mic.bat`. Fiturnya sama dengan app Windows, plus prompt `mic>`:

| Perintah | Fungsi |
|---|---|
| `start` / `stop` | mulai / berhenti listen |
| `status` | status koneksi, format audio, level, buffer, dan setting |
| `watch` | level meter live, tekan Enter untuk keluar |
| `mode tcp\|udp` | `tcp` = app Mirror atau mode `WIFI` AndroidMic, `udp` = mode `UDP` AndroidMic |
| `ip [auto\|alamat]` | alamat listen, tanpa argumen menampilkan IP laptop |
| `port <angka>` | port listen |
| `devices` / `device <no\|nama\|default>` | daftar / pilih output device |
| `latency <ms>` | target buffer, naikkan kalau suara kresek |
| `help` / `quit` | bantuan / keluar |

`mode`, `ip`, `port`, `device`, dan `latency` otomatis disimpan ke `config.toml` di folder ini. File itu
juga bisa diedit manual. Untuk sekali jalan saja (tidak disimpan) bisa pakai flag: `-m tcp|udp`, `-i IP`,
`-p PORT`, `-d DEVICE`, `-l MS`, `--list`, `--no-tray`. Contoh: `android-mic.bat -d "cable input"`.
Lihat `-h`.

Untuk auto start tanpa jendela terminal, buat shortcut di folder Startup Windows (`Win+R` →
`shell:startup`) dengan **Target** `pythonw.exe "<folder ini>\android_mic_cli.py"` dan **Start in**
folder ini. Kontrolnya lewat ikon tray, dan log ditulis ke `android-mic.log`.

## Build

App Windows: `build.bat` membuat `dist\Mirror\Mirror.exe`. Kalau
[Inno Setup 6](https://jrsoftware.org/isinfo.php) terpasang, sekalian dibuat installer
`dist\Mirror-Setup-<versi>.exe`.

App Android, dari folder `android/`:

```
gradlew.bat assembleRelease
```

Hasilnya di `android\app\build\outputs\apk\release\`. APK rilis ditandatangani dengan kunci dari
`android\keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). File itu tidak
masuk git. Tanpa file itu, APK rilis keluar tanpa tanda tangan. Untuk mencoba cepat, pakai
`gradlew.bat assembleDebug`.

Versi diatur di `android\app\build.gradle.kts` (`versionName`) dan `installer.iss` (`AppVersion`).

## Arti status

- `Connected on ... (handshake N ms)`: waktu jabat tangan jaringan.
- `First audio N ms after connect`: jeda sampai mic HP mulai merekam. Kalau angkanya besar, lag-nya ada di HP, bukan di laptop.
- `buf` = audio yang mengantre, `underrun` = audio telat datang (naikkan latency), `drop` = antrean kepenuhan sehingga dibuang.

## Performa

Diukur dengan itel S666LN di Wi-Fi rumah dan laptop 14 core:

- Jendela memakai 0,2% total CPU (2% dari satu core) saat mirroring, dengan RAM ±170 MB.
- Decode plus gambar ±2 ms per frame, untuk video 712×1600 di 60 fps.
- Latensi bolak-balik, dari klik di laptop sampai perubahan layar HP tampil di laptop: median 65–85 ms.

## Catatan

- **Keamanan:** koneksi HP ke laptop tidak dienkripsi. Pakai di hotspot HP atau Wi-Fi rumah, jangan
  di Wi-Fi publik.
- IP hotspot bisa berubah tiap hotspot dinyalakan ulang. Kalau muncul `This PC's IP changed`, matikan
  lalu nyalakan lagi **Active** di tray (atau ketik `start`), lalu ganti IP di HP.
- App Mirror di HP menyambung ulang sendiri kalau koneksi putus, jadi izin rekam layar tidak perlu diulang.
- Output stream tetap terbuka saat idle, jadi tidak ada jeda membuka device ketika HP connect.
- Kalau output yang tersimpan tidak ada (misalnya headset Bluetooth mati), output pindah ke default Windows.
- Batasan: swipe dijalankan setelah mouse dilepas (bukan drag live), ketikan selalu masuk di akhir
  kolom, mic 44,1 kHz mono.
- Belum ada: denoise, amplify, efek, dan mode USB.
- Untuk mengecek protokol, decode, dan jendela tanpa HP: `python android_mic_cli.py --selftest`
  (atau `Mirror.exe --selftest`).

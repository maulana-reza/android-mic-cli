@echo off
rem Builds the Windows app into dist\Mirror\ and, when Inno Setup 6 is installed, the installer dist\Mirror-Setup-*.exe.
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (python -m venv .venv || exit /b 1)
.venv\Scripts\python -m pip install -q --disable-pip-version-check -r requirements.txt pyinstaller || exit /b 1
if not exist build mkdir build
.venv\Scripts\python -c "import android_mic_cli as m; m.tray_image(m.TRAY_COLORS['CONNECTED']).save('build/mirror.ico', sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64)])" || exit /b 1
rem Only SDL2.dll of pysdl2-dll, and none of the TLS, font or extra image codecs nothing here loads.
.venv\Scripts\pyinstaller --noconfirm --clean --log-level WARN --windowed --name Mirror --icon "%~dp0build\mirror.ico" ^
  --specpath build --workpath build --distpath dist --add-binary "%~dp0.venv\Lib\site-packages\sdl2dll\dll\SDL2.dll;sdl2dll\dll" ^
  --exclude-module ssl --exclude-module _ssl --exclude-module _hashlib --exclude-module PIL._avif --exclude-module PIL._webp ^
  --exclude-module PIL._imagingft --exclude-module PIL._imagingcms --exclude-module PIL._imagingtk android_mic_cli.py || exit /b 1
set ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe
if not exist "%ISCC%" set ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe
if not exist "%ISCC%" (echo dist\Mirror\Mirror.exe is ready. For the installer install Inno Setup 6 and run this again. & exit /b 0)
"%ISCC%" /Q installer.iss || exit /b 1
echo dist\Mirror\Mirror.exe and the installer dist\Mirror-Setup-*.exe are ready.

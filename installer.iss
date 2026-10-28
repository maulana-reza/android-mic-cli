; Mirror for Windows, compiled by build.bat with Inno Setup 6 into dist\Mirror-Setup-<version>.exe.
; Installs for the current user only, so config.toml next to Mirror.exe stays writable.
#define AppVersion "0.4.1"

[Setup]
AppId={{F01847B5-6431-4F41-99E0-8F8D60546097}
AppName=Mirror
AppVersion={#AppVersion}
AppPublisher=maulana-reza
AppPublisherURL=https://github.com/maulana-reza/android-mic-cli
AppSupportURL=https://github.com/maulana-reza/android-mic-cli/issues
DefaultDirName={autopf}\Mirror
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=dist
OutputBaseFilename=Mirror-Setup-{#AppVersion}
SetupIconFile=build\mirror.ico
UninstallDisplayIcon={app}\Mirror.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "startup"; Description: "Start Mirror when I sign in to Windows"; Flags: unchecked
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; Flags: unchecked

[Files]
Source: "dist\Mirror\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Mirror"; Filename: "{app}\Mirror.exe"
Name: "{userstartup}\Mirror"; Filename: "{app}\Mirror.exe"; Parameters: "--background"; Tasks: startup
Name: "{autodesktop}\Mirror"; Filename: "{app}\Mirror.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Mirror.exe"; Description: "{cm:LaunchProgram,Mirror}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: files; Name: "{app}\config.toml"
Type: files; Name: "{app}\android-mic.log"

; Hypurr for Windows: per-user installer (no admin rights).
; Installs to %LOCALAPPDATA%\Programs\Hypurr with a Start menu shortcut and an uninstaller.
; Build: iscc /DAppVersion=0.1.0 /DSrcDir=<dir with hypurr.exe, hypurr-host.exe + hypurr-screen.exe> hypurr.iss
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#ifndef SrcDir
  #define SrcDir "..\..\dist\windows"
#endif

[Setup]
AppId={{6F1E2A8C-4B7D-4E8A-9C1F-2D3B4A5C6E7F}
AppName=Hypurr
AppVersion={#AppVersion}
AppPublisher=Hypurr
DefaultDirName={localappdata}\Programs\Hypurr
DefaultGroupName=Hypurr
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=
OutputBaseFilename=HypurrSetup-{#AppVersion}
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\hypurr.exe
UninstallDisplayName=Hypurr
WizardStyle=modern
CloseApplications=yes

[Files]
Source: "{#SrcDir}\hypurr.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SrcDir}\hypurr-host.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SrcDir}\hypurr-screen.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SrcDir}\LICENSE.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SrcDir}\NOTICE.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{userprograms}\Hypurr"; Filename: "{app}\hypurr.exe"
Name: "{userprograms}\Uninstall Hypurr"; Filename: "{uninstallexe}"

[Run]
Filename: "{app}\hypurr.exe"; Description: "Open Hypurr"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM hypurr.exe"; Flags: runhidden; RunOnceId: "KillApp"
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM hypurr-host.exe"; Flags: runhidden; RunOnceId: "KillHost"
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM hypurr-screen.exe"; Flags: runhidden; RunOnceId: "KillScreen"
; Remove the optional "Start with Windows" entry the app may have written (HKCU only).
Filename: "{sys}\reg.exe"; Parameters: "delete HKCU\Software\Microsoft\Windows\CurrentVersion\Run /v Hypurr /f"; Flags: runhidden; RunOnceId: "RunKey"

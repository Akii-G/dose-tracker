; Inno Setup recipe for Dose on Windows. Built by .github/workflows/windows.yml:
;   ISCC /DVersion=1.1.0 packaging\windows\dose.iss
; Installs for all users in Program Files (it adds the firewall rule for phone sync), adds a
; Start menu entry, and registers dose: so the reminder's Taken and Later buttons work. Your
; data stays in %LOCALAPPDATA%\Dose, also when you uninstall.

#ifndef Version
  #define Version "0.0.0"
#endif
#ifndef Source
  #define Source "..\..\dist\Dose"
#endif

[Setup]
AppId={{01DA8755-CC70-44A9-8A19-908951CD366F}
AppName=Dose
AppVersion={#Version}
AppVerName=Dose {#Version}
AppPublisher=Dose
AppPublisherURL=https://github.com/Akii-G/dose-tracker
AppSupportURL=https://github.com/Akii-G/dose-tracker/issues
DefaultDirName={autopf}\Dose
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir=..\..\dist
OutputBaseFilename=Dose-Setup-{#Version}
SetupIconFile=dose.ico
UninstallDisplayIcon={app}\Dose.exe
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=force
RestartApplications=no
LicenseFile=..\..\LICENSE

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "it"; MessagesFile: "compiler:Languages\Italian.isl"

[CustomMessages]
en.SyncRule=Dose phone sync
it.SyncRule=Dose sincronizzazione telefono
en.Launch=Open Dose
it.Launch=Apri Dose
en.Desktop=Shortcut on the desktop
it.Desktop=Collegamento sul desktop

[Tasks]
Name: "desktopicon"; Description: "{cm:Desktop}"; Flags: unchecked

[Files]
Source: "{#Source}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; AppUserModelID: Windows shows the reminders under this entry
Name: "{autoprograms}\Dose"; Filename: "{app}\Dose.exe"; AppUserModelID: "Dose.Dose"
Name: "{autodesktop}\Dose"; Filename: "{app}\Dose.exe"; Tasks: desktopicon

[Registry]
; dose: links (the reminder's Taken and Later buttons, and a click on it) open Dose
Root: HKA; Subkey: "Software\Classes\dose"; ValueType: string; ValueName: ""; ValueData: "URL:Dose"; Flags: uninsdeletekey
Root: HKA; Subkey: "Software\Classes\dose"; ValueType: string; ValueName: "URL Protocol"; ValueData: ""
Root: HKA; Subkey: "Software\Classes\dose\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\Dose.exe,0"
Root: HKA; Subkey: "Software\Classes\dose\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\Dose.exe"" ""%1"""

[Run]
; phone sync: let the phones reach port 8766, on private (home) networks only
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{cm:SyncRule}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""{cm:SyncRule}"" dir=in action=allow protocol=TCP localport=8766 profile=private program=""{app}\Dose.exe"""; Flags: runhidden
; after an update: start the background part (reminders, sync) again if it was on
Filename: "{app}\Dose.exe"; Parameters: "--resume-background"; Flags: nowait runasoriginaluser
Filename: "{app}\Dose.exe"; Description: "{cm:Launch}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM Dose.exe /F"; Flags: runhidden; RunOnceId: "StopDose"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{cm:SyncRule}"""; Flags: runhidden; RunOnceId: "DelSyncRule"
Filename: "{sys}\reg.exe"; Parameters: "delete HKCU\Software\Microsoft\Windows\CurrentVersion\Run /v ""Dose reminders"" /f"; Flags: runhidden; RunOnceId: "DelAutostart"

[Code]
// Close Dose (the window and the background part) before an update, so its files can be replaced.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/IM Dose.exe /F', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Result := '';
end;

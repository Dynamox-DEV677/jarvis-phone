@echo off
rem Optional: start the JARVIS PC bridge (minimised) every time you sign in to Windows.
rem To undo it, delete "JARVIS PC bridge.lnk" from the folder this opens: shell:startup
set "LNK=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\JARVIS PC bridge.lnk"
powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:LNK); $s.TargetPath='%~dp0START-PC-BRIDGE.cmd'; $s.WorkingDirectory='%~dp0'; $s.WindowStyle=7; $s.Save()"
echo Done. The bridge will start by itself when you sign in.
pause

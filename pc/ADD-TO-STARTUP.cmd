@echo off
rem Optional: start the JARVIS laptop worker (minimised) every time you sign in to Windows.
rem To undo it, delete "JARVIS laptop.lnk" from the folder this opens: shell:startup
set "LNK=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\JARVIS laptop.lnk"
powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut($env:LNK); $s.TargetPath='%~dp0START-JARVIS-LAPTOP.cmd'; $s.WorkingDirectory='%~dp0'; $s.WindowStyle=7; $s.Save()"
echo Done. JARVIS will start by itself when you sign in.
pause

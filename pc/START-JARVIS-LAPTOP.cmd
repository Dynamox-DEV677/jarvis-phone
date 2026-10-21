@echo off
title JARVIS laptop
rem Lets JARVIS on your phone use this laptop from anywhere, through your Supabase project,
rem and answers "Hey Jarvis" (or Ctrl+Alt+J) on the laptop itself, with the orange glow.
rem Keep this window open (it can be minimised). Close it to stop JARVIS.
where node >nul 2>nul || (echo Node.js isn't installed. Get it from https://nodejs.org & pause & exit /b 1)
python -c "import sys" >nul 2>nul || goto run
rem JARVIS's voice (edge-tts). Microsoft changes its voice service now and then, and new
rem versions of edge-tts follow, so keep it up to date.
echo Checking JARVIS's voice...
python -m pip install --user --quiet --disable-pip-version-check --no-warn-script-location --timeout 8 --retries 0 --upgrade edge-tts >nul 2>nul
rem JARVIS's ears and glow on this laptop: installed once.
python -c "import vosk, sounddevice, faster_whisper, PIL" >nul 2>nul && goto run
echo Setting up "Hey Jarvis" on this laptop (first time only, a minute or two)...
python -m pip install --user --quiet --disable-pip-version-check --no-warn-script-location vosk sounddevice faster-whisper pillow
:run
node "%~dp0worker.mjs"
pause

@echo off
title JARVIS laptop
rem Lets JARVIS on your phone use this laptop from anywhere, through your Supabase project.
rem Keep this window open (it can be minimised). Close it to cut the phone off.
where node >nul 2>nul || (echo Node.js isn't installed. Get it from https://nodejs.org & pause & exit /b 1)
node "%~dp0worker.mjs"
pause

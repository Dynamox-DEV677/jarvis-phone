#!/data/data/com.termux/files/usr/bin/bash
# Started by Termux:Boot after the phone restarts: brings JARVIS's brain back up.
termux-wake-lock
exec "$PREFIX/bin/jarvis" start

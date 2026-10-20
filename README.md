# JARVIS for your phone

A voice assistant for Android. Tap the orange orb on the right edge of the screen, or say **"Jarvis"**. An orange glow slides in from the sides of the screen and listens. JARVIS thinks, then answers out loud, and the glow blinks with every word.

- **The brain is your own Google AI Pro plan.** It runs through Google's Antigravity CLI (`agy`) inside Termux, signed in with your Google account. No API keys, nothing to pay.
- **It searches the web** and can use your phone: battery, torch, volume, links, reminders.
- **It can use your PC when the PC is on**, over your home Wi-Fi. It can check the PC's status, open apps and websites, look at the screen, lock it, and play or pause music. It can also hand bigger jobs to JARVIS on the PC or to **Claude Code**.

```
 phone                                              your PC (optional)
 ┌──────────────────────────────┐                   ┌────────────────────────────┐
 │ JARVIS app                   │                   │ PC bridge (pc/bridge.mjs)  │
 │  orb · wake word · glow      │                   │  pc_status  pc_open        │
 │  speech in · voice out       │                   │  pc_screenshot  pc_lock    │
 │        │ 127.0.0.1:8765      │   home Wi-Fi,     │  pc_media                  │
 │ Termux │                     │   secret token    │  pc_jarvis  → agy jarvis   │
 │  brain (brain/brain.mjs)     │ ────────────────▶ │  claude_code → Claude Code │
 │   └─ agy --agent jarvis-phone│                   └────────────────────────────┘
 │        (your Google AI Pro)  │
 └──────────────────────────────┘
```

## Two places JARVIS can think

| | **On your laptop** (recommended) | **On your phone** |
|---|---|---|
| Brain | Your Antigravity `jarvis` agent on the laptop | `agy` inside Termux on the phone |
| Works from | Anywhere: home Wi-Fi or mobile data | Anywhere, but no laptop access |
| Needs | Laptop on, with JARVIS running; a free Supabase project | Termux + `agy` signed in on the phone |
| Laptop screenshots | Yes, sent to the phone | Only on the same Wi-Fi |

## Laptop setup (works from anywhere)

```
phone (anywhere) ──▶ your Supabase project (jarvis_jobs) ◀── realtime ── laptop worker ──▶ JARVIS (agy)
```

1. **Make a free Supabase project** at [supabase.com](https://supabase.com) → New project. Name it `jarvis-relay` and pick the Mumbai region.
2. In the project, open **SQL Editor** → **New query**. Paste all of [`supabase/relay.sql`](supabase/relay.sql) and press **Run**.
3. On the laptop, get this repo (`git clone https://github.com/Dynamox-DEV677/jarvis-phone`) and double-click **`pc\SETUP-JARVIS-LAPTOP.cmd`**. Paste:
   - the **Project URL** (Settings → API);
   - the **anon / publishable key**. Never the secret / service_role key.
4. A QR code opens. In the JARVIS app, tap **Scan QR code** and point the phone at it. The scanner is Google's, built into Play services: no camera permission, and the first time it downloads in a few seconds. The app should then say **Online**.
5. Double-click **`pc\START-JARVIS-LAPTOP.cmd`** and keep it running. **`pc\ADD-TO-STARTUP.cmd`** makes it start whenever you sign in.

You only need the JARVIS app on the phone: no Termux and no `agy` sign-in there. Say "send me a screenshot" or tap **Laptop screenshot**, and the laptop's screen appears on your phone. Any screenshot JARVIS takes while doing a task comes to your phone too.

**Security:** the phone and laptop share one long secret, made by the laptop and stored in `%USERPROFILE%\.jarvis-phone\relay.json`. The database refuses everything without it. Anyone who has it can give your laptop tasks, so treat the QR code like a password and delete `pair-qr.png` after pairing. To change the secret, delete `relay.json`, run setup again, and re-pair.

**JARVIS's voice:** the laptop records every reply in a British neural voice (Microsoft's free "Ryan" voice, through [edge-tts](https://github.com/rany2/edge-tts)) and sends it with the answer. The orange glow blinks on each word. For this you need [Python](https://www.python.org) on the laptop; `START-JARVIS-LAPTOP` installs and updates edge-tts by itself. Without it, the phone uses its own voice. To change the voice, add `"voice": "en-GB-ThomasNeural"` to `relay.json`. List the voices with `python -m edge_tts --list-voices`. `""` turns it off. In the app, **Voice** lets you pick one of the phone's voices instead.

**Keep the laptop awake:** in Windows Settings → System → Power, set sleep to *Never* while plugged in, and closing the lid to *Do nothing*. A sleeping laptop can't answer.

**Live screen mirroring:** use Google's free **Chrome Remote Desktop**. On the laptop, go to remotedesktop.google.com/access and set up remote access with a PIN. On the phone, install the Chrome Remote Desktop app and sign in with the same Google account.

## Phone-only setup (Termux)

You need **Termux** and **Termux:API** from **[F-Droid](https://f-droid.org)**. The Play Store version of Termux is outdated and won't work. Install both apps from F-Droid.

1. Open Termux and run:

   ```bash
   curl -sL dynamox-dev677.github.io/jarvis-phone/i | bash
   ```

   This is the long form of the same command:
   `curl -fsSL https://raw.githubusercontent.com/Dynamox-DEV677/jarvis-phone/main/termux/install.sh | bash`

   It installs Node.js, git, Google's official Antigravity CLI and JARVIS, and asks what JARVIS should call you.

2. Sign in to Antigravity with the Google account that has AI Pro:

   ```bash
   agy
   ```

   Open the link it shows, sign in, then type `/exit`.

3. Start the brain:

   ```bash
   jarvis start
   ```

4. Install the app:

   ```bash
   jarvis app
   ```

   If Android asks, allow Termux to install apps.

5. Open **JARVIS** and allow what it asks for: the microphone, notifications, and "Display over other apps". Then get the token:

   ```bash
   jarvis token
   ```

   Tap **Paste** in the app, then **Test**. It should say **Connected**.

6. Tap **Start JARVIS**. The orb appears on the right edge. Tap it and talk.

Optional extras in the app:
- **Wake word "Jarvis".** It downloads a small offline speech model (36 MB) once. The sound never leaves the phone. It uses a little battery.
- **Start the brain for you.** JARVIS can run `jarvis start` in Termux by itself when the brain isn't running.
- **Unrestricted battery.** Stops Android from putting JARVIS to sleep.
- **Quick-settings tile.** Swipe down twice, tap the pencil, and add the **JARVIS** tile.
- **App shortcut.** Long-press the JARVIS icon and pick "Talk to JARVIS".

## 2. Let JARVIS use your PC (optional)

On the Windows PC (it needs [Node.js](https://nodejs.org)):

1. Get this repository on the PC (`git clone https://github.com/Dynamox-DEV677/jarvis-phone`), then double-click **`pc\START-PC-BRIDGE.cmd`**.
2. The first time, Windows asks whether Node.js may use the network. Allow **private networks** only.
3. The window shows one line like this:

   ```
   jarvis pair 192.168.1.20:8766 AbC...xyz
   ```

   Run that line in Termux on the phone.

Keep the bridge window open while you want JARVIS to reach the PC. To start it every time you sign in, run `pc\ADD-TO-STARTUP.cmd`.

What the PC tools can do is fixed in `pc/bridge.mjs`:

| Tool | What it does |
|---|---|
| `pc_status` | CPU, memory, battery, disks, uptime, heaviest apps |
| `pc_open` | Opens an app, website, file or folder. Refuses shells, scripts, installers and `C:\Windows` |
| `pc_screenshot` | Takes a screenshot. The PC describes what's on it in words |
| `pc_lock` | Locks the PC |
| `pc_media` | Play/pause, next, previous, volume |
| `pc_jarvis` | Hands a task to your JARVIS agent in the Antigravity CLI on the PC. It's told not to delete, uninstall or change settings in voice runs |
| `claude_code` | Asks Claude Code. It can search the web, read code, and edit files only inside the project folders listed in `%USERPROFILE%\.jarvis-phone\pc.json`. It can never run commands |

## Using it

- **Talk:** tap the orb, use the tile, use the shortcut, or say "Jarvis". Then speak.
- **Stop:** tap the orb again, or say "stop".
- **New topic:** say "new conversation". JARVIS also starts fresh after 30 quiet minutes.
- **Type instead:** use the box in the app.
- **Move the orb:** drag it up or down. Hold it to open the app.

Examples:
- "What's the weather in Chennai?"
- "Turn on the torch."
- "How's my PC doing?"
- "Open YouTube on my PC."
- "What's on my PC screen?"
- "Pause the music."
- "Ask Claude Code to explain the login code in Kairo."

## Termux commands

```
jarvis start | stop | status     run the brain
jarvis token                     the token for the app
jarvis app                       install or update the app
jarvis pair <ip>:<port> <token>  let JARVIS use your PC
jarvis ask "question"            ask from the terminal
jarvis new                       new conversation
jarvis model [name]              change the Gemini model (list: agy models)
jarvis name <name> [address]     what JARVIS calls you (default address: sir)
jarvis logs                      watch the brain
jarvis update                    get the newest version
```

## Privacy and safety

- **No accounts, no servers.** The app, the brain and the PC bridge run on your own devices. The only outside service is Google, through your own Antigravity sign-in. The listening part uses your phone's speech recognizer (the Google app on most phones).
- **The brain only listens on the phone itself** (`127.0.0.1`) and needs a token.
- **The PC bridge needs a secret token and only answers devices on your own network.** Never share the token or put it online. If it ever leaks, delete `%USERPROFILE%\.jarvis-phone\pc.json`, restart the bridge, and pair again.
- **Your name and pairing stay off GitHub.** They live only in the copy installed on your phone (`~/.jarvis`).
- **Voice mode can't ask you for permission.** So JARVIS on the phone only runs a short list of harmless Termux commands. Anything else is refused.

## Building the app yourself

Open `android/` in Android Studio, or run `./gradlew assembleDebug`. Releases are built and signed by GitHub Actions when a `v*` tag is pushed.

## License

MIT. The offline wake-word model is [Vosk](https://alphacephei.com/vosk/) (Apache 2.0) and downloads separately.

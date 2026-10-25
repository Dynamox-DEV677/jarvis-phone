#!/usr/bin/env node
/**
 * JARVIS laptop worker - lets JARVIS on your phone use this laptop from ANYWHERE (mobile data,
 * another Wi-Fi), through your own free Supabase project. No ports to open, nothing exposed.
 *
 *   node worker.mjs setup   once: your Supabase project's URL and public key, then pair the phone
 *   node worker.mjs pair    show the pairing QR code again
 *   node worker.mjs         run (START-JARVIS-LAPTOP.cmd does this)
 *
 * The phone puts a question in the jarvis_jobs table; Supabase Realtime pokes this worker at
 * once; JARVIS on this laptop (your Antigravity "jarvis" agent, on your Google AI Pro plan)
 * answers; the answer and any screenshots it took go back to the phone.
 *
 * The secret that links phone and laptop lives in %USERPROFILE%\.jarvis-phone\relay.json and in
 * the app. Anyone who has it can give this laptop tasks, so treat it like a password.
 */
import { execFile, spawn } from 'node:child_process'
import crypto from 'node:crypto'
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createInterface } from 'node:readline'
import readline from 'node:readline/promises'
import { fileURLToPath } from 'node:url'
import { Agy } from '../brain/agy.mjs'
import { captureScreen, previewOf } from './screen.mjs'

const VERSION = '1.5.0'
const HERE = path.dirname(fileURLToPath(import.meta.url))
const HOME = os.homedir()
const CONF_DIR = path.join(HOME, '.jarvis-phone')
const CONF_FILE = path.join(CONF_DIR, 'relay.json')
const HANDS_STOP = path.join(CONF_DIR, 'hands.stop') // Ctrl+Alt+J at the laptop: JARVIS's hands stop
const SCRATCH = path.join(HOME, '.gemini', 'antigravity', 'scratch')
const SHOT_DIRS = ['screenshots', 'browser', path.join('phone', 'screenshots')].map((d) => path.join(SCRATCH, 'JARVIS', d))
const MY_SHOTS = path.join(SCRATCH, 'JARVIS', 'phone', 'screenshots')
const MAX_IMAGES = 3

const stamp = () => new Date().toTimeString().slice(0, 8)
const LOG_FILE = path.join(CONF_DIR, 'worker.log')
const log = (...a) => {
  const line = `[${stamp()}] ${a.join(' ')}`
  console.log(line)
  try { appendFileSync(LOG_FILE, line + '\n') } catch {}
}

/* ── config ───────────────────────────────────────────────────────────────── */

function readConf() {
  if (!existsSync(CONF_FILE)) return null
  return JSON.parse(readFileSync(CONF_FILE, 'utf8'))
}
function writeConf(c) {
  mkdirSync(CONF_DIR, { recursive: true })
  writeFileSync(CONF_FILE, JSON.stringify(c, null, 2))
}

/* ── Supabase ─────────────────────────────────────────────────────────────── */

function headers(key) {
  // Older "anon" keys are JWTs and go in Authorization too; new "publishable" keys go in apikey only.
  const h = { apikey: key, 'Content-Type': 'application/json' }
  if (key.startsWith('eyJ')) h.Authorization = `Bearer ${key}`
  return h
}

async function rpc(conf, fn, args, timeoutMs = 20_000) {
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), timeoutMs)
  try {
    const r = await fetch(`${conf.url}/rest/v1/rpc/${fn}`, { method: 'POST', headers: headers(conf.key), body: JSON.stringify(args), signal: ctrl.signal })
    const text = await r.text()
    if (!r.ok) throw new Error(`${fn}: ${r.status} ${text.slice(0, 300)}`)
    return text ? JSON.parse(text) : null
  } finally { clearTimeout(t) }
}

const roomOf = (secret) => crypto.createHash('sha256').update(secret).digest('hex')

/* ── setup and pairing ────────────────────────────────────────────────────── */

async function setup() {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
  console.log('\nJARVIS laptop setup. You need your Supabase project with supabase\\relay.sql already run in its SQL Editor.\n')
  const url = (await rl.question('Project URL (Settings > API, like https://abcd.supabase.co): ')).trim().replace(/\/+$/, '')
  const key = (await rl.question('Public key (the "anon" or "publishable" key - NOT the secret/service_role one): ')).trim()
  rl.close()
  if (!/^https:\/\/[a-z0-9-]+\.supabase\.co$/.test(url)) throw new Error("That doesn't look like a Supabase project URL.")
  if (key.startsWith('sb_secret_') || /service_role/.test(Buffer.from(key.split('.')[1] || '', 'base64').toString())) {
    throw new Error('That is the SECRET key. Use the anon / publishable key - the secret one must never leave the dashboard.')
  }
  const old = readConf()
  const conf = { url, key, secret: old?.secret || crypto.randomBytes(32).toString('base64url'), agent: old?.agent || 'jarvis', model: old?.model || '' }
  // Prove the database is set up before saving.
  const s = await rpc(conf, 'jarvis_status', { secret: conf.secret }).catch((e) => {
    throw new Error(`Couldn't use the database: ${e.message}\nDid you run supabase\\relay.sql in the SQL Editor?`)
  })
  if (!Array.isArray(s)) throw new Error('Unexpected answer from Supabase.')
  writeConf(conf)
  console.log('\nSaved. The database answers correctly.')
  await pair(conf)
}

async function pair(conf) {
  const link = `jarvis://pair?u=${encodeURIComponent(conf.url)}&k=${encodeURIComponent(conf.key)}&s=${encodeURIComponent(conf.secret)}`
  const png = path.join(CONF_DIR, 'pair-qr.png')
  console.log('\nPair your phone: open the JARVIS app, tap "Scan QR code" and point the phone at this QR code.')
  console.log('(Or copy the pairing text below to the phone and tap "Paste link instead".)\n')
  // npx through node itself, never through cmd: cmd would split the link at its "&".
  const npxCli = path.join(path.dirname(process.execPath), 'node_modules', 'npm', 'bin', 'npx-cli.js')
  await new Promise((resolve) => {
    const p = execFile(process.execPath, [npxCli, '--yes', 'qrcode@1.5.4', '-o', png, '-w', '600', link], { windowsHide: true, timeout: 120_000 }, (err) => {
      if (err) console.log('(Could not make the QR picture - use the text below.)')
      else { console.log(`QR code: ${png}`); execFile('cmd', ['/c', 'start', '', png], { windowsHide: true }, () => {}) }
      resolve()
    })
    // The qrcode tool reads stdin whenever it isn't a terminal: close it, or it waits forever.
    p.stdin?.end()
  })
  console.log(`\nPairing text (a password - don't share it):\n${link}\n`)
  console.log('When the app says "Laptop online", delete the QR picture.')
}

/* ── jobs ─────────────────────────────────────────────────────────────────── */

// Measured on Darshan's laptop (2026-10-25, warm agy, one-line answers): the default model with slash
// commands reloading on every turn took 14-15 s; Gemini 3.8 Flash Medium with --disable-slash-commands
// took 9-11 s, and skills still load. relay.json "model" and "agyArgs" override these.
const FAST_MODEL = 'gemini-3.8-flash-medium'
const FAST_ARGS = ['--disable-slash-commands']

const REMOTE_NOTE ='This request came from my phone through JARVIS, maybe from outside home. Keep the answer short and plain, with no markdown: it will be read aloud on the phone. ' +
  'Any screenshot you take is sent to the phone automatically, so take one when it helps show what happened. ' +
  'Nobody is at the laptop to approve a permission prompt: to use apps on the laptop (Claude included), use your desktop_* and claude_desktop tools. ' +
  'In this run do not delete, uninstall, or change system settings; if the task needs that, stop and say what you would do instead.\n\nRequest: '

const LABELS = [
  [/^run_command$/, 'Running a command on the laptop…'],
  [/^(view_file|list_dir|find_by_name|grep_search)$/, 'Looking through files…'],
  [/^(write_to_file|replace_file_content|multi_replace_file_content)$/, 'Editing files…'],
  [/^search_web$/, 'Searching the web…'],
  [/^read_url_content$/, 'Reading a page…'],
  [/browser/, 'Using the browser on the laptop…'],
  [/screenshot/, 'Taking a screenshot…'],
  [/kyno/, 'Checking Kyno…'],
  [/^(open|pc_open)$/, 'Opening it on the laptop…'],
  [/^pc_status$/, 'Checking the laptop…'],
  [/^claude_desktop$/, 'Typing into Claude…'],
  [/^desktop_(windows|read)$/, 'Looking at the screen…'],
  [/^desktop_focus$/, 'Switching windows…'],
  [/^desktop_click$/, 'Clicking…'],
  [/^desktop_type$/, 'Typing…'],
  [/^desktop_keys$/, 'Pressing keys…'],
  [/^desktop_scroll$/, 'Scrolling…'],
]
const label = (name) => (LABELS.find(([re]) => re.test(name)) || [null, 'Working…'])[1]

function imagesSince(t0) {
  const found = []
  for (const dir of SHOT_DIRS) {
    if (!existsSync(dir)) continue
    for (const f of readdirSync(dir)) {
      if (!/\.(png|jpe?g)$/i.test(f) || f.endsWith('.preview.jpg')) continue
      const p = path.join(dir, f)
      const st = statSync(p)
      if (st.isFile() && st.mtimeMs >= t0) found.push({ p, t: st.mtimeMs })
    }
  }
  return found.sort((a, b) => a.t - b.t).slice(-MAX_IMAGES).map((x) => x.p)
}

async function encode(file) {
  const preview = file.endsWith('.preview.jpg') ? file : await previewOf(file)
  const data = readFileSync(preview)
  if (data.length > 900_000) return null
  return { mime: 'image/jpeg', name: path.basename(file), data: data.toString('base64') }
}

/** jarvis_update refuses outputs over 3 MB: drop the oldest screenshots, then the voice. */
function fit(out) {
  const size = () => JSON.stringify(out).length
  while (size() > 2_900_000 && out.images?.length) out.images.shift()
  if (size() > 2_900_000) delete out.voice
  return out
}

/* ── voice ────────────────────────────────────────────────────────────────── */

/** The same clean-up the phone does before speaking: no markdown, no raw links. */
const speakable = (s) => String(s)
  .replace(/```[\s\S]*?```/g, ' ')
  .replace(/\[([^\]]+)]\([^)]+\)/g, '$1')
  .replace(/https?:\/\/\S+/g, 'a link')
  .replace(/[*_#`>|~]/g, '')
  .replace(/^\s*[-•]\s+/gm, '')
  .replace(/\s+/g, ' ')
  .trim()

/**
 * JARVIS's voice for the phone. pc/tts.py turns each reply into speech with Microsoft's free
 * neural voices (British "Ryan" by default) and says when every word starts, so the glow on the
 * phone blinks in time. If it fails, the phone simply uses its own voice.
 */
class Voice {
  constructor(conf) {
    this.voice = conf.voice ?? 'en-GB-RyanNeural' // "" turns it off
    this.rate = conf.voiceRate || '+0%'
    this.pitch = conf.voicePitch || '+0Hz'
    this.python = conf.python || 'python'
    this.proc = null
    this.waiting = new Map()
    this.seq = 0
    this.cache = new Map()
    this.warned = false
    this.missing = false
  }

  start() {
    const p = spawn(this.python, [path.join(HERE, 'tts.py')], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
    this.proc = p
    let err = ''
    createInterface({ input: p.stdout }).on('line', (line) => {
      let j
      try { j = JSON.parse(line) } catch { return }
      const done = this.waiting.get(j.id)
      if (done) { this.waiting.delete(j.id); done(j) }
    })
    p.stderr.on('data', (d) => { err = (err + d).slice(-2000) })
    p.stdin.on('error', () => {})
    const gone = (why) => {
      if (this.proc === p) this.proc = null
      for (const done of this.waiting.values()) done({ error: why })
      this.waiting.clear()
    }
    p.on('error', (e) => { if (e.code === 'ENOENT') this.missing = true; gone(e.message) })
    p.on('exit', (code) => gone(err.trim().split('\n').pop() || `the voice helper stopped (${code})`))
  }

  /** { mime, data (base64 mp3), words (ms) } or null. */
  async speak(text) {
    const t = speakable(text)
    if (!this.voice || this.missing || !t) return null
    if (this.cache.has(t)) return this.cache.get(t)
    if (!this.proc) this.start()
    const id = ++this.seq
    const ans = await new Promise((resolve) => {
      this.waiting.set(id, resolve)
      setTimeout(() => { if (this.waiting.delete(id)) resolve({ error: 'timed out' }) }, 25_000)
      this.proc.stdin.write(JSON.stringify({ id, text: t, voice: this.voice, rate: this.rate, pitch: this.pitch }) + '\n')
    })
    if (ans.error) {
      if (!this.warned) log(`voice: ${ans.error}\n  The phone will use its own voice. To fix: python -m pip install --user --upgrade edge-tts`)
      this.warned = true
      return null
    }
    if (this.warned) log('voice: working again')
    this.warned = false
    const v = { mime: 'audio/mpeg', data: ans.audio, words: ans.words }
    if (t.length <= 80) {
      this.cache.set(t, v)
      if (this.cache.size > 30) this.cache.delete(this.cache.keys().next().value)
    }
    return v
  }

  stop() { try { this.proc?.kill() } catch {} }
}

/* ── JARVIS at the laptop ─────────────────────────────────────────────────── */

const DESK_NOTE = 'I am talking to you out loud at the laptop, through JARVIS on the desktop. Answer in one to three short spoken sentences, ' +
  'with no markdown, lists or links: your answer is read aloud. ' +
  'I can\'t click permission prompts while talking: to use apps on the laptop (Claude included), use your desktop_* and claude_desktop tools. ' +
  'In this run do not delete, uninstall, or change system settings; if the task needs that, stop and say what you would do instead.\n\nRequest: '

/**
 * "Hey Jarvis" on the laptop. pc/desktop.py listens for the wake word, shows the orange glow and
 * speaks; this side does the thinking, with the same JARVIS (and conversation) as the phone.
 */
class Desktop {
  constructor(conf, onAsk, onLiveStop) {
    this.enabled = conf.desktop !== false // "desktop": false in relay.json turns it off
    this.python = conf.python || 'python'
    this.voice = conf.voice || 'en-GB-RyanNeural'
    this.onAsk = onAsk
    this.onLiveStop = onLiveStop
    this.proc = null
    this.restarts = 0
  }

  start() {
    if (!this.enabled) return
    const p = spawn(this.python, [path.join(HERE, 'desktop.py'), '--voice', this.voice], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
    this.proc = p
    let err = ''
    p.stderr.on('data', (d) => { err = (err + d).slice(-3000) })
    p.stdin.on('error', () => {})
    createInterface({ input: p.stdout }).on('line', (line) => {
      let m
      try { m = JSON.parse(line) } catch { return }
      if (m.event === 'ready') { this.restarts = 0; log('desktop: say "Hey Jarvis" or press Ctrl+Alt+J') }
      else if (m.event === 'log') log('desktop:', m.text)
      else if (m.event === 'ask') { log(`desktop asks: ${String(m.text).slice(0, 120)}`); this.onAsk(m.id, m.text) }
      else if (m.event === 'live_stop') this.onLiveStop()
    })
    p.on('error', (e) => log(`desktop: can't start Python (${e.message}). Install Python from python.org for "Hey Jarvis" on the laptop.`))
    p.on('exit', (code) => {
      if (this.proc !== p) return
      this.proc = null
      const why = err.trim().split('\n').pop() || `exit ${code}`
      if (/ModuleNotFoundError|No module named/.test(why)) {
        log(`desktop: ${why}\n  Fix: python -m pip install --user vosk sounddevice faster-whisper pillow edge-tts`)
      } else if (this.restarts++ < 5) {
        log(`desktop stopped (${why}) - restarting`)
        setTimeout(() => this.start(), 5000 * this.restarts)
      } else log(`desktop stopped (${why}) - giving up; restart JARVIS to try again`)
    })
  }

  send(msg) { try { this.proc?.stdin.write(JSON.stringify(msg) + '\n') } catch {} }

  stop() { try { this.proc?.kill() } catch {} this.proc = null }
}

/* ── live control from the phone ──────────────────────────────────────────── */

// Live frames count against the Supabase free plan's 5 GB a month, which the relay needs too.
const LIVE_CAP = 3 * 1024 ** 3
const USAGE_FILE = path.join(CONF_DIR, 'live-usage.json')

/** The live channel's name and key, both made from the pairing secret (the phone does the same). */
function liveKeys(secret) {
  return {
    topic: 'realtime:jarvis-live-' + crypto.createHash('sha256').update('jarvis-live:' + secret).digest('hex').slice(0, 32),
    key: crypto.createHash('sha256').update('jarvis-live-key:' + secret).digest(),
  }
}

/** AES-256-GCM: base64(iv | ciphertext | tag). The direction is bound in, so a message can't be bounced back. */
function seal(key, direction, plain) {
  const iv = crypto.randomBytes(12)
  const c = crypto.createCipheriv('aes-256-gcm', key, iv)
  c.setAAD(Buffer.from(direction))
  const body = Buffer.concat([c.update(plain), c.final()])
  return Buffer.concat([iv, body, c.getAuthTag()]).toString('base64')
}

function unseal(key, direction, b64) {
  const b = Buffer.from(String(b64 || ''), 'base64')
  if (b.length < 29) throw new Error('too short')
  const d = crypto.createDecipheriv('aes-256-gcm', key, b.subarray(0, 12))
  d.setAAD(Buffer.from(direction))
  d.setAuthTag(b.subarray(b.length - 16))
  return Buffer.concat([d.update(b.subarray(12, b.length - 16)), d.final()])
}

/**
 * One live-control session at a time: the phone sees this laptop's screen and controls it.
 * pc/live.py captures the screen and replays input; this side encrypts and carries it over
 * Supabase Realtime, checks every message really came from the paired phone, and keeps the
 * month's data under LIVE_CAP.
 */
class Live {
  constructor(worker) {
    this.w = worker
    Object.assign(this, liveKeys(worker.conf.secret))
    this.proc = null
    this.sid = null
    this.lastN = 0
    this.lastSeen = 0
    this.n = 0
    this.timers = []
    this.ended = new Set()
    this.usage = { month: '', bytes: 0 }
    try { this.usage = JSON.parse(readFileSync(USAGE_FILE, 'utf8')) } catch {}
  }

  month() {
    const m = new Date().toISOString().slice(0, 7)
    if (this.usage.month !== m) this.usage = { month: m, bytes: 0 }
    return this.usage
  }

  saveUsage() { try { writeFileSync(USAGE_FILE, JSON.stringify(this.usage)) } catch {} }

  send(obj, binary) {
    const head = Buffer.from(JSON.stringify({ ...obj, n: ++this.n }) + '\n')
    const d = seal(this.key, 'to-phone', binary ? Buffer.concat([head, binary]) : head)
    if (!this.w.broadcast(this.topic, 'm', { d })) return 0
    this.month().bytes += d.length
    return d.length
  }

  state() {
    const u = this.month()
    this.send({ t: 'state', live: Boolean(this.proc), host: os.hostname(), used: u.bytes, cap: LIVE_CAP })
  }

  onMessage(d) {
    let m
    try { m = JSON.parse(unseal(this.key, 'to-laptop', d).toString('utf8')) } catch { return } // not from the paired phone
    if (typeof m.ts !== 'number' || Math.abs(Date.now() - m.ts) > 120_000) return // too old: maybe replayed
    if (this.ended.has(m.sid)) return // that session is over: its last messages may still be arriving
    if (m.sid !== this.sid) {
      if (m.t !== 'hello') return
      if (this.proc) this.stop('A new live session started.', false)
      this.sid = m.sid
      this.lastN = 0
    }
    if (!(m.n > this.lastN)) return // seen it already
    this.lastN = m.n
    this.lastSeen = Date.now()
    if (m.t === 'hello') {
      const cfg = JSON.stringify([m.maxW, m.q, m.fps])
      if (!this.proc) this.start(m)
      else if (cfg !== this.cfg) this.toLive({ t: 'cfg', maxW: m.maxW, q: m.q, fps: m.fps }) // only when the phone changed it
      this.cfg = cfg
    } else if (m.t === 'bye') {
      this.stop('Closed on the phone.')
    } else {
      this.toLive(m)
    }
  }

  toLive(m) { try { this.proc?.stdin.write(JSON.stringify(m) + '\n') } catch {} }

  start(m) {
    if (this.month().bytes > LIVE_CAP) {
      this.send({ t: 'end', why: `This month's live-control data is used up (${(LIVE_CAP / 1024 ** 3).toFixed(0)} GB, to keep Supabase free). It resets next month.` })
      return
    }
    try { unlinkSync(HANDS_STOP) } catch {}
    const python = this.w.conf.python || 'python'
    const p = spawn(python, [path.join(HERE, 'live.py')], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true })
    this.proc = p
    let err = ''
    p.stderr.on('data', (x) => { err = (err + x).slice(-2000) })
    p.stdin.on('error', () => {})
    this.toLive({ t: 'cfg', maxW: m.maxW || 1280, q: m.q || 50, fps: m.fps || 4 })
    createInterface({ input: p.stdout }).on('line', (line) => {
      let f
      try { f = JSON.parse(line) } catch { return }
      if (f.t === 'frame') this.frame(f)
      else if (f.t === 'stopped') this.stop(f.why)
    })
    p.on('exit', () => { if (this.proc === p) this.stop(err.trim().split('\n').pop() || 'The live screen stopped.') })
    log('live control: the phone is connected')
    this.w.desktop.send({ cmd: 'live', on: true })
    this.state()
    this.timers = [
      setInterval(() => { if (Date.now() - this.lastSeen > 12_000) this.stop('The phone went quiet.') }, 2000),
      setInterval(() => { this.state(); this.saveUsage() }, 10_000),
    ]
  }

  frame(f) {
    if (this.w.wsBacklog() > 2_000_000) return // the connection is behind: skip this one, the next will catch up
    const parts = f.tiles.map((t) => Buffer.from(t[4], 'base64'))
    const head = { t: 'frame', f: f.f, w: f.w, h: f.h, sw: f.sw, sh: f.sh, full: f.full, tiles: f.tiles.map((t, i) => [t[0], t[1], t[2], t[3], parts[i].length]) }
    this.send(head, Buffer.concat(parts))
    if (this.month().bytes > LIVE_CAP) this.stop(`This month's live-control data is used up (${(LIVE_CAP / 1024 ** 3).toFixed(0)} GB). It resets next month.`)
  }

  stop(why, tell = true) {
    if (!this.proc) return
    const p = this.proc
    this.proc = null
    try { p.stdin.write('{"t":"bye"}\n') } catch {}
    setTimeout(() => { try { p.kill() } catch {} }, 1500)
    for (const t of this.timers) clearInterval(t)
    this.timers = []
    if (tell) this.send({ t: 'end', why })
    this.saveUsage()
    this.ended.add(this.sid)
    this.sid = null
    this.w.desktop.send({ cmd: 'live', on: false })
    log(`live control ended: ${why} (${(this.month().bytes / 1024 ** 2).toFixed(0)} MB used this month)`)
  }
}

class Worker {
  constructor(conf) {
    this.conf = conf
    this.room = roomOf(conf.secret)
    this.voice = new Voice(conf)
    this.desktop = new Desktop(conf, (id, text) => this.deskAsk(id, text), () => this.live.stop('Stopped at the laptop (Ctrl+Alt+J).'))
    this.live = new Live(this)
    this.broadcast = () => false
    this.wsBacklog = () => 0
    this.lock = Promise.resolve() // one question at a time to agy, from the phone or the laptop
    this.agy = new Agy({
      bin: conf.agy || path.join(process.env.LOCALAPPDATA || path.join(HOME, 'AppData', 'Local'), 'agy', 'bin', 'agy.exe'),
      agent: conf.agent || 'jarvis', model: conf.model || FAST_MODEL, extraArgs: conf.agyArgs || FAST_ARGS, cwd: SCRATCH, log,
    })
    this.draining = false
    this.again = false
    this.joined = false
    this.ws = null
    this.wsRef = 0
  }

  update(id, status, progress, output) {
    return rpc(this.conf, 'jarvis_update', { secret: this.conf.secret, job: id, new_status: status, new_progress: progress, new_output: output ?? null })
      .catch((e) => log('update failed:', e.message))
  }

  async run(job) {
    log(`job ${job.kind}: ${String(job.input).slice(0, 120)}`)
    try {
      if (job.kind === 'screenshot') {
        await this.update(job.id, 'working', 'Taking a screenshot…')
        const text = "Here's your laptop screen."
        const [img, voice] = await Promise.all([captureScreen(MY_SHOTS).then((shot) => encode(shot.preview)), this.voice.speak(text)])
        await this.update(job.id, 'done', null, fit({ text, images: img ? [img] : [], voice }))
      } else if (job.kind === 'reset') {
        await this.exclusive(() => this.agy.stop())
        await this.update(job.id, 'done', null, { text: 'Starting a new conversation.' })
      } else {
        const t0 = Date.now()
        await this.update(job.id, 'working', 'Thinking…')
        const reply = await this.think(REMOTE_NOTE + job.input, (name) => this.update(job.id, 'working', label(name)))
        const text = reply || 'Done.'
        const [images, voice] = await Promise.all([
          Promise.all(imagesSince(t0 - 1000).map((f) => encode(f).catch(() => null))).then((a) => a.filter(Boolean)),
          this.voice.speak(text),
        ])
        log(`  done${images.length ? ` (+${images.length} screenshot${images.length > 1 ? 's' : ''})` : ''}${voice ? '' : ' (no voice)'}: ${reply.slice(0, 120)}`)
        await this.update(job.id, 'done', null, fit({ text, images, voice }))
      }
    } catch (e) {
      log('  failed:', e.message)
      await this.update(job.id, 'error', e.message)
    }
  }

  /** Runs fn when agy is free: the phone and the laptop take turns with the one JARVIS. */
  exclusive(fn) {
    const run = this.lock.then(fn)
    this.lock = run.catch(() => {})
    return run
  }

  /** Asks JARVIS (agy), starting it if needed or if it's been quiet for 30 minutes (a fresh conversation). */
  think(prompt, onTool) {
    return this.exclusive(() => {
      try { unlinkSync(HANDS_STOP) } catch {} // a new request: JARVIS may use its hands again (pc/hands.py)
      const idle = this.agy.lastUsed && Date.now() - this.agy.lastUsed > 30 * 60_000
      if (!this.agy.running || idle) this.agy.start()
      const notes = this.agy.primed ? '' : this.context()
      this.agy.primed = true
      return this.agy.ask(notes + prompt, (ev) => { log('  tool', ev.name); onTool(ev.name) })
    })
  }

  /** relay.json "contextFile": notes JARVIS gets at the start of each conversation (e.g. a digest of
   *  his own files), so everyday questions need no file reads - each read is another model step, 4-5 s. */
  context() {
    if (!this.conf.contextFile) return ''
    try {
      const text = readFileSync(this.conf.contextFile, 'utf8').slice(0, 6000).trim()
      return text ? `Background notes about me, for reference - answer from them when they are enough, instead of opening files:\n${text}\n\n---\n` : ''
    } catch {
      return ''
    }
  }

  /** "Hey Jarvis" at the laptop: think, and send the answer back to be spoken. */
  async deskAsk(id, text) {
    let reply
    try {
      reply = (await this.think(DESK_NOTE + text, (name) => this.desktop.send({ cmd: 'status', text: label(name) }))) || 'Done.'
      log(`  desktop answer: ${reply.slice(0, 120)}`)
    } catch (e) {
      log('  desktop failed:', e.message)
      reply = "Sorry, I couldn't do that just now."
    }
    this.desktop.send({ cmd: 'reply', id, text: reply })
  }

  /** Take jobs until none are waiting. A poke while busy makes it look again afterwards. */
  async drain() {
    if (this.draining) { this.again = true; return }
    this.draining = true
    try {
      do {
        this.again = false
        for (;;) {
          const rows = await rpc(this.conf, 'jarvis_claim', { secret: this.conf.secret }).catch((e) => { log('claim failed:', e.message); return [] })
          if (!rows || !rows.length) break
          await this.run(rows[0])
        }
      } while (this.again)
    } finally { this.draining = false }
  }

  async beat() {
    await rpc(this.conf, 'jarvis_beat', { secret: this.conf.secret, p_host: os.hostname() }).catch((e) => log('check-in failed:', e.message))
  }

  /**
   * Supabase Realtime (Phoenix channels over WebSocket), two channels on one connection: the
   * database pokes us when a job arrives, and the phone's live-control session talks to us.
   */
  connect() {
    const ws = new WebSocket(`${this.conf.url.replace(/^http/, 'ws')}/realtime/v1/websocket?apikey=${encodeURIComponent(this.conf.key)}&vsn=1.0.0`)
    this.ws = ws
    const topic = `realtime:jarvis-${this.room}`
    const joins = { [topic]: '1', [this.live.topic]: '2' }
    const send = (event, payload, t = topic) => ws.send(JSON.stringify({ topic: t, event, payload, ref: String(++this.wsRef), join_ref: joins[t] }))
    this.broadcast = (t, event, payload) => {
      if (ws.readyState !== 1 || this.ws !== ws) return false
      send('broadcast', { type: 'broadcast', event, payload }, t)
      return true
    }
    this.wsBacklog = () => ws.bufferedAmount || 0
    let hb
    ws.addEventListener('open', () => {
      for (const t of Object.keys(joins)) {
        send('phx_join', {
          config: { broadcast: { self: false, ack: false }, presence: { key: '' }, postgres_changes: [], private: false },
          ...(this.conf.key.startsWith('eyJ') ? { access_token: this.conf.key } : {}),
        }, t)
      }
      hb = setInterval(() => { try { send('heartbeat', {}, 'phoenix') } catch {} }, 25_000)
    })
    ws.addEventListener('message', (m) => {
      let msg
      try { msg = JSON.parse(String(m.data)) } catch { return }
      if (msg.event === 'phx_reply' && msg.topic === topic) {
        const ok = msg.payload?.status === 'ok'
        if (ok && !this.joined) log('realtime connected - jobs arrive instantly')
        if (!ok) log('realtime join refused:', JSON.stringify(msg.payload).slice(0, 200))
        this.joined = ok
      } else if (msg.event === 'phx_reply' && msg.topic === this.live.topic && msg.payload?.status !== 'ok') {
        log('live-control channel refused:', JSON.stringify(msg.payload).slice(0, 200))
      } else if (msg.event === 'broadcast' && msg.topic === topic) {
        this.drain()
      } else if (msg.event === 'broadcast' && msg.topic === this.live.topic && msg.payload?.event === 'm') {
        this.live.onMessage(msg.payload.payload?.d)
      }
    })
    const retry = () => {
      clearInterval(hb)
      if (this.ws !== ws) return
      if (this.joined) log('realtime disconnected - checking every few seconds until it is back')
      this.joined = false
      this.ws = null
      setTimeout(() => this.connect(), 5000)
    }
    ws.addEventListener('close', retry)
    ws.addEventListener('error', () => { try { ws.close() } catch {} })
  }

  async start() {
    try { if (statSync(LOG_FILE).size > 1_000_000) writeFileSync(LOG_FILE, '') } catch {}
    await this.beat()
    log(`JARVIS laptop worker ${VERSION} is running on ${os.hostname()}. Keep this window open.`)
    this.agy.start() // warm up, so the first question doesn't wait for agy to start
    this.voice.speak('Online, sir.').catch(() => {}) // and the voice: its first use costs ~9 s (imports, TLS)
    this.desktop.start()
    if (typeof WebSocket === 'function') this.connect()
    setInterval(() => this.beat(), 20_000)
    // Safety net: look for jobs even without Realtime (often when it's down, rarely when it's up).
    const poll = () => { this.drain(); setTimeout(poll, this.joined ? 30_000 : 3_000) }
    poll()
  }
}

/* ── main ─────────────────────────────────────────────────────────────────── */

const cmd = process.argv[2] || 'run'
try {
  if (cmd === 'setup') await setup()
  else if (cmd === 'pair') { const c = readConf(); if (!c) throw new Error('Run setup first.'); await pair(c) }
  else if (cmd === 'run') {
    const c = readConf()
    if (!c) { console.log('Not set up yet. Run SETUP-JARVIS-LAPTOP.cmd first.'); process.exit(1) }
    const w = new Worker(c)
    process.on('SIGINT', () => { w.agy.stop(); w.voice.stop(); w.desktop.stop(); process.exit(0) })
    await w.start()
  } else { console.log('Usage: node worker.mjs [setup|pair]'); process.exit(1) }
} catch (e) {
  console.error(`\n${e.message}\n`)
  process.exit(1)
}

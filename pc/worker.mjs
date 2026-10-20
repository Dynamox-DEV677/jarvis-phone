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
import { execFile } from 'node:child_process'
import crypto from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import readline from 'node:readline/promises'
import { Agy } from '../brain/agy.mjs'
import { captureScreen, previewOf } from './screen.mjs'

const VERSION = '1.1.0'
const HOME = os.homedir()
const CONF_DIR = path.join(HOME, '.jarvis-phone')
const CONF_FILE = path.join(CONF_DIR, 'relay.json')
const SCRATCH = path.join(HOME, '.gemini', 'antigravity', 'scratch')
const SHOT_DIRS = ['screenshots', 'browser', path.join('phone', 'screenshots')].map((d) => path.join(SCRATCH, 'JARVIS', d))
const MY_SHOTS = path.join(SCRATCH, 'JARVIS', 'phone', 'screenshots')
const MAX_IMAGES = 3

const stamp = () => new Date().toTimeString().slice(0, 8)
const log = (...a) => console.log(`[${stamp()}]`, ...a)

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

const REMOTE_NOTE = 'This request came from my phone through JARVIS, maybe from outside home. Keep the answer short and plain, with no markdown: it will be read aloud on the phone. ' +
  'Any screenshot you take is sent to the phone automatically, so take one when it helps show what happened. ' +
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

class Worker {
  constructor(conf) {
    this.conf = conf
    this.room = roomOf(conf.secret)
    this.agy = new Agy({ bin: conf.agy || path.join(process.env.LOCALAPPDATA || path.join(HOME, 'AppData', 'Local'), 'agy', 'bin', 'agy.exe'), agent: conf.agent || 'jarvis', model: conf.model || undefined, cwd: SCRATCH, log })
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
        const shot = await captureScreen(MY_SHOTS)
        const img = await encode(shot.preview)
        await this.update(job.id, 'done', null, { text: "Here's your laptop screen.", images: img ? [img] : [] })
      } else if (job.kind === 'reset') {
        this.agy.stop()
        await this.update(job.id, 'done', null, { text: 'Starting a new conversation.' })
      } else {
        const idle = this.agy.lastUsed && Date.now() - this.agy.lastUsed > 30 * 60_000
        if (!this.agy.running || idle) this.agy.start()
        const t0 = Date.now()
        await this.update(job.id, 'working', 'Thinking…')
        const reply = await this.agy.ask(REMOTE_NOTE + job.input, (ev) => {
          log('  tool', ev.name)
          this.update(job.id, 'working', label(ev.name))
        })
        const images = (await Promise.all(imagesSince(t0 - 1000).map((f) => encode(f).catch(() => null)))).filter(Boolean)
        log(`  done${images.length ? ` (+${images.length} screenshot${images.length > 1 ? 's' : ''})` : ''}: ${reply.slice(0, 120)}`)
        await this.update(job.id, 'done', null, { text: reply || 'Done.', images })
      }
    } catch (e) {
      log('  failed:', e.message)
      await this.update(job.id, 'error', e.message)
    }
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

  /* Supabase Realtime (Phoenix channels over WebSocket): the database pokes us when a job arrives. */
  connect() {
    const ws = new WebSocket(`${this.conf.url.replace(/^http/, 'ws')}/realtime/v1/websocket?apikey=${encodeURIComponent(this.conf.key)}&vsn=1.0.0`)
    this.ws = ws
    const topic = `realtime:jarvis-${this.room}`
    const send = (event, payload, t = topic) => ws.send(JSON.stringify({ topic: t, event, payload, ref: String(++this.wsRef), join_ref: t === topic ? '1' : undefined }))
    let hb
    ws.addEventListener('open', () => {
      send('phx_join', {
        config: { broadcast: { self: false, ack: false }, presence: { key: '' }, postgres_changes: [], private: false },
        ...(this.conf.key.startsWith('eyJ') ? { access_token: this.conf.key } : {}),
      })
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
      } else if (msg.event === 'broadcast' && msg.topic === topic) {
        this.drain()
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
    await this.beat()
    log(`JARVIS laptop worker ${VERSION} is running on ${os.hostname()}. Keep this window open.`)
    this.agy.start() // warm up, so the first question doesn't wait for agy to start
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
    process.on('SIGINT', () => { w.agy.stop(); process.exit(0) })
    await w.start()
  } else { console.log('Usage: node worker.mjs [setup|pair]'); process.exit(1) }
} catch (e) {
  console.error(`\n${e.message}\n`)
  process.exit(1)
}

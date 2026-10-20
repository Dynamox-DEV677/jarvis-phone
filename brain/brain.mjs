#!/usr/bin/env node
/**
 * JARVIS brain - runs in Termux on the phone. No dependencies.
 *
 * It keeps ONE Antigravity CLI (agy) process running with the "jarvis-phone" agent, signed in
 * with your own Google account (your Google AI Pro plan - no API keys). Each question goes in as
 * one line of stream-json, so there is no start-up wait per question and the conversation
 * carries on.
 *
 * The JARVIS app on the phone talks to it at http://127.0.0.1:8765 with a token (see
 * `jarvis token`). Nothing outside the phone can reach it: it only listens on 127.0.0.1.
 *
 * If you paired a PC (`jarvis pair`), it checks before each question whether the PC is on, and
 * switches the PC tools on or off to match, so JARVIS never pretends the PC is there.
 */
import crypto from 'node:crypto'
import dgram from 'node:dgram'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { Agy } from './agy.mjs'

const VERSION = '1.0.0'
const HOME = os.homedir()
const CONF_DIR = process.env.JARVIS_CONFIG_DIR || path.join(HOME, '.jarvis')
const CONF_FILE = path.join(CONF_DIR, 'config.json')
const PLUGIN_DIR = path.join(HOME, '.gemini', 'config', 'plugins', 'jarvis-phone')
const PC_TOOLS = ['pc_status', 'pc_open', 'pc_screenshot', 'pc_lock', 'pc_media', 'pc_jarvis', 'claude_code']

const stamp = () => new Date().toTimeString().slice(0, 8)
const log = (...a) => console.log(`[${stamp()}]`, ...a)

function loadConfig() {
  mkdirSync(CONF_DIR, { recursive: true })
  let c = {}
  if (existsSync(CONF_FILE)) c = JSON.parse(readFileSync(CONF_FILE, 'utf8'))
  const merged = {
    token: crypto.randomBytes(18).toString('base64url'),
    port: 8765,
    agy: 'agy',
    agent: 'jarvis-phone',
    model: 'gemini-3.8-flash-low',
    workspace: path.join(HOME, 'jarvis-workspace'),
    idleResetMinutes: 30,
    pc: null, // { host, port, token } after `jarvis pair`
    ...c,
  }
  if (JSON.stringify(merged) !== JSON.stringify(c)) saveConfig(merged)
  return merged
}
const saveConfig = (c) => writeFileSync(CONF_FILE, JSON.stringify(c, null, 2), { mode: 0o600 })

let CONF = loadConfig()
mkdirSync(CONF.workspace, { recursive: true })

/* ── the PC ───────────────────────────────────────────────────────────────── */

async function pcOnline() {
  if (!CONF.pc) return false
  const ctrl = new AbortController()
  const t = setTimeout(() => ctrl.abort(), 1500)
  try {
    const r = await fetch(`http://${CONF.pc.host}:${CONF.pc.port}/health/${CONF.pc.token}`, { signal: ctrl.signal })
    return r.ok
  } catch { return false } finally { clearTimeout(t) }
}

// The router may give the PC a new address. Ask the network; the PC answers with its port.
let lastDiscovery = 0
function discoverPc() {
  if (!CONF.pc || Date.now() - lastDiscovery < 60_000) return Promise.resolve(null)
  lastDiscovery = Date.now()
  return new Promise((resolve) => {
    const s = dgram.createSocket('udp4')
    const done = (v) => { try { s.close() } catch {} resolve(v) }
    const t = setTimeout(() => done(null), 1500)
    s.on('message', (m, rinfo) => {
      const port = Number(String(m).match(/^JARVIS-PC (\d+)/)?.[1])
      if (port) { clearTimeout(t); done({ host: rinfo.address, port }) }
    })
    s.on('error', () => { clearTimeout(t); done(null) })
    s.bind(() => { s.setBroadcast(true); s.send('JARVIS-PC?', 8767, '255.255.255.255') })
  })
}

async function checkPc() {
  if (!CONF.pc) return false
  if (await pcOnline()) return true
  const found = await discoverPc()
  if (found && (found.host !== CONF.pc.host || found.port !== CONF.pc.port)) {
    CONF.pc = { ...CONF.pc, ...found }
    saveConfig(CONF)
    log(`PC moved to ${found.host}:${found.port}`)
    return pcOnline()
  }
  return false
}

// agy reads the plugin's MCP servers when it starts, so the file must match reality first.
function writeMcpConfig(withPc) {
  if (!existsSync(PLUGIN_DIR)) return
  // "eager" puts each PC tool straight into the model's tool list, which saves a lookup step
  // (a few seconds) every time JARVIS uses the PC.
  const eager = Object.fromEntries(PC_TOOLS.map((t) => [t, { eager: true }]))
  const servers = withPc
    ? { pc: { url: `http://${CONF.pc.host}:${CONF.pc.port}/mcp/${CONF.pc.token}`, timeoutSeconds: 1200, tools: eager } }
    : {}
  writeFileSync(path.join(PLUGIN_DIR, 'mcp_config.json'), JSON.stringify({ mcpServers: servers }, null, 2))
}

/* ── agy ──────────────────────────────────────────────────────────────────── */

const agy = new Agy({ bin: CONF.agy, agent: CONF.agent, model: CONF.model, extraArgs: CONF.agyArgs, cwd: CONF.workspace, log })
let agyWithPc = false

// agy reads the plugin's MCP servers when it starts, so write them first.
function startAgy(withPc) {
  writeMcpConfig(withPc)
  agyWithPc = withPc
  log(`PC tools ${withPc ? 'on' : 'off'}`)
  agy.start()
}

let queue = Promise.resolve()

function ask(text, onEvent) {
  const job = queue.then(async () => {
    const withPc = await checkPc()
    const idle = agy.lastUsed && Date.now() - agy.lastUsed > CONF.idleResetMinutes * 60_000
    if (!agy.running || withPc !== agyWithPc || idle) startAgy(withPc)
    let note = ''
    if (CONF.pc && !withPc) note = '[Note from the app: the PC is off or not on this Wi-Fi right now, so the pc tools are not available.]\n'
    return agy.ask(note + text, onEvent)
  })
  queue = job.catch(() => {})
  return job
}

/* ── HTTP for the app ─────────────────────────────────────────────────────── */

const tokenOk = (t) => { const a = Buffer.from(String(t || '')), b = Buffer.from(CONF.token); return a.length === b.length && crypto.timingSafeEqual(a, b) }

function readBody(req) {
  return new Promise((resolve, reject) => {
    let s = ''
    req.setEncoding('utf8')
    req.on('data', (d) => { s += d; if (s.length > 64 << 10) req.destroy() })
    req.on('end', () => { try { resolve(s ? JSON.parse(s) : {}) } catch { reject(new Error('Bad JSON')) } })
    req.on('error', reject)
  })
}

const json = (res, status, body) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(body)) }

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x')
  if (!tokenOk(req.headers['x-jarvis-token'])) return json(res, 401, { error: 'Wrong or missing token. In Termux run: jarvis token' })
  try {
    if (req.method === 'GET' && url.pathname === '/health') {
      return json(res, 200, { ok: true, version: VERSION, agy: agy.running, busy: agy.busy, model: CONF.model, pc: CONF.pc ? ((await pcOnline()) ? 'online' : 'offline') : 'not paired' })
    }
    if (req.method === 'POST' && url.pathname === '/reset') {
      agy.stop()
      log('new conversation')
      return json(res, 200, { ok: true })
    }
    if (req.method === 'POST' && url.pathname === '/ask') {
      const { text } = await readBody(req)
      const q = String(text || '').trim()
      if (!q) return json(res, 400, { error: 'No text' })
      log('>', q.slice(0, 160))
      // Newline-delimited JSON, so the app can show what JARVIS is doing while it works.
      res.writeHead(200, { 'Content-Type': 'application/x-ndjson', 'Cache-Control': 'no-store' })
      const out = (o) => { try { res.write(JSON.stringify(o) + '\n') } catch {} }
      out({ type: 'thinking' })
      try {
        const reply = await ask(q, (ev) => { log('  tool', ev.name); out(ev) })
        log('<', reply.slice(0, 160))
        out({ type: 'reply', text: reply })
      } catch (e) {
        log('error:', e.message)
        out({ type: 'error', message: e.message })
      }
      return res.end()
    }
    json(res, 404, { error: 'Not found' })
  } catch (e) {
    json(res, 500, { error: e.message })
  }
})

server.listen(CONF.port, process.env.JARVIS_BRAIN_HOST || '127.0.0.1', () => {
  log(`JARVIS brain ${VERSION} on http://127.0.0.1:${CONF.port} (agent ${CONF.agent})`)
  // Warm up now so the first question doesn't wait for agy to start.
  checkPc().then((withPc) => { if (!agy.running) startAgy(withPc) })
})

const shutdown = () => { agy.stop(); process.exit(0) }
process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)

package dev.kairo.jarvis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Live control of the laptop through your Supabase project (pc/worker.mjs and pc/live.py are the
 * other end). Everything is end-to-end encrypted with a key made from the pairing secret, so even
 * someone who found the channel couldn't watch the screen or send a click.
 *
 * The laptop sends only the parts of the screen that changed; this side puts them together.
 */
class LiveClient(private val prefs: Prefs, private val listener: Listener) {
    interface Listener {
        fun onStatus(text: String)
        fun onFrame(w: Int, h: Int, full: Boolean, tiles: List<Tile>)
        fun onState(host: String, used: Long, cap: Long)
        fun onEnd(why: String)
    }

    class Tile(val x: Int, val y: Int, val bmp: Bitmap)

    data class Quality(val maxW: Int, val q: Int, val fps: Int)

    companion object {
        val QUALITIES = mapOf(
            "saver" to Quality(960, 40, 3),
            "balanced" to Quality(1280, 50, 4),
            "sharp" to Quality(1600, 62, 5),
        )

        private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    }

    private val secret = prefs.relaySecret
    private val topic = "realtime:jarvis-live-" + hex(sha256("jarvis-live:$secret")).take(32)
    private val key = SecretKeySpec(sha256("jarvis-live-key:$secret"), "AES")
    private val random = SecureRandom()
    private val sid = hex(ByteArray(8).also { random.nextBytes(it) })
    private val n = AtomicLong(0)
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var ws: WebSocket? = null
    private var ref = 0
    private var joinRef = ""
    private var joinedAt = 0L
    @Volatile private var closed = false
    @Volatile private var live = false
    private var lastHeard = 0L
    /** Data received this session, to show on screen. */
    @Volatile var bytes = 0L
        private set
    @Volatile var frames = 0
        private set

    var quality: Quality = QUALITIES[prefs.liveQuality] ?: QUALITIES.getValue("balanced")

    private val hello = object : Runnable {
        override fun run() {
            if (closed) return
            send(JSONObject().put("t", "hello").put("maxW", quality.maxW).put("q", quality.q).put("fps", quality.fps))
            if (!live && lastHeard == 0L && System.currentTimeMillis() - joinedAt > 6000) status("Waiting for your laptop… Is JARVIS running on it?")
            main.postDelayed(this, 2000)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            push("heartbeat", JSONObject(), "phoenix")
            main.postDelayed(this, 25_000)
        }
    }

    fun start() {
        closed = false
        status("Connecting…")
        val url = prefs.relayUrl.replaceFirst("https://", "wss://") + "/realtime/v1/websocket?apikey=" +
            URLEncoder.encode(prefs.relayKey, "UTF-8") + "&vsn=1.0.0"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { join() }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed || webSocket !== ws) return
                status("Connection lost. Reconnecting…")
                main.postDelayed({ if (!closed) { stopTimers(); start() } }, 3000)
            }
        })
    }

    fun stop() {
        if (closed) return
        send(JSONObject().put("t", "bye"))
        closed = true
        stopTimers()
        val w = ws
        main.postDelayed({ w?.close(1000, null) }, 400)
    }

    private fun stopTimers() {
        main.removeCallbacks(hello)
        main.removeCallbacks(heartbeat)
    }

    /* ── what the phone sends: taps, keys, typing ───────────────────────────── */

    fun tap(x: Float, y: Float) = send(JSONObject().put("t", "tap").put("x", x).put("y", y))
    fun doubleTap(x: Float, y: Float) = send(JSONObject().put("t", "dbl").put("x", x).put("y", y))
    fun rightClick(x: Float, y: Float) = send(JSONObject().put("t", "right").put("x", x).put("y", y))
    fun mouse(kind: String, x: Float, y: Float) = send(JSONObject().put("t", kind).put("x", x).put("y", y))
    fun scroll(dy: Int, x: Float, y: Float) = send(JSONObject().put("t", "scroll").put("dy", dy).put("x", x).put("y", y))
    fun text(s: String) = send(JSONObject().put("t", "text").put("s", s))
    fun keys(k: String) = send(JSONObject().put("t", "key").put("k", k))
    fun requestFull() = send(JSONObject().put("t", "full"))

    fun setQuality(name: String) {
        prefs.liveQuality = name
        quality = QUALITIES[name] ?: return
        main.removeCallbacks(hello)
        hello.run()
    }

    private fun send(m: JSONObject) {
        if (closed) return
        m.put("sid", sid).put("n", n.incrementAndGet()).put("ts", System.currentTimeMillis())
        val d = seal(m.toString().toByteArray())
        push("broadcast", JSONObject().put("type", "broadcast").put("event", "m").put("payload", JSONObject().put("d", d)))
    }

    /* ── Supabase Realtime (Phoenix channels) ───────────────────────────────── */

    @Synchronized private fun push(event: String, payload: JSONObject, t: String = topic) {
        val r = (++ref).toString()
        if (event == "phx_join") joinRef = r
        ws?.send(JSONObject().put("topic", t).put("event", event).put("payload", payload).put("ref", r).put("join_ref", joinRef).toString())
    }

    private fun join() {
        val config = JSONObject()
            .put("broadcast", JSONObject().put("self", false).put("ack", false))
            .put("presence", JSONObject().put("key", ""))
            .put("postgres_changes", JSONArray())
            .put("private", false)
        val payload = JSONObject().put("config", config)
        if (prefs.relayKey.startsWith("eyJ")) payload.put("access_token", prefs.relayKey)
        push("phx_join", payload)
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (msg.optString("topic") != topic) return
        when (msg.optString("event")) {
            "phx_reply" -> {
                if (msg.optString("ref") != joinRef) return
                if (msg.optJSONObject("payload")?.optString("status") == "ok") {
                    if (!live) status("Connecting to your laptop…")
                    joinedAt = System.currentTimeMillis()
                    main.removeCallbacks(hello); main.removeCallbacks(heartbeat)
                    main.post(hello); main.postDelayed(heartbeat, 25_000)
                } else {
                    status("Supabase refused the live channel: ${msg.optJSONObject("payload")?.toString()?.take(120)}")
                }
            }
            "broadcast" -> {
                val p = msg.optJSONObject("payload") ?: return
                if (p.optString("event") != "m") return
                val d = p.optJSONObject("payload")?.optString("d") ?: return
                work.execute { receive(d) }
            }
        }
    }

    /** Runs off the main thread: decrypt, unpack, decode the pictures. */
    private fun receive(d: String) {
        val plain = runCatching { open(d) }.getOrNull() ?: return // not from our laptop
        bytes += d.length
        lastHeard = System.currentTimeMillis()
        val nl = plain.indexOf('\n'.code.toByte())
        if (nl < 0) return
        val head = runCatching { JSONObject(String(plain, 0, nl, Charsets.UTF_8)) }.getOrNull() ?: return
        when (head.optString("t")) {
            "frame" -> {
                val arr = head.optJSONArray("tiles") ?: return
                var off = nl + 1
                val tiles = ArrayList<Tile>(arr.length())
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONArray(i)
                    val len = t.getInt(4)
                    if (off + len > plain.size) break
                    BitmapFactory.decodeByteArray(plain, off, len)?.let { tiles += Tile(t.getInt(0), t.getInt(1), it) }
                    off += len
                }
                frames++
                send(JSONObject().put("t", "ack").put("f", head.optInt("f")))
                if (!live) { live = true; post { listener.onStatus("") } }
                val w = head.optInt("w"); val h = head.optInt("h"); val full = head.optBoolean("full")
                post { listener.onFrame(w, h, full, tiles) }
            }
            "state" -> {
                val host = head.optString("host"); val used = head.optLong("used"); val cap = head.optLong("cap")
                if (!live) status("Connected to $host. Starting the live screen…")
                post { listener.onState(host, used, cap) }
            }
            "end" -> {
                closed = true
                stopTimers()
                val why = head.optString("why", "The live session ended.")
                post { listener.onEnd(why) }
                main.postDelayed({ ws?.close(1000, null) }, 300)
            }
        }
    }

    /* ── AES-256-GCM, the same as pc/worker.mjs: base64(iv | ciphertext | tag) ── */

    private fun seal(plain: ByteArray): String {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD("to-laptop".toByteArray())
        return Base64.encodeToString(iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    private fun open(b64: String): ByteArray {
        val b = Base64.decode(b64, Base64.DEFAULT)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12))
        c.updateAAD("to-phone".toByteArray())
        return c.doFinal(b, 12, b.size - 12)
    }

    private fun status(s: String) = post { listener.onStatus(s) }

    private fun post(r: () -> Unit) { main.post(r) }
}

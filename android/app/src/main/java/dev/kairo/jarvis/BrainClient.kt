package dev.kairo.jarvis

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors

/**
 * Talks to JARVIS's brain in Termux (brain/brain.mjs) at http://127.0.0.1:8765.
 * Answers arrive as newline-delimited JSON, so the screen can show what JARVIS is doing.
 * Every callback runs on the main thread.
 */
class BrainClient(private val prefs: Prefs) {
    interface Listener {
        fun onTool(name: String)
        fun onReply(text: String)
        fun onError(message: String, brainDown: Boolean)
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: HttpURLConnection? = null

    private fun open(path: String, method: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL("http://${prefs.brainAddress}$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 3000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("x-jarvis-token", prefs.token)
        c.setRequestProperty("content-type", "application/json")
        return c
    }

    fun ask(text: String, listener: Listener) {
        io.execute {
            var c: HttpURLConnection? = null
            try {
                c = open("/ask", "POST", 25 * 60_000)
                current = c
                c.doOutput = true
                c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
                if (c.responseCode == 401) {
                    post { listener.onError("The token in the app doesn't match the brain. In Termux run jarvis token, then paste it in the JARVIS app.", false) }
                    return@execute
                }
                if (c.responseCode != 200) {
                    post { listener.onError("The brain answered with error ${c.responseCode}.", false) }
                    return@execute
                }
                var done = false
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.isBlank()) continue
                        val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        when (ev.optString("type")) {
                            "tool" -> ev.optString("name").let { n -> post { listener.onTool(n) } }
                            "reply" -> { done = true; ev.optString("text").let { t -> post { listener.onReply(t) } } }
                            "error" -> { done = true; ev.optString("message").let { m -> post { listener.onError(m, false) } } }
                        }
                    }
                }
                if (!done && current === c) post { listener.onError("The brain stopped answering.", false) }
            } catch (e: ConnectException) {
                post { listener.onError("brain is not running", true) }
            } catch (e: SocketTimeoutException) {
                val connecting = e.message?.contains("connect", ignoreCase = true) == true
                post { listener.onError(if (connecting) "brain is not running" else "That took too long.", connecting) }
            } catch (e: Exception) {
                if (current === c) post { listener.onError(e.message ?: "Couldn't reach the brain.", e.message?.contains("ECONNREFUSED") == true) }
            } finally {
                if (current === c) current = null
                c?.disconnect()
            }
        }
    }

    /** Stop waiting for the current answer (the brain finishes it in the background). */
    fun cancel() {
        val c = current
        current = null
        if (c != null) io.execute { runCatching { c.disconnect() } }
    }

    fun reset(done: (Boolean) -> Unit = {}) {
        io.execute {
            val ok = runCatching { open("/reset", "POST", 5000).run { doOutput = true; outputStream.close(); val r = responseCode; disconnect(); r == 200 } }.getOrDefault(false)
            post { done(ok) }
        }
    }

    /** One line for the setup screen, e.g. "Connected · PC online". */
    fun health(done: (ok: Boolean, text: String) -> Unit) {
        io.execute {
            val result = try {
                val c = open("/health", "GET", 5000)
                when (c.responseCode) {
                    200 -> {
                        val j = JSONObject(c.inputStream.bufferedReader().readText())
                        val pc = when (j.optString("pc")) { "online" -> "PC online"; "offline" -> "PC offline"; else -> "no PC paired" }
                        true to "Connected · ${j.optString("model")} · $pc"
                    }
                    401 -> false to "Wrong token. In Termux run: jarvis token"
                    else -> false to "Brain answered ${c.responseCode}"
                }.also { c.disconnect() }
            } catch (e: Exception) {
                false to "Brain not running. In Termux run: jarvis start"
            }
            post { done(result.first, result.second) }
        }
    }

    private fun post(r: () -> Unit) = main.post(r)
}

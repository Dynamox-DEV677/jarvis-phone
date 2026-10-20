package dev.kairo.jarvis

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Speaks JARVIS's replies with the phone's own text-to-speech, and reports every word as it is
 * spoken so the glow can pulse with it.
 */
class Speaker(context: Context, private val prefs: Prefs) {
    interface Listener {
        fun onWord()
        fun onDone()
    }

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var pending: Pair<String, Listener>? = null
    private var listener: Listener? = null
    private var lastId = ""
    private var serial = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            if (ready) applySettings()
            pending?.let { (t, l) -> pending = null; speak(t, l) }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) { main.post { listener?.onWord() } }
            override fun onDone(id: String) { main.post { if (id == lastId) finish() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { main.post { if (id == lastId) finish() } }
            override fun onError(id: String, errorCode: Int) { main.post { if (id == lastId) finish() } }
            override fun onStop(id: String, interrupted: Boolean) {}
        })
    }

    fun applySettings() {
        if (!ready) return
        val lang = prefs.language.split('-')
        val locale = if (lang.size > 1) Locale(lang[0], lang[1]) else Locale(lang[0])
        if (tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
        else tts.language = Locale.UK
        val wanted = prefs.voiceName
        if (wanted.isNotEmpty()) runCatching { tts.voices?.firstOrNull { it.name == wanted }?.let { tts.voice = it } }
        tts.setSpeechRate(prefs.speechRate / 100f)
    }

    /** English voices installed on this phone, best quality first. */
    fun voices(): List<Voice> = runCatching {
        tts.voices.orEmpty().filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.locale.toString() }.thenBy { it.name })
    }.getOrDefault(emptyList())

    fun speak(text: String, l: Listener) {
        if (!ready) { pending = text to l; return }
        listener = l
        val chunks = chunk(clean(text))
        if (chunks.isEmpty()) { finish(); return }
        serial++
        chunks.forEachIndexed { i, c ->
            val id = "u$serial-$i"
            if (i == chunks.lastIndex) lastId = id
            tts.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), id)
        }
    }

    fun stop() {
        listener = null
        lastId = ""
        if (ready) tts.stop()
    }

    val speaking: Boolean get() = listener != null

    private fun finish() {
        val l = listener
        listener = null
        l?.onDone()
    }

    fun shutdown() { stop(); tts.shutdown() }

    companion object {
        /** Markdown and links read badly aloud: strip them. */
        fun clean(s: String): String = s
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "a link")
            .replace(Regex("[*_#`>|~]"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        /** TTS engines cap one utterance at about 4000 characters: split at sentence ends. */
        fun chunk(s: String, max: Int = 3000): List<String> {
            if (s.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var rest = s
            while (rest.length > max) {
                val cut = rest.lastIndexOf(". ", max).takeIf { it > max / 2 }?.plus(1) ?: max
                out += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}

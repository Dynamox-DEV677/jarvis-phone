package dev.kairo.jarvis

import android.content.Context
import android.content.SharedPreferences

/** Everything the user sets in the app, kept on this phone only. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    init {
        // 1.3.0 brought the real JARVIS voice: switch everyone to it once (they can pick a phone voice again).
        if (p.getInt("voiceVersion", 0) < 1) p.edit().putString("voice", "").remove("rate").putInt("voiceVersion", 1).apply()
    }

    var brainAddress: String
        get() = p.getString("brain", DEFAULT_BRAIN) ?: DEFAULT_BRAIN
        set(v) = p.edit().putString("brain", v.trim().ifEmpty { DEFAULT_BRAIN }).apply()

    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()

    /** "laptop": think on the laptop through Supabase; "phone": the brain in Termux. */
    var mode: String
        get() = p.getString("mode", "laptop") ?: "laptop"
        set(v) = p.edit().putString("mode", v).apply()

    var relayUrl: String
        get() = p.getString("relayUrl", "") ?: ""
        set(v) = p.edit().putString("relayUrl", v.trim().trimEnd('/')).apply()

    var relayKey: String
        get() = p.getString("relayKey", "") ?: ""
        set(v) = p.edit().putString("relayKey", v.trim()).apply()

    var relaySecret: String
        get() = p.getString("relaySecret", "") ?: ""
        set(v) = p.edit().putString("relaySecret", v.trim()).apply()

    var orb: Boolean
        get() = p.getBoolean("orb", true)
        set(v) = p.edit().putBoolean("orb", v).apply()

    var wakeWord: Boolean
        get() = p.getBoolean("wake", false)
        set(v) = p.edit().putBoolean("wake", v).apply()

    var followUp: Boolean
        get() = p.getBoolean("followUp", true)
        set(v) = p.edit().putBoolean("followUp", v).apply()

    var captions: Boolean
        get() = p.getBoolean("captions", true)
        set(v) = p.edit().putBoolean("captions", v).apply()

    var language: String
        get() = p.getString("lang", "en-IN") ?: "en-IN"
        set(v) = p.edit().putString("lang", v.trim().ifEmpty { "en-IN" }).apply()

    /** "" = the JARVIS voice (recorded by the laptop); otherwise one of the phone's own voices. */
    var voiceName: String
        get() = p.getString("voice", "") ?: ""
        set(v) = p.edit().putString("voice", v).apply()

    /** Speech rate ×100 (100 = normal). */
    var speechRate: Int
        get() = p.getInt("rate", 100)
        set(v) = p.edit().putInt("rate", v.coerceIn(50, 200)).apply()

    /** Where the orb sits on the right edge, as a fraction of the screen height. */
    var orbY: Float
        get() = p.getFloat("orbY", 0.42f)
        set(v) = p.edit().putFloat("orbY", v.coerceIn(0.05f, 0.9f)).apply()

    /** Live control picture: "saver", "balanced" or "sharp". */
    var liveQuality: String
        get() = p.getString("liveQuality", "balanced") ?: "balanced"
        set(v) = p.edit().putString("liveQuality", v).apply()

    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) = p.edit().putBoolean("running", v).apply()

    companion object {
        const val DEFAULT_BRAIN = "127.0.0.1:8765"
    }
}

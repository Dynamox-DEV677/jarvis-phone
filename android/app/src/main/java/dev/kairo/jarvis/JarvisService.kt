package dev.kairo.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * JARVIS itself: the orb, the wake word, and one conversation turn at a time -
 * listen, think (the brain in Termux), speak, and listen again if JARVIS asked a question.
 */
class JarvisService : Service() {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var overlays: Overlays
    private lateinit var termux: BrainClient
    private lateinit var relay: RelayClient
    private val brain: Brain get() = if (prefs.mode == "laptop") relay else termux
    private lateinit var speaker: Speaker
    private lateinit var listener: Listener
    private lateinit var wake: WakeWord
    private var state = State.IDLE
    private var turn = 0 // bumps on every new turn, so a late answer from an old turn is ignored

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        if (!goForeground()) { stopSelf(); return }
        instance = this
        prefs.running = true
        overlays = Overlays(this, prefs, onOrbTap = { activate() }, onOrbLongPress = { openApp() })
        termux = BrainClient(prefs)
        relay = RelayClient(this, prefs)
        speaker = Speaker(this, prefs)
        listener = Listener(this, prefs)
        wake = WakeWord(this) { if (state == State.IDLE) listen() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (instance == null) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_ACTIVATE -> activate()
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { finish(quiet = true); handle(it) }
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> applySettings()
        }
        return START_NOT_STICKY
    }

    private fun applySettings() {
        if (prefs.orb) overlays.showOrb() else overlays.hideOrb()
        speaker.applySettings()
        if (state == State.IDLE) {
            if (prefs.wakeWord && wake.modelReady && hasMic()) wake.start() else wake.pause()
        }
    }

    /* ── one turn ─────────────────────────────────────────────────────────── */

    /** The orb, the tile, the shortcut and the notification all land here: talk, or stop. */
    fun activate() {
        if (state == State.IDLE) listen() else finish()
    }

    private fun listen() {
        if (!hasMic()) { openApp(); return }
        val my = ++turn
        state = State.LISTENING
        wake.pause()
        speaker.stop()
        buzz()
        overlays.orb.active = true
        overlays.showGlow(EdgeGlowView.Mode.LISTENING)
        overlays.caption("Listening…")
        listener.start(object : Listener.Callback {
            override fun onLevel(level: Float) { if (my == turn) overlays.glow.setInput(level) }
            override fun onPartial(text: String) { if (my == turn) overlays.caption(text) }
            override fun onText(text: String) { if (my == turn) handle(text) }
            override fun onNothing(message: String?) { if (my == turn) { if (message != null) say(message) else finish() } }
        })
    }

    private fun handle(text: String) {
        val t = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        when {
            t in STOP_WORDS -> finish()
            NEW_CHAT.matches(t) -> {
                say("Starting a new conversation.")
                brain.reset()
            }
            prefs.mode == "laptop" && SCREENSHOT.containsMatchIn(t) -> screenshot()
            else -> ask(text, retried = false)
        }
    }

    private fun ask(text: String, retried: Boolean) {
        val my = ++turn
        state = State.THINKING
        overlays.orb.active = true
        overlays.showGlow(EdgeGlowView.Mode.THINKING)
        overlays.setGlowMode(EdgeGlowView.Mode.THINKING)
        overlays.caption("“$text”")
        brain.ask(text, object : Brain.Listener {
            override fun onTool(name: String) { if (my == turn) overlays.caption(toolLabel(name)) }
            override fun onReply(text: String, images: List<java.io.File>, speech: Speech?) { if (my == turn) { showShots(images); say(text.ifBlank { "Done." }, speech) } }
            override fun onError(message: String, brainDown: Boolean) {
                if (my != turn) return
                if (brainDown && !retried && prefs.mode == "phone" && Termux.startBrain(this@JarvisService)) {
                    overlays.caption("Starting my brain in Termux…")
                    main.postDelayed({ if (my == turn) ask(text, retried = true) }, 9000)
                } else if (brainDown) {
                    say("My brain in Termux isn't running. Open Termux and type: jarvis start.")
                } else {
                    say(message)
                }
            }
        })
    }

    /** "Send me a screenshot": straight to the laptop, no thinking needed. */
    private fun screenshot() {
        val my = ++turn
        state = State.THINKING
        overlays.orb.active = true
        overlays.showGlow(EdgeGlowView.Mode.THINKING)
        overlays.setGlowMode(EdgeGlowView.Mode.THINKING)
        overlays.caption("Getting your laptop screen…")
        brain.screenshot(object : Brain.Listener {
            override fun onTool(name: String) { if (my == turn) overlays.caption(toolLabel(name)) }
            override fun onReply(text: String, images: List<java.io.File>, speech: Speech?) { if (my == turn) { showShots(images); say(text, speech) } }
            override fun onError(message: String, brainDown: Boolean) { if (my == turn) say(message) }
        })
    }

    private fun showShots(images: List<java.io.File>) {
        if (images.isEmpty()) return
        // Allowed from the background because JARVIS may draw over other apps.
        runCatching {
            startActivity(Intent(this, ShotActivity::class.java)
                .putExtra(ShotActivity.EXTRA_FILES, images.map { it.absolutePath }.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
    }

    /** [speech]: the laptop's recording of [text] in JARVIS's voice, when it sent one. */
    private fun say(text: String, speech: Speech? = null) {
        val my = ++turn
        state = State.SPEAKING
        overlays.orb.active = true
        overlays.showGlow(EdgeGlowView.Mode.SPEAKING)
        overlays.setGlowMode(EdgeGlowView.Mode.SPEAKING)
        overlays.caption(Speaker.clean(text))
        speaker.speak(text, speech = speech, l = object : Speaker.Listener {
            override fun onWord() { if (my == turn) overlays.glow.pulse() }
            override fun onDone() {
                if (my != turn) return
                // JARVIS asked something: listen for the answer straight away.
                if (prefs.followUp && text.trim().endsWith("?")) main.postDelayed({ if (my == turn) listen() }, 250)
                else finish()
            }
        })
    }

    private fun finish(quiet: Boolean = false) {
        turn++
        state = State.IDLE
        listener.stop()
        speaker.stop()
        brain.cancel()
        overlays.orb.active = false
        overlays.hideGlow()
        if (quiet) overlays.hideCaption() else overlays.hideCaption(2500)
        if (prefs.wakeWord && wake.modelReady) main.postDelayed({ if (state == State.IDLE) wake.start() }, 400)
    }

    private fun toolLabel(name: String) = if (name.contains(' ')) name else when (name) {
        "pc_status" -> "Checking your PC…"
        "pc_open" -> "Opening it on your PC…"
        "pc_screenshot" -> "Looking at your PC screen…"
        "pc_lock" -> "Locking your PC…"
        "pc_media" -> "Media keys on your PC…"
        "pc_jarvis" -> "Handing it to JARVIS on your PC…"
        "claude_code" -> "Asking Claude Code on your PC…"
        "search_web" -> "Searching the web…"
        "read_url_content" -> "Reading a page…"
        "run_command" -> "Using the phone…"
        else -> "Working…"
    }

    /* ── plumbing ─────────────────────────────────────────────────────────── */

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun buzz() {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(18, 120)) }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "JARVIS", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while JARVIS is ready to listen"
            setShowBadge(false)
        })
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val talk = PendingIntent.getService(this, 1, intent(this, ACTION_ACTIVATE), flags)
        val stop = PendingIntent.getService(this, 2, intent(this, ACTION_STOP), flags)
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_orb)
            .setContentTitle("JARVIS is ready")
            .setContentText(if (prefs.wakeWord) "Say \"Jarvis\" or tap the orb." else "Tap the orb to talk.")
            .setContentIntent(open)
            .setOngoing(true)
            .setColor(0xFFFF8A1F.toInt())
            .addAction(Notification.Action.Builder(null, "Talk", talk).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION_ID, n)
            true
        } catch (e: Exception) {
            // No microphone permission yet, or Android won't let the mic start from the background.
            Log.w("Jarvis", "can't start in the foreground: ${e.message}")
            false
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (instance != null) overlays.onScreenChanged()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            prefs.running = false
            finish(quiet = true)
            overlays.removeAll()
            wake.release()
            speaker.shutdown()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.kairo.jarvis.START"
        const val ACTION_ACTIVATE = "dev.kairo.jarvis.ACTIVATE"
        const val ACTION_ASK = "dev.kairo.jarvis.ASK"
        const val ACTION_STOP = "dev.kairo.jarvis.STOP"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION_ID = 7
        private val STOP_WORDS = setOf("stop", "cancel", "never mind", "nevermind", "nothing", "shut up", "be quiet", "that's all", "thats all", "thank you", "thanks")
        private val SCREENSHOT = Regex("\\b(screenshot|screen ?shot|my (laptop|pc|computer) screen|what'?s on (my|the) (laptop|pc|computer|screen))\\b")
        private val NEW_CHAT = Regex("^(start )?(a )?new (chat|conversation)|start over|reset( the)? (chat|conversation)$")

        @Volatile var instance: JarvisService? = null
            private set
        val running: Boolean get() = instance != null

        fun intent(c: Context, action: String): Intent = Intent(c, JarvisService::class.java).setAction(action)

        /** Start (or poke) the service. Call from something the user is looking at. */
        fun send(c: Context, action: String, text: String? = null) {
            val i = intent(c, action)
            if (text != null) i.putExtra(EXTRA_TEXT, text)
            // Already running in the foreground: a plain start. Otherwise Android requires the
            // foreground start, and the service promotes itself in onCreate.
            runCatching { if (running) c.startService(i) else c.startForegroundService(i) }
        }
    }
}

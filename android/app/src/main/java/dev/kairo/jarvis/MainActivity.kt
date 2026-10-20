package dev.kairo.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** The setup screen: permissions, the brain's token, and JARVIS's settings. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var brain: BrainClient
    private lateinit var relay: RelayClient
    private var speaker: Speaker? = null
    private val dp by lazy { resources.displayMetrics.density }
    private val refreshers = mutableListOf<() -> Unit>()

    private val ink = 0xFFF2EFEA.toInt()
    private val dim = 0xFF9A9CA3.toInt()
    private val orange = 0xFFFF8A1F.toInt()
    private val good = 0xFF6FD08C.toInt()
    private val card = 0xFF17191E.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        brain = BrainClient(prefs)
        relay = RelayClient(this, prefs)
        build()
        handlePairing(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairing(intent)
    }

    /** The QR code on the laptop opens jarvis://pair?u=…&k=…&s=… here. */
    private fun handlePairing(intent: Intent?) {
        val data = intent?.dataString ?: return
        pairWith(data)
    }

    private fun pairWith(text: String) {
        val p = RelayClient.parsePairing(text)
        if (p == null) { info("That isn't a pairing code", "Scan the QR code that SETUP-JARVIS-LAPTOP shows on your laptop, or copy its pairing text (it starts with jarvis://pair)."); return }
        prefs.relayUrl = p.first; prefs.relayKey = p.second; prefs.relaySecret = p.third
        prefs.mode = "laptop"
        refreshService()
        refreshers.forEach { it() }
        info("Paired with your laptop", "JARVIS will now think on your laptop, from anywhere. Keep JARVIS running on the laptop.")
    }

    override fun onResume() {
        super.onResume()
        refreshers.forEach { it() }
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshers.forEach { it() }
    }

    /* ── layout ───────────────────────────────────────────────────────────── */

    private fun build() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(22), px(18), px(40))
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val orb = OrbView(this).apply { active = false }
        header.addView(orb)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), 0, 0, 0)
            addView(text("JARVIS", 26f, ink, bold = true).apply { letterSpacing = 0.12f })
            addView(text("Your voice assistant · brain: Antigravity in Termux", 13f, dim))
        })
        col.addView(header)

        // Start / talk
        val startBtn = button("", primary = true) { toggleRunning() }
        val talkBtn = button("Talk now", primary = false) { JarvisService.send(this, JarvisService.ACTION_ACTIVATE) }
        col.addView(row(startBtn, talkBtn).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = px(20) })
        refreshers += {
            startBtn.text = if (JarvisService.running) "Stop JARVIS" else "Start JARVIS"
            talkBtn.isEnabled = JarvisService.running
            talkBtn.alpha = if (JarvisService.running) 1f else 0.4f
        }

        // Permissions
        col.addView(section("Permissions"))
        val perms = cardBox()
        perms.addView(permissionRow("Microphone", "To hear you.", { hasPerm(Manifest.permission.RECORD_AUDIO) }) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        if (Build.VERSION.SDK_INT >= 33) perms.addView(permissionRow("Notifications", "Android shows one while JARVIS is ready.", { hasPerm(Manifest.permission.POST_NOTIFICATIONS) }) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        })
        perms.addView(permissionRow("Display over other apps", "For the orb and the orange glow.", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        perms.addView(permissionRow("Start the brain for you", "Lets JARVIS run \"jarvis start\" in Termux when the brain isn't running.", { Termux.permitted(this) }) {
            if (Termux.installed(this)) requestPermissions(arrayOf(Termux.PERMISSION), 3)
            else info("Termux isn't installed", "Install Termux from F-Droid (f-droid.org), then run the JARVIS installer in it. The Play Store version of Termux is outdated and won't work.")
        })
        perms.addView(permissionRow("Unrestricted battery", "So Android doesn't put JARVIS to sleep. Optional.", { (getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(packageName) }) {
            @Suppress("BatteryLife") startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        col.addView(perms)

        // Brain: on the laptop (anywhere, through Supabase) or in Termux on this phone
        col.addView(section("Brain"))
        val laptopBtn = button("On my laptop", primary = false) { prefs.mode = "laptop"; refreshService(); refreshers.forEach { it() } }
        val phoneBtn = button("On this phone", primary = false) { prefs.mode = "phone"; refreshService(); refreshers.forEach { it() } }
        col.addView(row(laptopBtn, phoneBtn).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = 0 })

        val l = cardBox().apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) } }
        l.addView(text("JARVIS on your laptop answers from anywhere: home Wi-Fi or mobile data. On the laptop run SETUP-JARVIS-LAPTOP once, then scan its QR code with your camera.", 13f, dim))
        val lStatus = text("", 13f, dim).apply { setPadding(0, px(8), 0, 0) }
        l.addView(row(
            button("Paste pairing", primary = false) {
                val clip = (getSystemService(ClipboardManager::class.java)).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
                pairWith(clip)
            },
            button("Test", primary = false) { checkLaptop(lStatus) },
        ))
        l.addView(lStatus)
        l.addView(button("Laptop screenshot", primary = false) {
            if (!JarvisService.running && !canRun()) info("Not yet", "Give JARVIS the microphone and \"Display over other apps\" permissions first.")
            else JarvisService.send(this, JarvisService.ACTION_ASK, "send me a screenshot")
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) } })
        l.addView(text("To see and control the whole laptop screen live, use Google's free Chrome Remote Desktop app.", 12f, dim).apply { setPadding(0, px(10), 0, 0) })
        col.addView(l)

        val b = cardBox().apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) } }
        b.addView(text("Paste the token that Termux shows for: jarvis token", 13f, dim))
        val token = input(prefs.token, "Token", secret = true)
        b.addView(token)
        val status = text("", 13f, dim).apply { setPadding(0, px(8), 0, 0) }
        val paste = button("Paste", primary = false) {
            val clip = (getSystemService(ClipboardManager::class.java)).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (!clip.isNullOrEmpty()) { token.setText(clip); prefs.token = clip; check(status) }
        }
        val test = button("Test", primary = false) { prefs.token = token.text.toString(); check(status) }
        b.addView(row(paste, test))
        b.addView(status)
        val startBrain = button("Start the brain in Termux", primary = false) {
            if (Termux.startBrain(this)) { status.text = "Starting… (a few seconds)"; status.postDelayed({ check(status) }, 7000) }
            else status.text = "Allow \"Start the brain for you\" above first, or run jarvis start in Termux."
        }
        b.addView(startBrain)
        b.addView(text("Address (leave as is unless you know why)", 12f, dim).apply { setPadding(0, px(12), 0, 0) })
        val addr = input(prefs.brainAddress, Prefs.DEFAULT_BRAIN, secret = false)
        addr.setOnFocusChangeListener { _, f -> if (!f) prefs.brainAddress = addr.text.toString() }
        token.setOnFocusChangeListener { _, f -> if (!f) prefs.token = token.text.toString() }
        b.addView(addr)
        col.addView(b)
        refreshers += {
            val laptop = prefs.mode == "laptop"
            l.visibility = if (laptop) View.VISIBLE else View.GONE
            b.visibility = if (laptop) View.GONE else View.VISIBLE
            for ((btn, on) in listOf(laptopBtn to laptop, phoneBtn to !laptop)) {
                (btn.background as GradientDrawable).apply { if (on) { setColor(0xFF3A2410.toInt()); setStroke(px(2), orange) } else { setColor(0xFF20232A.toInt()); setStroke(px(1), 0xFF3A3E47.toInt()) } }
                btn.setTextColor(if (on) orange else dim)
            }
            if (laptop) checkLaptop(lStatus)
            else if (prefs.token.isNotEmpty()) check(status) else status.text = "No token yet."
        }

        // Settings
        col.addView(section("JARVIS"))
        val s = cardBox()
        s.addView(switchRow("Orb on the right edge", "Tap it to talk, drag it up or down, hold it to open this screen.", prefs.orb) { prefs.orb = it; refreshService() })
        val wakeNote = text("", 12f, dim)
        val wakeSwitch = switchRow("Wake word \"Jarvis\"", "Listens offline for the word Jarvis. Uses a little battery.", prefs.wakeWord) { on -> setWake(on, wakeNote) }
        s.addView(wakeSwitch)
        s.addView(wakeNote)
        s.addView(switchRow("Listen again after a question", "When JARVIS asks you something, it waits for your answer.", prefs.followUp) { prefs.followUp = it })
        s.addView(switchRow("Captions", "Show what you said and what JARVIS says.", prefs.captions) { prefs.captions = it })
        s.addView(text("Speaking speed", 14f, ink).apply { setPadding(0, px(14), 0, 0) })
        s.addView(SeekBar(this).apply {
            max = 150; progress = prefs.speechRate - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) prefs.speechRate = p + 50 }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { tts().applySettings(); refreshService() }
            })
        })
        s.addView(text("Language for listening and speaking (for example en-IN, en-GB, en-US)", 12f, dim).apply { setPadding(0, px(10), 0, 0) })
        val lang = input(prefs.language, "en-IN", secret = false)
        lang.setOnFocusChangeListener { _, f -> if (!f) { prefs.language = lang.text.toString(); tts().applySettings(); refreshService() } }
        s.addView(lang)
        s.addView(row(button("Choose voice", primary = false) { chooseVoice() }, button("Test voice", primary = false) {
            tts().applySettings()
            tts().speak("Good evening. JARVIS is online and ready when you are.", object : Speaker.Listener { override fun onWord() {}; override fun onDone() {} })
        }))
        col.addView(s)

        // Type instead of talking
        col.addView(section("Type to JARVIS"))
        val t = cardBox()
        val q = input("", "Ask anything…", secret = false).apply { imeOptions = EditorInfo.IME_ACTION_SEND; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        val send = {
            val text = q.text.toString().trim()
            if (text.isNotEmpty()) {
                if (!JarvisService.running && !canRun()) info("Not yet", "Give JARVIS the microphone and \"Display over other apps\" permissions first.")
                else { JarvisService.send(this, JarvisService.ACTION_ASK, text); q.setText("") }
            }
        }
        q.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        t.addView(q)
        t.addView(button("Send", primary = false) { send() })
        col.addView(t)

        // Help
        col.addView(section("Setting up"))
        col.addView(text(
            "1. Install Termux and Termux:API from F-Droid.\n" +
                "2. In Termux run:\n   curl -fsSL https://raw.githubusercontent.com/Dynamox-DEV677/jarvis-phone/main/termux/install.sh | bash\n" +
                "3. Sign in:  agy   ·   then:  jarvis start\n" +
                "4. Paste the token from  jarvis token  above.\n" +
                "5. For your PC: run the PC bridge on it, then the  jarvis pair  line it shows, in Termux.\n\n" +
                "Help and source: github.com/Dynamox-DEV677/jarvis-phone", 13f, dim).apply {
            setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance(); typeface = Typeface.MONOSPACE
        })

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(col) })
        refreshers.forEach { it() }
        if (prefs.wakeWord && !WakeWord(this) {}.modelReady) wakeNote.text = "The wake word's model isn't downloaded. Switch it off and on again."
    }

    /* ── actions ──────────────────────────────────────────────────────────── */

    private fun canRun() = hasPerm(Manifest.permission.RECORD_AUDIO) && Settings.canDrawOverlays(this)

    private fun toggleRunning() {
        if (JarvisService.running) {
            startService(JarvisService.intent(this, JarvisService.ACTION_STOP))
        } else {
            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return }
            if (!Settings.canDrawOverlays(this)) { info("One more permission", "Allow \"Display over other apps\" so JARVIS can show the orb and the glow."); return }
            JarvisService.send(this, JarvisService.ACTION_START)
        }
        window.decorView.postDelayed({ refreshers.forEach { it() } }, 400)
    }

    private fun refreshService() { if (JarvisService.running) JarvisService.send(this, JarvisService.ACTION_START) }

    private fun checkLaptop(status: TextView) {
        status.text = "Checking…"
        relay.health { ok, msg -> status.text = msg; status.setTextColor(if (ok) good else orange) }
    }

    private fun check(status: TextView) {
        status.text = "Checking…"
        brain.health { ok, msg -> status.text = msg; status.setTextColor(if (ok) good else orange) }
    }

    private fun setWake(on: Boolean, note: TextView) {
        if (!on) { prefs.wakeWord = false; note.text = ""; refreshService(); return }
        val wake = WakeWord(this) {}
        if (wake.modelReady) { prefs.wakeWord = true; note.text = "Say \"Jarvis\"."; refreshService(); return }
        note.text = "Downloading the offline speech model (${WakeWord.MODEL_MB} MB)…"
        wake.download(progress = { p -> if (p >= 0) note.text = "Downloading the offline speech model… $p%" }) { ok, err ->
            if (ok) { prefs.wakeWord = true; note.text = "Ready. Say \"Jarvis\"."; refreshService() }
            else { prefs.wakeWord = false; note.text = "Download failed: $err. Switch it on again to retry." }
        }
    }

    private fun tts(): Speaker = speaker ?: Speaker(this, prefs).also { speaker = it }

    private fun chooseVoice() {
        val voices = tts().voices()
        if (voices.isEmpty()) { info("No voices yet", "Your phone's text-to-speech is still starting, or has no English voices. Try again in a moment, or install voices in Settings › Text-to-speech."); return }
        val names = voices.map { v -> "${v.locale.displayName} · ${v.name.substringAfterLast('#').replace('-', ' ')}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Voice").setItems(names) { _, i ->
            prefs.voiceName = voices[i].name
            tts().applySettings()
            refreshService()
            tts().speak("This is how I sound now.", object : Speaker.Listener { override fun onWord() {}; override fun onDone() {} })
        }.show()
    }

    private fun info(title: String, msg: String) = AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /* ── little view helpers ──────────────────────────────────────────────── */

    private fun px(v: Int) = (v * dp).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun section(title: String) = text(title.uppercase(), 12f, orange, bold = true).apply {
        letterSpacing = 0.14f
        setPadding(px(4), px(26), 0, px(8))
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(16), px(14), px(16), px(16))
        background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(card); setStroke(px(1), 0xFF24272E.toInt()) }
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(if (primary) 0xFF1A0D00.toInt() else ink)
        background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            if (primary) setColor(orange) else { setColor(0xFF20232A.toInt()); setStroke(px(1), 0xFF3A3E47.toInt()) }
        }
        minHeight = px(48)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10) }
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = px(10) })
        }
    }

    private fun input(value: String, hint: String, secret: Boolean) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(0xFF5E616A.toInt())
        setTextColor(ink)
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.MONOSPACE
        setPadding(px(12), px(10), px(12), px(10))
        background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(0xFF0F1014.toInt()); setStroke(px(1), 0xFF2E3139.toInt()) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) }
    }

    private fun permissionRow(title: String, why: String, granted: () -> Boolean, ask: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, px(8)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f, ink))
        texts.addView(text(why, 12f, dim))
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val state = text("", 13f, good, bold = true)
        val btn = button("Allow", primary = true) { ask() }.apply { minHeight = px(40); minWidth = px(84) }
        val right = FrameLayout(this)
        right.addView(state); right.addView(btn)
        box.addView(right)
        refreshers += {
            val ok = granted()
            state.text = if (ok) "✓ On" else ""
            state.visibility = if (ok) View.VISIBLE else View.GONE
            btn.visibility = if (ok) View.GONE else View.VISIBLE
        }
        return box
    }

    private fun switchRow(title: String, why: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, px(8)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 15f, ink))
        texts.addView(text(why, 12f, dim))
        box.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(Switch(this).apply { isChecked = value; setOnCheckedChangeListener { _, c -> onChange(c) } })
        return box
    }
}

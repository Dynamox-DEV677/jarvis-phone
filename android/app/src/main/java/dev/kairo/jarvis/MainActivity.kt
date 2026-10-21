package dev.kairo.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/** The home screen: JARVIS's status, pairing with the laptop, and settings. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var brain: BrainClient
    private lateinit var relay: RelayClient
    private var speaker: Speaker? = null
    private val dp by lazy { resources.displayMetrics.density }
    private val ui = Handler(Looper.getMainLooper())
    private val refreshers = mutableListOf<() -> Unit>()

    private val bg = 0xFF0B0C10.toInt()
    private val ink = 0xFFF2EFEA.toInt()
    private val dim = 0xFF8E919A.toInt()
    private val faint = 0xFF5A5E68.toInt()
    private val orange = 0xFFFF8A1F.toInt()
    private val good = 0xFF5BD08A.toInt()
    private val bad = 0xFFFF6B5A.toInt()
    private val cardColor = 0xFF14161B.toInt()
    private val line = 0xFF23262D.toInt()
    private val fieldColor = 0xFF0F1115.toInt()
    private val medium by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val condensed by lazy { Typeface.create("sans-serif-condensed", Typeface.BOLD) }

    private lateinit var hero: HeroView
    private lateinit var statusDot: GradientDrawable
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var lapTitle: TextView
    private lateinit var lapSub: TextView

    /** The latest check of the brain (laptop or Termux). Null until the first answer. */
    private enum class State { UNPAIRED, ONLINE, OFFLINE, ERROR }
    private class Link(val state: State, val title: String, val detail: String)
    private var link: Link? = null

    private val poll = object : Runnable {
        override fun run() { checkConnection(); ui.postDelayed(this, 20_000) }
    }

    private val languages = listOf(
        "en-IN" to "English (India)", "en-GB" to "English (UK)", "en-US" to "English (US)", "ta-IN" to "Tamil", "hi-IN" to "Hindi",
    )

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

    override fun onResume() {
        super.onResume()
        refresh()
        hero.animating = true
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    override fun onPause() {
        hero.animating = false
        ui.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        speaker?.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() = refreshers.forEach { it() }

    /* ── pairing ──────────────────────────────────────────────────────────── */

    /** The QR code on the laptop holds jarvis://pair?u=…&k=…&s=… */
    private fun handlePairing(intent: Intent?) {
        val data = intent?.dataString ?: return
        pairWith(data)
    }

    private fun pairWith(text: String) {
        val p = RelayClient.parsePairing(text)
        if (p == null) { info("That isn't a pairing code", "Scan the QR code that SETUP-JARVIS-LAPTOP shows on your laptop. Its link starts with jarvis://pair"); return }
        prefs.relayUrl = p.first; prefs.relayKey = p.second; prefs.relaySecret = p.third
        prefs.mode = "laptop"
        link = null
        refreshService()
        refresh()
        checkConnection()
        info("Paired with your laptop", "JARVIS now thinks on your laptop, from anywhere. Keep JARVIS running on the laptop.\n\nYou can delete the QR picture on the laptop now.")
    }

    private fun isPaired() = prefs.relayUrl.isNotEmpty() && prefs.relayKey.isNotEmpty() && prefs.relaySecret.length >= 32

    /** Google's QR scanner from Play services: no camera permission, nothing to install from the store. */
    private fun scanPairing() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        val scanner = GmsBarcodeScanning.getClient(this, opts)
        val installer = ModuleInstall.getClient(this)
        installer.areModulesAvailable(scanner)
            .addOnSuccessListener { if (it.areModulesAvailable()) startScan(scanner) else installScanner(installer, scanner) }
            .addOnFailureListener { noScanner(it.message) }
    }

    private fun installScanner(installer: ModuleInstallClient, scanner: GmsBarcodeScanner) {
        Toast.makeText(this, "Getting the scanner ready…", Toast.LENGTH_SHORT).show()
        val listener = object : InstallStatusListener {
            override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                when (update.installState) {
                    ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> { installer.unregisterListener(this); startScan(scanner) }
                    ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                    ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> { installer.unregisterListener(this); noScanner() }
                }
            }
        }
        installer.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build())
            .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) { installer.unregisterListener(listener); startScan(scanner) } }
            .addOnFailureListener { noScanner(it.message) }
    }

    /** Right after the scanner downloads, Play services needs a moment before it opens: retry a few times. */
    private fun startScan(scanner: GmsBarcodeScanner, attempt: Int = 0) {
        scanner.startScan()
            .addOnSuccessListener { code -> pairWith(code.rawValue.orEmpty()) }
            .addOnFailureListener { e -> if (attempt < 4) ui.postDelayed({ startScan(scanner, attempt + 1) }, 1500) else noScanner(e.message) }
    }

    private fun noScanner(why: String? = null) = info(
        "The scanner didn't open",
        "Check that you're online and try again. If it still won't open, scan the QR code with Google Lens, copy the link it shows (it starts with jarvis://pair), then tap \"Paste link instead\"." +
            (why?.let { "\n\n($it)" } ?: ""),
    )

    private fun pastePairing() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (clip.isBlank()) info("Nothing copied", "Copy the pairing link first (it starts with jarvis://pair), then tap this again.")
        else pairWith(clip)
    }

    /* ── status ───────────────────────────────────────────────────────────── */

    private fun checkConnection() {
        if (prefs.mode == "laptop") {
            relay.laptop { s ->
                link = when {
                    !s.paired -> Link(State.UNPAIRED, "Not connected yet", "Pair with your laptop below to begin.")
                    s.problem != null -> Link(State.ERROR, "Can't reach the internet", s.problem)
                    s.online -> Link(State.ONLINE, "Online · ${s.host.ifEmpty { "your laptop" }}", "")
                    s.lastSeen.isEmpty() -> Link(State.OFFLINE, "Laptop not connected", "Run START-JARVIS-LAPTOP on your laptop.")
                    else -> Link(State.OFFLINE, "Laptop offline", "Last seen ${ago(s.lastSeen)}. Turn it on and start JARVIS on it.")
                }
                lapTitle.text = s.host.ifEmpty { "Your laptop" }
                lapSub.text = when {
                    !s.paired -> "Not paired yet"
                    s.problem != null -> "Can't check right now"
                    s.online -> "Online · ready"
                    s.lastSeen.isEmpty() -> "Paired · never connected"
                    else -> "Offline · last seen ${ago(s.lastSeen)}"
                }
                lapSub.setTextColor(if (s.online) good else dim)
                renderStatus()
            }
        } else if (prefs.token.isEmpty()) {
            link = Link(State.UNPAIRED, "Brain not set up", "Paste the token from Termux below.")
            renderStatus()
        } else {
            brain.health { ok, msg ->
                link = if (ok) Link(State.ONLINE, "Brain ready", "") else Link(State.OFFLINE, "Brain not running", msg)
                renderStatus()
            }
        }
    }

    private fun renderStatus() {
        val l = link
        val running = JarvisService.running
        val ready = if (running) (if (prefs.wakeWord) "Tap the orb or say \"Jarvis\"." else "Tap the orb to talk.") else "Tap Start to put JARVIS on your screen."
        val (color, title, sub) = if (l == null) Triple(faint, "Connecting…", "") else when (l.state) {
            State.UNPAIRED -> Triple(faint, l.title, l.detail)
            State.ONLINE -> Triple(good, l.title, ready)
            State.OFFLINE -> Triple(orange, l.title, l.detail)
            State.ERROR -> Triple(bad, l.title, l.detail)
        }
        statusDot.setColor(color)
        statusText.text = title
        statusSub.text = sub
        statusSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        hero.mood = when { l?.state == State.ONLINE && running -> 2; l?.state == State.ONLINE -> 1; else -> 0 }
    }

    private fun ago(iso: String): String = runCatching {
        val m = Duration.between(OffsetDateTime.parse(iso).toInstant(), Instant.now()).toMinutes()
        when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 60 * 24 -> "${m / 60} h ago"
            else -> "${m / (60 * 24)} days ago"
        }
    }.getOrDefault("a while ago")

    /* ── layout ───────────────────────────────────────────────────────────── */

    private fun build() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(10), px(20), px(28))
        }

        // Top bar
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("JARVIS", 15f, ink, condensed).apply { letterSpacing = 0.34f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("v${BuildConfig.VERSION_NAME}", 12f, faint))
        })

        // The arc reactor and the status under it
        hero = HeroView(this).apply { contentDescription = "Talk to JARVIS"; setOnClickListener { talk() } }
        col.addView(hero, lp(h = px(236), top = 6))
        statusDot = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(faint) }
        statusText = text("Connecting…", 18f, ink, medium)
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(View(context).apply { background = statusDot }, LinearLayout.LayoutParams(px(9), px(9)).apply { marginEnd = px(9) })
            addView(statusText)
        }, lp(top = 4))
        statusSub = text("", 13.5f, dim).apply { gravity = Gravity.CENTER }
        col.addView(statusSub, lp(top = 4))

        // Start / talk
        val start = Pill("Start JARVIS", LineIcon.Kind.POWER, Style.PRIMARY) { toggleRunning() }
        val talk = Pill("", LineIcon.Kind.MIC, Style.SECONDARY) { talk() }.apply { contentDescription = "Talk now"; setPadding(0, 0, 0, 0) }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(start, LinearLayout.LayoutParams(0, px(56), 1f))
            addView(talk, LinearLayout.LayoutParams(px(56), px(56)).apply { marginStart = px(12) })
        }, lp(top = 22))
        refreshers += {
            val on = JarvisService.running
            start.setLabel(if (on) "Stop JARVIS" else "Start JARVIS")
            start.setStyle(if (on) Style.SECONDARY else Style.PRIMARY)
            renderStatus()
        }

        // Type instead of talking
        val ask = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cardColor, 100f, line)
            setPadding(px(20), px(4), px(6), px(4))
        }
        val q = EditText(this).apply {
            hint = "Ask JARVIS anything…"
            setHintTextColor(faint); setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            styleCursor(this)
        }
        val send = {
            val t = q.text.toString().trim()
            if (t.isNotEmpty()) {
                if (!canRun()) askForMissing()
                else {
                    JarvisService.send(this, JarvisService.ACTION_ASK, t)
                    q.setText("")
                    getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(q.windowToken, 0)
                }
            }
        }
        q.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        ask.addView(q, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        ask.addView(Pill("", LineIcon.Kind.SEND, Style.PRIMARY) { send() }.apply { contentDescription = "Send"; setPadding(0, 0, 0, 0); minimumHeight = 0 }, LinearLayout.LayoutParams(px(44), px(44)))
        col.addView(ask, lp(top = 14))

        // Permissions, only while something is missing
        val setup = card("Finish setting up", "JARVIS needs these to hear you and to show the orb.")
        setup.addView(permissionRow("Microphone", "To hear you.", { hasPerm(Manifest.permission.RECORD_AUDIO) }) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        if (Build.VERSION.SDK_INT >= 33) setup.addView(permissionRow("Notifications", "Android shows one while JARVIS is on.", { hasPerm(Manifest.permission.POST_NOTIFICATIONS) }) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        })
        setup.addView(permissionRow("Display over other apps", "For the orb and the orange glow.", { Settings.canDrawOverlays(this) }) { openOverlaySettings() })
        refreshers += { setup.visibility = if (requiredGranted()) View.GONE else View.VISIBLE }

        // The laptop
        val lap = card(null)
        lapTitle = text("Your laptop", 16f, ink, medium)
        lapSub = text("", 13f, dim)
        lap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconTile(LineIcon.Kind.LAPTOP), LinearLayout.LayoutParams(px(44), px(44)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(lapTitle); addView(lapSub)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14) })
            addView(Pill("", LineIcon.Kind.REFRESH, Style.QUIET) { link = null; renderStatus(); checkConnection() }.apply {
                contentDescription = "Check again"; setPadding(0, 0, 0, 0); minimumHeight = 0
            }, LinearLayout.LayoutParams(px(40), px(40)))
        })
        val unpaired = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        unpaired.addView(steps("On your laptop, double-click SETUP-JARVIS-LAPTOP.", "A QR code opens. Tap Scan and point your phone at it."), lp(top = 16))
        unpaired.addView(Pill("Scan QR code", LineIcon.Kind.SCAN, Style.PRIMARY) { scanPairing() }, lp(h = px(52), top = 16))
        unpaired.addView(Pill("Paste link instead", LineIcon.Kind.PASTE, Style.GHOST) { pastePairing() }, lp(top = 6))
        val paired = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        paired.addView(row(
            Pill("Screenshot", LineIcon.Kind.IMAGE, Style.SECONDARY) {
                if (!canRun()) askForMissing() else JarvisService.send(this, JarvisService.ACTION_ASK, "send me a screenshot")
            },
            Pill("Live control", LineIcon.Kind.SCREEN, Style.SECONDARY) { openLive() },
        ), lp(top = 16))
        paired.addView(Pill("Pair again", LineIcon.Kind.SCAN, Style.GHOST) { scanPairing() }, lp(top = 6))
        lap.addView(unpaired); lap.addView(paired)
        col.addView(lap, lp(top = 18))

        // Termux on this phone (only in phone mode)
        val ph = card("Brain on this phone", "Antigravity inside Termux does the thinking.")
        val tokenStatus = text("", 13f, dim).apply { setPadding(0, px(8), 0, 0) }
        val token = input(prefs.token, "Token from: jarvis token", secret = true)
        token.setOnFocusChangeListener { _, f -> if (!f) prefs.token = token.text.toString() }
        ph.addView(token, lp(top = 14))
        ph.addView(row(
            Pill("Paste", LineIcon.Kind.PASTE, Style.SECONDARY) {
                val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (!clip.isNullOrEmpty()) { token.setText(clip); prefs.token = clip; checkBrain(tokenStatus) }
            },
            Pill("Test", LineIcon.Kind.REFRESH, Style.SECONDARY) { prefs.token = token.text.toString(); checkBrain(tokenStatus) },
        ), lp(top = 10))
        ph.addView(tokenStatus)
        ph.addView(Pill("Start the brain in Termux", LineIcon.Kind.POWER, Style.GHOST) {
            if (Termux.startBrain(this)) { tokenStatus.text = "Starting… (a few seconds)"; tokenStatus.postDelayed({ checkBrain(tokenStatus) }, 7000) }
            else tokenStatus.text = "Allow \"Start the brain for you\" below first, or run jarvis start in Termux."
        }, lp(top = 4))
        ph.addView(divider())
        ph.addView(permissionRow("Start the brain for you", "Lets JARVIS run \"jarvis start\" in Termux.", { Termux.permitted(this) }) {
            if (Termux.installed(this)) requestPermissions(arrayOf(Termux.PERMISSION), 3)
            else info("Termux isn't installed", "Install Termux from F-Droid (f-droid.org), then run the JARVIS installer in it. The Play Store version of Termux is outdated and won't work.")
        })
        ph.addView(text("Address", 13f, dim).apply { setPadding(0, px(14), 0, 0) })
        val addr = input(prefs.brainAddress, Prefs.DEFAULT_BRAIN, secret = false)
        addr.setOnFocusChangeListener { _, f -> if (!f) prefs.brainAddress = addr.text.toString() }
        ph.addView(addr, lp(top = 6))
        ph.addView(text("New phone? In Termux run:\ncurl -sL dynamox-dev677.github.io/jarvis-phone/i | bash", 12f, faint).apply {
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setPadding(0, px(14), 0, 0)
        })
        col.addView(ph, lp(top = 18))
        col.addView(setup, lp(top = 14))
        refreshers += {
            val laptop = prefs.mode == "laptop"
            lap.visibility = if (laptop) View.VISIBLE else View.GONE
            ph.visibility = if (laptop) View.GONE else View.VISIBLE
            unpaired.visibility = if (isPaired()) View.GONE else View.VISIBLE
            paired.visibility = if (isPaired()) View.VISIBLE else View.GONE
        }

        // Voice and behaviour
        val s = card("Voice & behaviour")
        s.addView(switchRow("Orb on the screen edge", "Tap it to talk, drag it up or down, hold it to open this screen.", prefs.orb) { prefs.orb = it; refreshService() })
        s.addView(divider())
        val wakeNote = text("", 12.5f, orange).apply {
            visibility = View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
            })
        }
        s.addView(switchRow("Wake word \"Jarvis\"", "Listens offline for the word Jarvis. Uses a little battery.", prefs.wakeWord) { on -> setWake(on, wakeNote) })
        s.addView(wakeNote)
        s.addView(divider())
        s.addView(switchRow("Listen again after a question", "When JARVIS asks you something, it waits for your answer.", prefs.followUp) { prefs.followUp = it })
        s.addView(divider())
        s.addView(switchRow("Captions", "Show what you said and what JARVIS says.", prefs.captions) { prefs.captions = it })
        s.addView(divider())
        val speedValue = text("", 14f, dim)
        s.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(14), 0, 0)
            addView(text("Speaking speed", 15f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(speedValue)
        })
        s.addView(SeekBar(this).apply {
            max = 150; progress = prefs.speechRate - 50
            progressTintList = ColorStateList.valueOf(orange)
            thumbTintList = ColorStateList.valueOf(orange)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3E47.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { prefs.speechRate = p + 50; refresh() } }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { tts().applySettings(); refreshService() }
            })
        }, lp(top = 6))
        refreshers += { speedValue.text = String.format(Locale.US, "%.2f×", prefs.speechRate / 100f) }
        s.addView(divider())
        s.addView(valueRow("Voice", { voiceLabel() }) { chooseVoice() })
        s.addView(divider())
        s.addView(valueRow("Language", { languageLabel() }) { chooseLanguage() })
        s.addView(divider())
        s.addView(permissionRow("Keep JARVIS awake", "Unrestricted battery, so Android doesn't put it to sleep. Optional.", {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }) {
            @Suppress("BatteryLife") startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        s.addView(Pill("Test voice", LineIcon.Kind.MIC, Style.GHOST) { testVoice() }, lp(top = 10))
        col.addView(s, lp(top = 14))

        // Where JARVIS thinks
        val m = card("Where JARVIS thinks")
        val modeNote = text("", 13f, dim).apply { setPadding(0, px(10), 0, 0) }
        m.addView(segmented(listOf("laptop" to "My laptop", "phone" to "This phone"), { prefs.mode }) { v ->
            prefs.mode = v; link = null; refreshService(); refresh(); checkConnection()
        }, lp(top = 14))
        m.addView(modeNote)
        refreshers += {
            modeNote.text = if (prefs.mode == "laptop") "Your laptop does the thinking. Works from anywhere, and JARVIS can use the laptop: open apps, take screenshots, ask Claude Code."
            else "Termux on this phone does the thinking. No laptop needed, but JARVIS can only reach the PC on home Wi-Fi."
        }
        col.addView(m, lp(top = 14))

        col.addView(text("JARVIS ${BuildConfig.VERSION_NAME} · open source\ngithub.com/Dynamox-DEV677/jarvis-phone", 12f, faint).apply {
            gravity = Gravity.CENTER; setLineSpacing(px(2).toFloat(), 1f)
        }, lp(top = 26))

        val scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; setBackgroundColor(bg); addView(col) }
        setContentView(scroll)
        edgeToEdge(scroll)
        refresh()
        if (prefs.wakeWord && !WakeWord(this) {}.modelReady) wakeNote.text = "The wake word's model isn't downloaded. Switch it off and on again."
    }

    /** Draws under the status and navigation bars, and pads the content clear of them and the keyboard. */
    @Suppress("DEPRECATION")
    private fun edgeToEdge(root: View) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    /* ── actions ──────────────────────────────────────────────────────────── */

    private fun requiredGranted() = hasPerm(Manifest.permission.RECORD_AUDIO) && Settings.canDrawOverlays(this) &&
        (Build.VERSION.SDK_INT < 33 || hasPerm(Manifest.permission.POST_NOTIFICATIONS))

    private fun canRun() = hasPerm(Manifest.permission.RECORD_AUDIO) && Settings.canDrawOverlays(this)

    private fun askForMissing() {
        if (!hasPerm(Manifest.permission.RECORD_AUDIO)) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        else if (!Settings.canDrawOverlays(this)) AlertDialog.Builder(this)
            .setTitle("One more permission")
            .setMessage("Allow \"Display over other apps\" so JARVIS can show the orb and the glow.")
            .setPositiveButton("Allow") { _, _ -> openOverlaySettings() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openOverlaySettings() = startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))

    private fun toggleRunning() {
        if (JarvisService.running) startService(JarvisService.intent(this, JarvisService.ACTION_STOP))
        else if (!canRun()) { askForMissing(); return }
        else JarvisService.send(this, JarvisService.ACTION_START)
        ui.postDelayed({ refresh() }, 400)
    }

    private fun talk() {
        if (!canRun()) { askForMissing(); return }
        JarvisService.send(this, JarvisService.ACTION_ACTIVATE)
        ui.postDelayed({ refresh() }, 400)
    }

    private fun refreshService() { if (JarvisService.running) JarvisService.send(this, JarvisService.ACTION_START) }

    private fun checkBrain(status: TextView) {
        status.text = "Checking…"
        status.setTextColor(dim)
        brain.health { ok, msg -> status.text = msg; status.setTextColor(if (ok) good else orange); checkConnection() }
    }

    /** Live control of the laptop, through Supabase (LiveActivity). */
    private fun openLive() = startActivity(Intent(this, LiveActivity::class.java))

    private fun setWake(on: Boolean, note: TextView) {
        if (!on) { prefs.wakeWord = false; note.text = ""; refreshService(); renderStatus(); return }
        val wake = WakeWord(this) {}
        if (wake.modelReady) { prefs.wakeWord = true; note.text = ""; refreshService(); renderStatus(); return }
        note.text = "Downloading the offline speech model (${WakeWord.MODEL_MB} MB)…"
        wake.download(progress = { p -> if (p >= 0) note.text = "Downloading the offline speech model… $p%" }) { ok, err ->
            if (ok) { prefs.wakeWord = true; note.text = "Ready. Say \"Jarvis\"."; refreshService(); renderStatus() }
            else { prefs.wakeWord = false; note.text = "Download failed: $err. Switch it on again to retry." }
        }
    }

    private fun tts(): Speaker = speaker ?: Speaker(this, prefs).also { speaker = it }

    private fun voiceLabel() = if (prefs.voiceName.isEmpty()) "JARVIS" else prefs.voiceName.substringAfterLast('#').let { if (it.length > 22) it.take(21) + "…" else it }

    private fun languageLabel() = languages.firstOrNull { it.first == prefs.language }?.second ?: Locale.forLanguageTag(prefs.language).displayName

    /** "JARVIS" (the laptop's British neural voice) first, then the phone's own voices. */
    private fun chooseVoice() {
        val voices = tts().voices()
        val names = (listOf("JARVIS · British, sent by your laptop (best)") +
            voices.map { v -> "Phone · ${v.locale.displayName} · ${v.name.substringAfterLast('#').replace('-', ' ')}" }).toTypedArray()
        AlertDialog.Builder(this).setTitle("Voice").setItems(names) { _, i ->
            prefs.voiceName = if (i == 0) "" else voices[i - 1].name
            tts().applySettings()
            refreshService()
            refresh()
            testVoice()
        }.show()
    }

    private fun testVoice() {
        tts().applySettings()
        val line = if (prefs.voiceName.isEmpty()) "At your service. JARVIS is online and ready when you are." else "This is how I sound now."
        tts().speak(line, object : Speaker.Listener { override fun onWord() {}; override fun onDone() {} })
    }

    private fun chooseLanguage() {
        val names = languages.map { it.second }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Language for listening and speaking").setItems(names) { _, i ->
            prefs.language = languages[i].first
            prefs.voiceName = ""
            tts().applySettings()
            refreshService()
            refresh()
        }.show()
    }

    private fun info(title: String, msg: String) = AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show()

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /* ── little view helpers ──────────────────────────────────────────────── */

    private fun px(v: Int) = (v * dp).toInt()

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = px(top) }

    private fun text(s: String, sp: Float, color: Int, face: Typeface? = null) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (face != null) typeface = face
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = radiusDp * dp
        setColor(fill)
        if (stroke != 0) setStroke(maxOf(1, px(1)), stroke)
    }

    private fun ripple(content: Drawable?, radiusDp: Float, color: Int) =
        RippleDrawable(ColorStateList.valueOf(color), content, rounded(Color.WHITE, radiusDp))

    private fun card(title: String?, subtitle: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(18), px(16), px(18), px(16))
        background = rounded(cardColor, 22f, line)
        if (title != null) addView(text(title, 16f, ink, medium))
        if (subtitle != null) addView(text(subtitle, 13f, dim).apply { setPadding(0, px(3), 0, 0) })
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, px(1) / 2 + 1)).apply { topMargin = px(12) }
    }

    private fun iconTile(kind: LineIcon.Kind) = ImageView(this).apply {
        setImageDrawable(LineIcon(kind, orange, px(22)))
        scaleType = ImageView.ScaleType.CENTER
        background = rounded(0xFF2A1A0C.toInt(), 14f, 0xFF4A2C10.toInt())
    }

    private fun steps(vararg lines: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        lines.forEachIndexed { i, s ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text("${i + 1}", 12f, orange, medium).apply {
                    gravity = Gravity.CENTER
                    background = rounded(0xFF2A1A0C.toInt(), 100f)
                }, LinearLayout.LayoutParams(px(22), px(22)))
                addView(text(s, 14f, dim).apply { setPadding(px(12), px(1), 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, lp(top = if (i == 0) 0 else 10))
        }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = px(10) })
        }
    }

    private fun styleCursor(e: EditText) {
        e.highlightColor = 0x55FF8A1F
        if (Build.VERSION.SDK_INT >= 29) e.textCursorDrawable = GradientDrawable().apply { setColor(orange); setSize(px(2), 0) }
    }

    private fun input(value: String, hint: String, secret: Boolean) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setHintTextColor(faint)
        setTextColor(ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        typeface = Typeface.MONOSPACE
        setPadding(px(14), px(12), px(14), px(12))
        background = rounded(fieldColor, 14f, 0xFF2E3139.toInt())
        styleCursor(this)
    }

    private fun permissionRow(title: String, why: String, granted: () -> Boolean, ask: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(14), 0, 0) }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, ink))
            addView(text(why, 12.5f, dim).apply { setPadding(0, px(2), px(10), 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = ImageView(this).apply { setImageDrawable(LineIcon(LineIcon.Kind.CHECK, good, px(22))); scaleType = ImageView.ScaleType.CENTER }
        val btn = Pill("Allow", null, Style.PRIMARY) { ask() }.apply { minimumHeight = px(36); setPadding(px(18), 0, px(18), 0) }
        box.addView(FrameLayout(this).apply {
            addView(done, FrameLayout.LayoutParams(px(36), px(36), Gravity.CENTER))
            addView(btn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36), Gravity.CENTER))
        })
        refreshers += {
            val ok = granted()
            done.visibility = if (ok) View.VISIBLE else View.GONE
            btn.visibility = if (ok) View.GONE else View.VISIBLE
        }
        return box
    }

    private fun switchRow(title: String, why: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(12), 0, 0) }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, ink))
            addView(text(why, 12.5f, dim).apply { setPadding(0, px(2), px(10), 0) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        box.addView(Switch(this).apply {
            isChecked = value
            thumbTintList = ColorStateList(states, intArrayOf(orange, 0xFFB8BBC2.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(0x88FF8A1F.toInt(), 0xFF3A3E47.toInt()))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
        return box
    }

    private fun valueRow(title: String, value: () -> String, onClick: () -> Unit): View {
        val v = text("", 14f, dim)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(12), 0, px(2))
            background = ripple(null, 10f, 0x22FFFFFF)
            isClickable = true
            setOnClickListener { onClick() }
            addView(text(title, 15f, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(v)
            addView(ImageView(context).apply { setImageDrawable(LineIcon(LineIcon.Kind.CHEVRON, faint, px(18))); scaleType = ImageView.ScaleType.CENTER },
                LinearLayout.LayoutParams(px(22), px(22)).apply { marginStart = px(4) })
        }
        refreshers += { v.text = value() }
        return box
    }

    private fun segmented(options: List<Pair<String, String>>, current: () -> String, pick: (String) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(fieldColor, 16f, line)
            setPadding(px(4), px(4), px(4), px(4))
        }
        for ((value, label) in options) {
            val t = text(label, 14f, dim, medium).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { pick(value) }
            }
            box.addView(t, LinearLayout.LayoutParams(0, px(42), 1f))
            refreshers += {
                val on = current() == value
                t.setTextColor(if (on) orange else dim)
                t.background = if (on) rounded(0xFF2A1A0C.toInt(), 12f, 0xFF7A4515.toInt()) else ripple(null, 12f, 0x22FFFFFF)
            }
        }
        return box
    }

    private enum class Style { PRIMARY, SECONDARY, GHOST, QUIET }

    /** A rounded button with an optional line icon. */
    private inner class Pill(label: String, icon: LineIcon.Kind?, style: Style, onClick: () -> Unit) : LinearLayout(this@MainActivity) {
        private val labelView = TextView(context)
        private val iconDrawable = icon?.let { LineIcon(it, ink, px(20)) }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = px(48)
            setPadding(px(16), 0, px(16), 0)
            if (iconDrawable != null) addView(ImageView(context).apply { setImageDrawable(iconDrawable) }, LayoutParams(px(20), px(20)))
            labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            labelView.typeface = medium
            addView(labelView, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (iconDrawable != null) marginStart = px(8) })
            setLabel(label)
            setStyle(style)
            setOnClickListener { onClick() }
        }

        fun setLabel(s: String) {
            labelView.text = s
            labelView.visibility = if (s.isEmpty()) GONE else VISIBLE
        }

        fun setStyle(s: Style) {
            val fg: Int
            background = when (s) {
                Style.PRIMARY -> {
                    fg = 0xFF1A0D00.toInt()
                    val fill = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFFFFA444.toInt(), 0xFFFF7A12.toInt())).apply { cornerRadius = 100 * dp }
                    ripple(fill, 100f, 0x44FFFFFF)
                }
                Style.SECONDARY -> { fg = ink; ripple(rounded(0xFF1B1E24.toInt(), 100f, 0xFF2C3038.toInt()), 100f, 0x22FFFFFF) }
                Style.GHOST -> { fg = orange; ripple(null, 100f, 0x33FF8A1F) }
                Style.QUIET -> { fg = dim; ripple(null, 100f, 0x22FFFFFF) }
            }
            labelView.setTextColor(fg)
            iconDrawable?.color = fg
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            alpha = if (enabled) 1f else 0.4f
        }
    }
}

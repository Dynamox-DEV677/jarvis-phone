package dev.kairo.jarvis

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Live control of the laptop from anywhere, through your Supabase project: see its screen and use
 * it with your fingers and the keyboard. The session ends when you leave this screen, so it never
 * uses data in the background.
 */
class LiveActivity : Activity(), LiveClient.Listener {
    private lateinit var prefs: Prefs
    private var client: LiveClient? = null
    private lateinit var live: LiveView
    private lateinit var status: TextView
    private lateinit var reconnect: TextView
    private lateinit var title: TextView
    private lateinit var info: TextView
    private lateinit var qualityChip: TextView
    private lateinit var dragChip: TextView
    private lateinit var keyboardChip: TextView
    private lateinit var panel: LinearLayout
    private lateinit var input: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val dp by lazy { resources.displayMetrics.density }
    private var host = "your laptop"
    private var used = -1L
    private var cap = 0L
    private var lastFrames = 0
    private var fps = 0

    private val ink = 0xFFF2EFEA.toInt()
    private val dim = 0xFF9A9DA6.toInt()
    private val orange = 0xFFFF8A1F.toInt()

    private val ticker = object : Runnable {
        override fun run() {
            client?.let { c ->
                fps = c.frames - lastFrames
                lastFrames = c.frames
                showInfo()
            }
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        build()
    }

    override fun onResume() {
        super.onResume()
        connect()
        ui.post(ticker)
    }

    override fun onPause() {
        // Leaving the screen ends the session: no data used in the background.
        client?.stop()
        client = null
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    private fun connect() {
        if (prefs.relaySecret.length < 32) {
            showStatus("Pair JARVIS with your laptop first.", canRetry = false)
            return
        }
        client?.stop()
        lastFrames = 0
        reconnect.visibility = View.GONE
        client = LiveClient(prefs, this).also { it.start() }
        updateChips()
    }

    /* ── what the laptop says ─────────────────────────────────────────────── */

    override fun onStatus(text: String) {
        if (text.isEmpty()) { status.visibility = View.GONE; return }
        if (!live.hasPicture) showStatus(text, canRetry = false)
    }

    override fun onFrame(w: Int, h: Int, full: Boolean, tiles: List<LiveClient.Tile>) {
        status.visibility = View.GONE
        live.onFrame(w, h, full, tiles)
    }

    override fun onState(host: String, used: Long, cap: Long) {
        this.host = host
        this.used = used
        this.cap = cap
        title.text = host
        showInfo()
    }

    override fun onEnd(why: String) {
        showStatus(why, canRetry = true)
        client = null
    }

    private fun showStatus(text: String, canRetry: Boolean) {
        status.text = text
        status.visibility = View.VISIBLE
        reconnect.visibility = if (canRetry) View.VISIBLE else View.GONE
    }

    private fun showInfo() {
        val c = client
        val session = if (c != null) mb(c.bytes) else "–"
        val month = if (used >= 0) "  ·  ${mb(used)} of ${gb(cap)} this month" else ""
        info.text = if (c != null && live.hasPicture) "Live  ·  $fps fps  ·  $session this session$month" else "Not live$month"
    }

    private fun mb(b: Long) = String.format(Locale.US, if (b < 10 * 1024 * 1024) "%.1f MB" else "%.0f MB", b / 1048576.0)
    private fun gb(b: Long) = String.format(Locale.US, "%.0f GB", b / 1073741824.0)

    /* ── layout ───────────────────────────────────────────────────────────── */

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        live = LiveView(this) { client }
        root.addView(live, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Middle: what's happening, until the picture arrives.
        val middle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        status = TextView(this).apply {
            setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.CENTER
            setPadding(px(18), px(12), px(18), px(12))
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(0xE614161B.toInt()); setStroke(px(1), 0xFF2A2D35.toInt()) }
        }
        reconnect = chip("Connect again", primary = true) { connect() }.apply { visibility = View.GONE }
        middle.addView(status)
        middle.addView(reconnect, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12) })
        root.addView(middle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { leftMargin = px(24); rightMargin = px(24) })

        // Top: close, laptop name and data, then the switches.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(6), px(10), px(10))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE6000000.toInt(), 0x00000000))
        }
        top.addView(chip("✕", primary = false) { finish() }.apply { minWidth = px(40) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), 0, px(6), 0) }
        title = TextView(this).apply { text = host; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); isSingleLine = true }
        info = TextView(this).apply { setTextColor(dim); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f); isSingleLine = true }
        names.addView(title); names.addView(info)
        top.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qualityChip = chip("", primary = false) { cycleQuality() }
        dragChip = chip("Drag", primary = false) { live.dragMode = !live.dragMode; updateChips() }
        keyboardChip = chip("Keyboard", primary = false) { togglePanel() }
        for (c in listOf(qualityChip, dragChip, keyboardChip)) top.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Bottom: shortcut keys and a box to type into the laptop.
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(8), px(10), px(10))
            setBackgroundColor(0xF20E0F13.toInt())
            visibility = View.GONE
        }
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, k) in listOf("Esc" to "esc", "Tab" to "tab", "⌫" to "backspace", "Enter" to "enter", "Win" to "win", "Ctrl+C" to "ctrl+c",
            "Ctrl+V" to "ctrl+v", "Ctrl+Z" to "ctrl+z", "Ctrl+A" to "ctrl+a", "Alt+Tab" to "alt+tab", "←" to "left", "→" to "right", "↑" to "up", "↓" to "down")) {
            keys.addView(chip(label, primary = false) { client?.keys(k) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(6) })
        }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(keys) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Type on the laptop…"
            setHintTextColor(0xFF5A5E68.toInt()); setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setPadding(px(14), px(10), px(14), px(10))
            background = GradientDrawable().apply { cornerRadius = 100 * dp; setColor(0xFF14161B.toInt()); setStroke(px(1), 0xFF2A2D35.toInt()) }
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(false); true } else false }
        }
        row.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip("Type", primary = false) { sendTyped(false) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
        row.addView(chip("Type + Enter", primary = true) { sendTyped(true) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
        panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(8) })
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
        edgeToEdge(root, top)
        updateChips()
        showStatus("Connecting…", canRetry = false)
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge(root: View, top: View) {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (t, b, l, r) = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                listOf(bars.top, maxOf(bars.bottom, ime.bottom), bars.left, bars.right)
            } else listOf(insets.systemWindowInsetTop, insets.systemWindowInsetBottom, insets.systemWindowInsetLeft, insets.systemWindowInsetRight)
            top.setPadding(px(10) + l, px(6) + t, px(10) + r, px(10))
            panel.setPadding(px(10) + l, px(8), px(10) + r, px(10) + b)
            insets
        }
    }

    private fun togglePanel() {
        val show = panel.visibility != View.VISIBLE
        panel.visibility = if (show) View.VISIBLE else View.GONE
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) { input.requestFocus(); imm.showSoftInput(input, 0) } else imm.hideSoftInputFromWindow(input.windowToken, 0)
        updateChips()
    }

    private fun sendTyped(enter: Boolean) {
        val s = input.text.toString()
        if (s.isNotEmpty()) client?.text(s)
        if (enter) client?.keys("enter")
        input.setText("")
    }

    private fun cycleQuality() {
        val order = listOf("saver", "balanced", "sharp")
        val next = order[(order.indexOf(prefs.liveQuality) + 1) % order.size]
        prefs.liveQuality = next
        client?.setQuality(next)
        updateChips()
    }

    private fun updateChips() {
        qualityChip.text = when (prefs.liveQuality) { "saver" -> "Data saver"; "sharp" -> "Sharp"; else -> "Balanced" }
        style(dragChip, live.dragMode)
        style(keyboardChip, panel.visibility == View.VISIBLE)
    }

    private fun chip(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(px(12), px(8), px(12), px(8))
        minHeight = px(38)
        isClickable = true
        setOnClickListener { onClick() }
        style(this, primary)
    }

    private fun style(v: TextView, on: Boolean) {
        val fill = GradientDrawable().apply {
            cornerRadius = 100 * dp
            if (on) setColor(orange) else { setColor(0xCC1B1E24.toInt()); setStroke(px(1), 0xFF2E323A.toInt()) }
        }
        val mask = GradientDrawable().apply { cornerRadius = 100 * dp; setColor(Color.WHITE) }
        v.background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fill, mask)
        v.setTextColor(if (on) 0xFF1A0D00.toInt() else ink)
    }

    private fun px(v: Int) = (v * dp).toInt()
}

package dev.kairo.jarvis

import android.app.Activity
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Shows the screenshots JARVIS sent from the laptop, full width. "Save" puts them in Photos. */
class ShotActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val files = intent.getStringArrayExtra(EXTRA_FILES).orEmpty().map(::File).filter { it.exists() }
        if (files.isEmpty()) { finish(); return }
        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, (24 * dp).toInt()) }
        col.addView(TextView(this).apply {
            text = if (files.size == 1) "From your laptop" else "From your laptop · ${files.size} screenshots"
            setTextColor(0xFFFF8A1F.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        })
        for (f in files) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
            col.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (10 * dp).toInt() }
                setOnClickListener { finish() }
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), 0) }
        if (Build.VERSION.SDK_INT >= 29) row.addView(Button(this).apply { text = "Save to Photos"; isAllCaps = false; setOnClickListener { save(files) } })
        row.addView(Button(this).apply { text = "Close"; isAllCaps = false; setOnClickListener { finish() } })
        col.addView(row)
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(col) })
    }

    private fun save(files: List<File>) {
        if (Build.VERSION.SDK_INT < 29) return
        var n = 0
        for (f in files) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "JARVIS-${f.name}")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JARVIS")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            n++
        }
        Toast.makeText(this, "Saved $n to Photos › JARVIS", Toast.LENGTH_SHORT).show()
    }

    companion object { const val EXTRA_FILES = "files" }
}

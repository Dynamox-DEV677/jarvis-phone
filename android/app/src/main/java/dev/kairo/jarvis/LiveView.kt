package dev.kairo.jarvis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The laptop's screen, put together from the tiles the laptop sends, and your fingers turned into
 * the mouse: tap = click, double-tap = double-click, long-press = right-click, two fingers up or
 * down = scroll, pinch = zoom (on the phone only), and in drag mode one finger drags.
 */
class LiveView(context: Context, private val client: () -> LiveClient?) : View(context) {
    private val dp = resources.displayMetrics.density
    private var screen: Bitmap? = null
    private var screenCanvas: Canvas? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val base = Matrix()   // fits the laptop screen into the view
    private val user = Matrix()   // your zoom and pan
    private val m = Matrix()
    private val inv = Matrix()
    private val rect = RectF()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * dp; color = 0xFFFF8A1F.toInt() }
    private val rings = ArrayList<FloatArray>() // x, y, start time

    /** One finger drags (mouse button held) instead of clicking. */
    var dragMode = false

    val hasPicture: Boolean get() = screen != null

    fun onFrame(w: Int, h: Int, full: Boolean, tiles: List<LiveClient.Tile>) {
        var s = screen
        if (s == null || s.width != w || s.height != h) {
            if (!full) { // a piece of a picture we don't have yet: ask for a whole one
                tiles.forEach { it.bmp.recycle() }
                client()?.requestFull()
                return
            }
            s = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            screen = s
            screenCanvas = Canvas(s)
            fit()
        }
        val c = screenCanvas ?: return
        for (t in tiles) {
            c.drawBitmap(t.bmp, t.x.toFloat(), t.y.toFloat(), null)
            t.bmp.recycle()
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    private fun fit() {
        val s = screen ?: return
        if (width == 0 || height == 0) return
        val k = min(width / s.width.toFloat(), height / s.height.toFloat())
        base.setScale(k, k)
        base.postTranslate((width - s.width * k) / 2, (height - s.height * k) / 2)
        clamp()
    }

    /** Never zoom out past "fits", and never pan the picture off the screen. */
    private fun clamp() {
        val s = screen ?: return
        val v = FloatArray(9)
        user.getValues(v)
        if (v[Matrix.MSCALE_X] < 1f) user.reset()
        m.set(base); m.postConcat(user)
        rect.set(0f, 0f, s.width.toFloat(), s.height.toFloat()); m.mapRect(rect)
        val dx = if (rect.width() <= width) width / 2f - rect.centerX() else if (rect.left > 0) -rect.left else if (rect.right < width) width - rect.right else 0f
        val dy = if (rect.height() <= height) height / 2f - rect.centerY() else if (rect.top > 0) -rect.top else if (rect.bottom < height) height - rect.bottom else 0f
        user.postTranslate(dx, dy)
        m.set(base); m.postConcat(user)
        m.invert(inv)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        screen?.let { canvas.drawBitmap(it, m, paint) }
        val now = SystemClock.uptimeMillis()
        rings.removeAll { now - it[2] > 450 }
        for (r in rings) {
            val t = (now - r[2]) / 450f
            ringPaint.alpha = (255 * (1 - t)).toInt()
            canvas.drawCircle(r[0], r[1], (10 + 26 * t) * dp, ringPaint)
        }
        if (rings.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun ring(x: Float, y: Float) {
        rings += floatArrayOf(x, y, SystemClock.uptimeMillis().toFloat())
        invalidate()
    }

    /** Where a point on the phone is on the laptop screen, 0..1 each way; null if it's off the picture. */
    private fun at(x: Float, y: Float, clampToEdge: Boolean = false): FloatArray? {
        val s = screen ?: return null
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        val u = p[0] / s.width
        val v = p[1] / s.height
        if (!clampToEdge && (u < 0 || v < 0 || u > 1 || v > 1)) return null
        return floatArrayOf(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            at(e.x, e.y)?.let { client()?.tap(it[0], it[1]); ring(e.x, e.y) }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            at(e.x, e.y)?.let { client()?.doubleTap(it[0], it[1]); ring(e.x, e.y) }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            at(e.x, e.y)?.let {
                client()?.rightClick(it[0], it[1])
                ring(e.x, e.y)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            user.postTranslate(-dx, -dy) // moves the zoomed-in picture
            clamp(); invalidate()
            return true
        }
    })

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (two != Two.ZOOM) return true
            val v = FloatArray(9); user.getValues(v)
            val f = (v[Matrix.MSCALE_X] * d.scaleFactor).coerceIn(1f, 6f) / v[Matrix.MSCALE_X]
            user.postScale(f, f, d.focusX, d.focusY)
            clamp(); invalidate()
            return true
        }
    })

    private enum class Two { NONE, UNDECIDED, ZOOM, SCROLL }
    private var two = Two.NONE
    private var startSpan = 0f
    private var lastMidY = 0f
    private var scrollAcc = 0f
    private var dragging = false
    private var lastMove = 0L

    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                // Two fingers: not a tap or a drag any more.
                gestures.onTouchEvent(MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL })
                if (dragging) { at(e.getX(0), e.getY(0), true)?.let { client()?.mouse("up", it[0], it[1]) }; dragging = false }
                two = Two.UNDECIDED
                startSpan = span(e)
                lastMidY = midY(e)
                scrollAcc = 0f
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2 && two != Two.NONE) {
                val my = midY(e)
                if (two == Two.UNDECIDED) {
                    if (abs(span(e) - startSpan) > 36 * dp) two = Two.ZOOM
                    else if (abs(my - lastMidY) > 14 * dp) two = Two.SCROLL
                }
                if (two == Two.SCROLL) {
                    scrollAcc += my - lastMidY
                    val step = 34 * dp
                    while (abs(scrollAcc) >= step) {
                        val dir = if (scrollAcc > 0) 1 else -1 // fingers down = scroll up, like a phone
                        at(midX(e), my, true)?.let { client()?.scroll(dir, it[0], it[1]) }
                        scrollAcc -= dir * step
                    }
                    lastMidY = my
                } else if (two == Two.UNDECIDED) {
                    // still deciding: keep the starting point
                } else {
                    lastMidY = my
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (two != Two.NONE) { two = Two.NONE; return true }
        }
        if (two != Two.NONE) return true // the rest of a two-finger gesture

        if (dragMode) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> at(e.x, e.y)?.let { client()?.mouse("down", it[0], it[1]); dragging = true; ring(e.x, e.y) }
                MotionEvent.ACTION_MOVE -> if (dragging && SystemClock.uptimeMillis() - lastMove > 45) {
                    lastMove = SystemClock.uptimeMillis()
                    at(e.x, e.y, true)?.let { client()?.mouse("move", it[0], it[1]) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    at(e.x, e.y, true)?.let { client()?.mouse("up", it[0], it[1]) }
                    dragging = false
                }
            }
            return true
        }
        gestures.onTouchEvent(e)
        return true
    }
}

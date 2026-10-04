package com.sskaraoke.player

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.accessibility.AccessibilityManager
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.hypot
import kotlin.math.min

internal class PointerMotion(private val density: Float) {
    var x = 0f
        private set
    var y = 0f
        private set
    private var speed = 180f

    fun position(x: Float, y: Float) { this.x = x; this.y = y }

    fun stop() { speed = 180f }

    fun move(horizontal: Int, vertical: Int, seconds: Float) {
        val length = hypot(horizontal.toFloat(), vertical.toFloat())
        if (length == 0f) { stop(); return }
        // Integrate acceleration in time, not key repeats or frames; cap stalls to avoid jumps.
        val elapsed = seconds.coerceIn(0f, 0.05f)
        val accelerating = min(elapsed, (720f - speed) / 1200f)
        val distance = (speed * accelerating + 600f * accelerating * accelerating +
            720f * (elapsed - accelerating)) * density
        speed = min(720f, speed + 1200f * elapsed)
        x += horizontal / length * distance
        y += vertical / length * distance
    }
}

internal class PointerNavigation private constructor(window: Window, private val callback: Window.Callback = window.callback) :
    Window.Callback by callback, Choreographer.FrameCallback {
    private val decor = window.decorView as ViewGroup
    private val density = decor.resources.displayMetrics.density
    private val motion = PointerMotion(density)
    private val choreographer = Choreographer.getInstance()
    private val directions = mutableSetOf<Int>()
    private val capturedKeys = mutableSetOf<Int>()
    private var framePending = false
    private var lastFrameNanos = 0L
    private var pressKey: Int? = null
    private var pressTime = 0L
    private var visible = false
    private var enabled = true
    private var initialized = false
    private val cursor = object : View(decor.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrow = Path().apply {
            moveTo(2f, 2f); lineTo(2f, 24f); lineTo(8f, 18f)
            lineTo(13f, 28f); lineTo(18f, 25f); lineTo(13f, 16f)
            lineTo(22f, 16f); close()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(density, density)
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL
            canvas.drawPath(arrow, paint)
            paint.color = Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            canvas.drawPath(arrow, paint)
            canvas.restore()
        }
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.INVISIBLE
        layout(0, 0, (26 * density).toInt(), (32 * density).toInt())
    }

    init {
        decor.overlay.add(cursor)
        decor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateCursor() }
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { reset() }
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val captured = event.keyCode in capturedKeys
        if (event.action == KeyEvent.ACTION_UP) {
            capturedKeys.remove(event.keyCode)
            if (!captured) return callback.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 &&
            (captured || event.keyCode !in directionKeys)) {
            return captured || callback.dispatchKeyEvent(event)
        }
        if (!event.hasNoModifiers() || inputNeedsKeys()) {
            reset()
            return captured || callback.dispatchKeyEvent(event)
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) capturedKeys.add(event.keyCode)
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                enabled = !enabled
                reset()
                if (enabled) showCursor()
            }
            return true
        }
        if (!enabled) {
            reset()
            return captured || callback.dispatchKeyEvent(event)
        }
        if (event.keyCode in directionKeys) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.isCanceled) { reset(); return true }
                capturedKeys.add(event.keyCode)
                showCursor()
                if (directions.add(event.keyCode) && directions.size == 1) {
                    lastFrameNanos = System.nanoTime()
                    motion.stop()
                }
                scheduleFrame()
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (event.isCanceled) reset() else {
                    advance(System.nanoTime())
                    directions.remove(event.keyCode)
                    if (directions.isEmpty()) stopFrames()
                }
            }
            return true
        }
        if (visible && event.keyCode in selectKeys) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.isCanceled) return true
                capturedKeys.add(event.keyCode)
                if (pressKey == null) {
                    pressKey = event.keyCode
                    pressTime = SystemClock.uptimeMillis()
                    sendTouch(MotionEvent.ACTION_DOWN)
                }
            } else if (event.action == KeyEvent.ACTION_UP && pressKey == event.keyCode) {
                pressKey = null
                sendTouch(if (event.isCanceled) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP)
            }
            return true
        }
        return captured || callback.dispatchKeyEvent(event)
    }

    private fun inputNeedsKeys(): Boolean =
        ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true ||
            decor.context.getSystemService<AccessibilityManager>()?.isTouchExplorationEnabled == true

    private fun showCursor() {
        visible = true
        updateCursor()
        cursor.visibility = View.VISIBLE
    }

    private fun updateCursor() {
        if (decor.width == 0 || decor.height == 0) return
        val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val left = (insets?.left ?: 0).toFloat()
        val top = (insets?.top ?: 0).toFloat()
        val right = (decor.width - (insets?.right ?: 0) - 1).toFloat().coerceAtLeast(left)
        val bottom = (decor.height - (insets?.bottom ?: 0) - 1).toFloat().coerceAtLeast(top)
        if (!initialized) {
            motion.position((left + right) / 2, (top + bottom) / 2)
            initialized = true
        }
        motion.position(motion.x.coerceIn(left, right), motion.y.coerceIn(top, bottom))
        // Translation only: no layout or WebView/JavaScript work on the animation path.
        cursor.translationX = motion.x - 2 * density
        cursor.translationY = motion.y - 2 * density
    }

    private fun scheduleFrame() {
        if (!framePending && directions.isNotEmpty()) {
            framePending = true
            choreographer.postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePending = false
        if (inputNeedsKeys() || !decor.hasWindowFocus()) { reset(); return }
        advance(frameTimeNanos)
        scheduleFrame()
    }

    private fun advance(frameTimeNanos: Long) {
        if (directions.isEmpty()) return
        val horizontal = (if (KeyEvent.KEYCODE_DPAD_RIGHT in directions) 1 else 0) -
            (if (KeyEvent.KEYCODE_DPAD_LEFT in directions) 1 else 0)
        val vertical = (if (KeyEvent.KEYCODE_DPAD_DOWN in directions) 1 else 0) -
            (if (KeyEvent.KEYCODE_DPAD_UP in directions) 1 else 0)
        motion.move(horizontal, vertical, ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceAtLeast(0f))
        lastFrameNanos = maxOf(lastFrameNanos, frameTimeNanos)
        updateCursor()
        if (pressKey != null) sendTouch(MotionEvent.ACTION_MOVE)
    }

    private fun sendTouch(action: Int) {
        val event = MotionEvent.obtain(pressTime, SystemClock.uptimeMillis(), action, motion.x, motion.y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { callback.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun stopFrames() {
        choreographer.removeFrameCallback(this)
        framePending = false
        lastFrameNanos = 0L
        motion.stop()
    }

    fun reset() {
        stopFrames()
        directions.clear()
        if (pressKey != null) {
            pressKey = null
            sendTouch(MotionEvent.ACTION_CANCEL)
        }
        visible = false
        cursor.visibility = View.INVISIBLE
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        reset()
        return callback.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) reset()
        return callback.dispatchGenericMotionEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) reset()
        callback.onWindowFocusChanged(hasFocus)
    }

    companion object {
        private val directionKeys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
        private val selectKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER)

        fun install(window: Window) {
            if (window.callback !is PointerNavigation) window.callback = PointerNavigation(window)
        }
    }
}

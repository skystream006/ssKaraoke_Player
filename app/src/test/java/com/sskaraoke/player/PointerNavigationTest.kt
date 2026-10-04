package com.sskaraoke.player

import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.Button
import androidx.appcompat.widget.AppCompatImageButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h600dp-land-mdpi")
class PointerNavigationTest {
    @Test fun movementIsIndependentOfFrameRateAndDensity() {
        for (rate in listOf(30, 60, 90, 120, 144)) {
            for (density in listOf(1f, 2f, 3f)) {
                val motion = PointerMotion(density)
                repeat(rate) { motion.move(1, 0, 1f / rate) }
                assertEquals(598.5f, motion.x / density, 0.01f)
                assertEquals(0f, motion.y, 0f)
            }
        }
    }

    @Test fun diagonalsAreNormalizedAndOpposingKeysDoNotMove() {
        val straight = PointerMotion(1f)
        val diagonal = PointerMotion(1f)
        repeat(60) {
            straight.move(1, 0, 1f / 60)
            diagonal.move(-1, 1, 1f / 60)
        }
        assertEquals(straight.x, hypot(diagonal.x, diagonal.y), 0.01f)
        val x = diagonal.x
        val y = diagonal.y
        diagonal.move(0, 0, 1f)
        assertEquals(x, diagonal.x, 0f)
        assertEquals(y, diagonal.y, 0f)
    }

    @Test fun stallsAreBoundedAndReleasingResetsAcceleration() {
        val motion = PointerMotion(1f)
        repeat(60) { motion.move(1, 0, 1f / 60) }
        val beforeStall = motion.x
        motion.move(1, 0, 10f)
        assertEquals(36f, motion.x - beforeStall, 0.001f)
        motion.stop()
        val beforeRestart = motion.x
        motion.move(1, 0, 1f / 60)
        assertEquals(3.166667f, motion.x - beforeRestart, 0.001f)
    }

    @Test fun holdingADirectionMovesEveryFrameWithoutKeyRepeatsAndStopsOnRelease() {
        withPointer { window, pointer ->
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            val start = motion(pointer).x
            frame(pointer)
            val first = motion(pointer).x
            frame(pointer)
            assertTrue(first > start)
            assertTrue(motion(pointer).x > first)
            val beforeRepeats = motion(pointer).x
            repeat(10) { key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, it + 1) }
            assertEquals(beforeRepeats, motion(pointer).x, 0f)
            key(window, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertFalse(ReflectionHelpers.getField(pointer, "framePending"))
            val stopped = motion(pointer).x
            pointer.doFrame(System.nanoTime() + 100_000_000)
            assertEquals(stopped, motion(pointer).x, 0f)
        }
    }

    @Test fun pointerStaysInBoundsAndIsClampedAfterResize() {
        withPointer { window, pointer ->
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
            repeat(300) { frame(pointer) }
            assertTrue(motion(pointer).x in 0f..799f)
            assertTrue(motion(pointer).y in 0f..599f)
            measure(window.decorView, 320, 240)
            frame(pointer)
            assertTrue(motion(pointer).x in 0f..319f)
            assertTrue(motion(pointer).y in 0f..239f)
        }
    }

    @Test fun selectProducesOneTouchGestureAndCanDragWithoutMovingFocus() {
        withPointer { window, pointer ->
            val events = mutableListOf<Int>()
            var clicks = 0
            val button = Button(window.context).apply {
                setOnTouchListener { _, event -> events.add(event.actionMasked); false }
                setOnClickListener { clicks++ }
            }
            window.setContentView(button)
            measure(window.decorView, 800, 600)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            key(window, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            repeat(5) { key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, it + 1) }
            assertEquals(listOf(MotionEvent.ACTION_DOWN), events)
            assertEquals(0, clicks)
            key(window, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events)
            assertEquals(1, clicks)
            events.clear()
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
            frame(pointer)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE), events)
            pointer.reset()
            assertEquals(MotionEvent.ACTION_CANCEL, events.last())
            key(window, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
            assertEquals(1, clicks)
        }
    }

    @Test fun focusLossAndRealTouchCancelMovementAndDoNotRestartFromRepeats() {
        withPointer { window, pointer ->
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            pointer.onWindowFocusChanged(false)
            assertFalse(ReflectionHelpers.getField(pointer, "visible"))
            assertFalse(ReflectionHelpers.getField(pointer, "framePending"))
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 1)
            assertFalse(ReflectionHelpers.getField(pointer, "visible"))
            pointer.onWindowFocusChanged(true)
            key(window, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            val touch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
            try { pointer.dispatchTouchEvent(touch) } finally { touch.recycle() }
            assertFalse(ReflectionHelpers.getField(pointer, "visible"))
            assertFalse(ReflectionHelpers.getField(pointer, "framePending"))
        }
    }

    @Test fun menuReturnsKeysToNormalFocusNavigationAndInstallationIsIdempotent() {
        withPointer { window, pointer ->
            PointerNavigation.install(window)
            assertSame(pointer, window.callback)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)
            key(window, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            assertFalse(ReflectionHelpers.getField(pointer, "visible"))
            assertFalse(ReflectionHelpers.getField(pointer, "framePending"))
            key(window, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU)
            key(window, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)
            assertTrue(ReflectionHelpers.getField(pointer, "visible"))
        }
    }

    @Test fun settingsAndConfirmationDialogsAlsoHavePointerNavigation() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertTrue(activity.window.callback is PointerNavigation)
            val settings = ReflectionHelpers.getField<AppCompatImageButton>(activity, "settingsButton")
            settings.performClick()
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue(dialog.window!!.callback is PointerNavigation)
            dialog.dismiss()
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "confirmServer",
                ReflectionHelpers.ClassParameter.from(ServerAddress::class.java, ServerAddress.parse("http://karaoke.example")))
            val confirmation = ShadowDialog.getLatestDialog()
            assertTrue(confirmation.window!!.callback is PointerNavigation)
            confirmation.dismiss()
        }
    }

    @Test fun pausingTheActivityStopsThePointer() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val window = controller.get().window
            measure(window.decorView, 800, 600)
            key(window, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            controller.pause()
            val pointer = window.callback as PointerNavigation
            assertFalse(ReflectionHelpers.getField(pointer, "visible"))
            assertFalse(ReflectionHelpers.getField(pointer, "framePending"))
        }
    }

    private fun withPointer(test: (Window, PointerNavigation) -> Unit) {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            controller.windowFocusChanged(true)
            val window = controller.get().window
            measure(window.decorView, 800, 600)
            val pointer = window.callback as PointerNavigation
            try { test(window, pointer) } finally { pointer.reset() }
        }
    }

    private fun key(window: Window, action: Int, code: Int, repeats: Int = 0) =
        window.callback.dispatchKeyEvent(KeyEvent(0, 0, action, code, repeats))

    private fun frame(pointer: PointerNavigation) =
        pointer.doFrame(ReflectionHelpers.getField<Long>(pointer, "lastFrameNanos") + 16_666_667L)

    private fun motion(pointer: PointerNavigation) = ReflectionHelpers.getField<PointerMotion>(pointer, "motion")

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }
}

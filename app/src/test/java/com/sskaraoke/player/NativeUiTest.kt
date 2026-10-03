package com.sskaraoke.player

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

class PreviewActivity : MainActivity() {
    override fun automaticUpdateChecks() = false
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class NativeUiTest {
    @Test fun firstLaunchHasServerEntryAndAccessibleSettings() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val root = activity.window.decorView
            measure(root, 320, 800)
            assertTrue(descendants(root).any { it is TextInputEditText && it.text!!.isEmpty() })
            val connect = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Connect" }
            assertTrue(connect.isShown)
            assertTrue(connect.height >= 48)
            val toolbar = descendants(root).filterIsInstance<MaterialToolbar>().single()
            assertTrue(toolbar.menu.findItem(2).isVisible)
            assertFalse(toolbar.menu.findItem(1).isVisible)
        }
    }

    @Test fun settingsHasARealUpdateButtonAndAllSixSwatchesBeforeLogin() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            descendants(activity.window.decorView).filterIsInstance<MaterialToolbar>().single().menu.performIdentifierAction(2, 0)
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            val root = dialog.window!!.decorView
            measure(root, 320, 800)
            val update = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Check for updates" }
            assertNotNull(update.background)
            assertTrue(update.isEnabled)
            assertTrue(update.width > 200)
            assertTrue(update.height >= 48)
            val themes = descendants(root).filterIsInstance<RadioButton>().toList()
            assertEquals(SavedSession.themes.values.toList(), themes.map { it.text.toString() })
            assertTrue(themes.all { it.compoundDrawablesRelative[2] != null })
            val ocean = themes.single { it.text == "Ocean" }
            ocean.performClick()
            assertTrue(ocean.isChecked)
            dialog.dismiss()
        }
    }

    @Test fun updateButtonFitsALargeFontOnANarrowPhone() {
        RuntimeEnvironment.setFontScale(1.5f)
        try {
            Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                descendants(activity.window.decorView).filterIsInstance<MaterialToolbar>().single().menu.performIdentifierAction(2, 0)
                val dialog = ShadowDialog.getLatestDialog()
                val root = dialog.window!!.decorView
                measure(root, 320, 800)
                val update = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Check for updates" }
                assertTextFits(update)
                dialog.dismiss()
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    private fun assertTextFits(view: TextView) {
        val layout = view.layout
        assertNotNull(layout)
        val available = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        for (index in 0 until layout.lineCount) assertTrue(layout.getLineWidth(index) <= available + 1)
        assertTrue(layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
    }

    private fun measure(view: View, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
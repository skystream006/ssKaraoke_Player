package com.sskaraoke.player

import android.os.Looper
import android.os.Build
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

class PreviewActivity : MainActivity() {
    override fun automaticUpdateChecks() = false
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h800dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class NativeUiTest {
    @Test
    @Config(sdk = [26, 27, 35])
    fun themePreservesSharedStylesAndUsesLightNavigationIcons() {
        val context = RuntimeEnvironment.getApplication()
        context.setTheme(R.style.Theme_SsKaraoke)
        val attributes = context.obtainStyledAttributes(intArrayOf(
            androidx.appcompat.R.attr.colorPrimary,
            android.R.attr.windowBackground,
            android.R.attr.fontFamily,
            android.R.attr.windowLightStatusBar
        ))
        try {
            assertEquals(Color.parseColor("#B44FFF"), attributes.getColor(0, 0))
            assertEquals(Color.parseColor("#0E0820"), attributes.getColor(1, 0))
            assertEquals(R.font.manrope, attributes.getResourceId(2, 0))
            assertFalse(attributes.getBoolean(3, true))
        } finally { attributes.recycle() }
        if (Build.VERSION.SDK_INT >= 27) {
            val navigation = context.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightNavigationBar))
            try {
                assertFalse(navigation.getBoolean(0, true))
            } finally { navigation.recycle() }
        }
    }

    @Test
    @Config(sdk = [26, 28, 35])
    fun firstLaunchHasServerEntryAndAccessibleSettings() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val root = activity.window.decorView
            measure(root, 320, 800)
            assertTrue(descendants(root).any { it is TextInputEditText && it.text!!.isEmpty() })
            val connect = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Connect" }
            assertTrue(connect.isShown)
            assertTrue(connect.height >= 48)
            assertFalse(descendants(root).any { it is MaterialToolbar })
            val settings = settingsButton(activity)
            assertEquals(0.5f, settings.alpha, 0f)
            assertTrue(settings.isShown)
            assertTrue(settings.width >= 48 && settings.height >= 48)
            assertTrue(settings.left > 240 && settings.top > 700)
            val chrome = settings.parent as View
            ViewCompat.dispatchApplyWindowInsets(chrome, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 24, 0, 32)).build())
            measure(root, 320, 800)
            assertEquals(chrome.height - 32 - 16 - 48, settings.top)
        }
    }

    @Test fun settingsHasARealUpdateButtonAndAllSixSwatchesBeforeLogin() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            settingsButton(activity).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog()
            val root = dialog.window!!.decorView
            measure(root, 320, 800)
            val update = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Check for updates" }
            assertNotNull(update.background)
            assertTrue(update.isEnabled)
            assertTrue(update.width > 200)
            assertTrue(update.height >= 48)
            assertFalse(descendants(root).filterIsInstance<MaterialButton>().any { it.text == "Home" })
            assertFalse(descendants(root).filterIsInstance<MaterialButton>().any { it.text == "Switch user" })
            val themes = descendants(root).filterIsInstance<RadioButton>().toList()
            assertEquals(SavedSession.themes.values.toList(), themes.map { it.contentDescription.toString() })
            assertTrue(themes.all { it.text.isEmpty() && it.width >= 48 && it.height >= 48 })
            val group = themes.first().parent as RadioGroup
            assertEquals(RadioGroup.HORIZONTAL, group.orientation)
            assertTrue(group.parent is HorizontalScrollView)
            assertEquals(1, themes.map { it.top }.distinct().size)
            assertTrue(themes.zipWithNext().all { (first, second) -> first.right <= second.left })
            val ocean = themes.single { it.contentDescription == "Ocean" }
            ocean.performClick()
            assertTrue(ocean.isChecked)
            assertEquals(1, themes.count { it.isChecked })
            dialog.dismiss()
        }
    }

    @Test fun homeButtonClosesSettingsAndPreservesTheCurrentUser() {
        for (role in listOf("guest", "organizer")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("[]"))
                Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
                    val activity = controller.get()
                    val store = ReflectionHelpers.getField<SessionStore>(activity, "store")
                    val saved = SavedSession(origin = ServerAddress.parse(server.url("/").toString()).origin,
                        token = "valid", level = if (role == "organizer") "admin" else "member", password = "secret",
                        username = "Alex", memberId = "member-1", memberName = "Stage Alex", memberRole = role,
                        route = "/$role/party-1/member-1", theme = "forest", view = "{\"tab\":\"search\"}")
                    store.update(saved)
                    settingsButton(activity).performClick()
                    val dialog = ShadowDialog.getLatestDialog()
                    val root = dialog.window!!.decorView
                    measure(root, 320, 800)
                    val home = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Home" }
                    assertTrue(home.isEnabled)
                    assertTrue(home.width > 200 && home.height >= 48)
                    assertTextFits(home)
                    home.performClick()
                    assertFalse(dialog.isShowing)
                    assertEquals(saved.copy(route = "/", view = "{}"), store.current)
                }
            }
        }
    }

    @Test fun switchUserButtonPreservesLoginAndStartsUsernameSelection() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val store = ReflectionHelpers.getField<SessionStore>(activity, "store")
            val saved = SavedSession(origin = "https://karaoke.example", token = "valid", level = "admin", password = "secret",
                username = "Alex", memberId = "member-1", memberName = "Stage Alex", memberRole = "organizer", route = "/organizer/party-1/member-1")
            store.update(saved)
            settingsButton(activity).performClick()
            val dialog = ShadowDialog.getLatestDialog()
            val root = dialog.window!!.decorView
            measure(root, 320, 800)
            val labels = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toList()
            assertTrue(labels.contains(saved.origin))
            assertTrue(labels.contains(saved.username))
            assertFalse(labels.contains(saved.memberName))
            val switch = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Switch user" }
            assertTrue(switch.isEnabled)
            assertTextFits(switch)
            switch.performClick()
            assertFalse(dialog.isShowing)
            assertEquals(saved.switchUser(), store.current)
        }
    }

    @Test fun updateButtonFitsALargeFontOnANarrowPhone() {
        RuntimeEnvironment.setFontScale(1.5f)
        try {
            Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                settingsButton(activity).performClick()
                val dialog = ShadowDialog.getLatestDialog()
                val root = dialog.window!!.decorView
                measure(root, 320, 800)
                val update = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Check for updates" }
                assertTextFits(update)
                dialog.dismiss()
            }
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test
    @Config(sdk = [26, 28, 35])
    fun allThemesRecolorNativeViewsWithoutDiscardingFormInput() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val store = ReflectionHelpers.getField<SessionStore>(activity, "store")
            val input = descendants(activity.window.decorView).filterIsInstance<TextInputEditText>().single()
            input.setText("https://karaoke.example")
            val palettes = JSONObject(activity.assets.open("color-themes.json").bufferedReader().use { it.readText() })
            assertEquals(SavedSession.themes.keys, palettes.keys().asSequence().toSet())
            settingsButton(activity).performClick()
            val dialog = ShadowDialog.getLatestDialog()
            val root = dialog.window!!.decorView
            measure(root, 320, 800)
            val swatches = descendants(root).filterIsInstance<RadioButton>().toList()
            val update = descendants(root).filterIsInstance<MaterialButton>().single { it.text == "Check for updates" }
            val appRoot = ReflectionHelpers.getField<View>(activity, "root")
            for (key in SavedSession.themes.keys) {
                val palette = palettes.getJSONObject(key)
                fun color(property: String) = Color.parseColor(palette.getString(property))
                val swatch = swatches.single { it.tag == key }
                swatch.performClick()
                assertEquals(key, store.current.theme)
                assertEquals(key, SavedSession.fromJson(store.current.toJson()).theme)
                assertEquals(1, swatches.count { it.isChecked })
                val circle = (swatch.background.current as InsetDrawable).drawable as GradientDrawable
                assertEquals(color("--primary"), circle.color!!.defaultColor)
                assertEquals(color("--primary"), update.backgroundTintList!!.defaultColor)
                assertEquals(color("--bg-dark"), update.currentTextColor)
                assertEquals(color("--bg-dark"), (appRoot.background as ColorDrawable).color)
                assertEquals(color("--bg-dark"), (root.background as ColorDrawable).color)
                assertEquals(color("--text-primary"), input.currentTextColor)
                assertEquals(color("--primary"), settingsButton(activity).imageTintList!!.defaultColor)
                assertEquals("https://karaoke.example", input.text.toString())
            }
            dialog.dismiss()
            settingsButton(activity).performClick()
            val reopened = ShadowDialog.getLatestDialog()
            val selected = descendants(reopened.window!!.decorView).filterIsInstance<RadioButton>().single { it.isChecked }
            assertEquals("dark", selected.tag)
            reopened.dismiss()
        }
    }

    @Test fun staleWebSnapshotsCannotUndoNativeThemeSelection() {
        Robolectric.buildActivity(PreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val store = ReflectionHelpers.getField<SessionStore>(activity, "store")
            store.update(store.current.copy(theme = "forest"))
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "receiveMessage",
                ReflectionHelpers.ClassParameter.from(JSONObject::class.java,
                    JSONObject().put("type", "snapshot").put("state", SavedSession(theme = "ocean").seed())))
            assertEquals("forest", store.current.theme)
        }
    }

    private fun settingsButton(activity: MainActivity) =
        descendants(activity.window.decorView).filterIsInstance<AppCompatImageButton>().single { it.contentDescription == "App settings" }

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
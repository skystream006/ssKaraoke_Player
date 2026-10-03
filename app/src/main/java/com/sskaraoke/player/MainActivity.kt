package com.sskaraoke.player

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

open class MainActivity : AppCompatActivity() {
    private lateinit var store: SessionStore
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var settingsButton: AppCompatImageButton
    private lateinit var content: FrameLayout
    private lateinit var pageProgress: LinearProgressIndicator
    private lateinit var fullscreen: FrameLayout
    private val sessionClient = SessionClient()
    private val updates = UpdateClient()
    private var webView: WebView? = null
    private var documentScript: ScriptHandler? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var connectionJob: Job? = null
    private var updateJob: Job? = null
    private var settingsDialog: Dialog? = null
    private var renewOnResume = false
    private var connectionPaused = false
    private var isForeground = false
    private var startupCheckDone = false
    private var availableRelease: ReleaseInfo? = null
    private var pendingApk: File? = null
    private var awaitingInstallPermission = false
    private var updateStatus = "Installed v${BuildConfig.VERSION_NAME}"
    private var updateProgress = -1
    private var downloading = false
    private var checking = false
    private var refreshUpdateViews: (() -> Unit)? = null
    private val themeJson by lazy { assets.open("color-themes.json").bufferedReader().use { it.readText() } }
    private val palettes by lazy { JSONObject(themeJson) }
    private fun themeColor(property: String, key: String = store.current.theme): Int =
        Color.parseColor((palettes.optJSONObject(key) ?: palettes.getJSONObject("neonPurple")).getString(property))
    private val primary get() = themeColor("--primary")
    private val ink get() = themeColor("--text-primary")
    private val muted get() = themeColor("--text-secondary")
    private val surface get() = themeColor("--bg-dark")
    private val card get() = themeColor("--bg-card")
    private val sessionScript by lazy { assets.open("mobile-session.js").bufferedReader().use { it.readText() } }
    private val shellScript by lazy { assets.open("mobile-shell.js").bufferedReader().use { it.readText() } }
    private val shellCss by lazy { assets.open("mobile-shell.css").bufferedReader().use { it.readText() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        store = SessionStore(this)
        buildChrome()
        onBackPressedDispatcher.addCallback(this) {
            when {
                fullscreen.isVisible -> hideFullscreen()
                webView?.canGoBack() == true -> webView?.goBack()
                store.current.origin.isNotEmpty() && store.current.route != "/" -> navigate("/")
                else -> finish()
            }
        }
        if (store.current.origin.isEmpty()) showServerSetup() else connect()
        if (store.recoveryNeeded) toast("Saved sign-in could not be unlocked. Please connect and sign in again.")
        receiveSharedLink(intent)
    }

    private fun buildChrome() {
        root = FrameLayout(this).apply { setBackgroundColor(surface) }
        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageProgress = LinearProgressIndicator(this).apply { isIndeterminate = true; isVisible = false }
        column.addView(pageProgress, LinearLayout.LayoutParams(-1, dp(3)))
        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val chrome = FrameLayout(this)
        chrome.addView(column, FrameLayout.LayoutParams(-1, -1))
        settingsButton = AppCompatImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_manage)
            contentDescription = "App settings"
            tooltipText = contentDescription
            alpha = 0.5f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { showSettings() }
        }
        chrome.addView(settingsButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(16), dp(16), dp(16), dp(16))
        })
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        fullscreen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isVisible = false }
        root.addView(fullscreen, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applyInsets(chrome)
        applyNativeTheme()
    }

    private fun applyInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bounds = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            target.setPadding(bounds.left, bounds.top, bounds.right, bounds.bottom)
            insets
        }
    }

    private fun showServerSetup(prefill: String = "") {
        disposeWebView()
        pageProgress.isVisible = false
        val form = formPage()
        val image = ImageView(this).apply {
            setImageResource(R.drawable.app_icon)
            contentDescription = "ssKaraoke Player"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        form.addView(image, LinearLayout.LayoutParams(dp(96), dp(96)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24) })
        form.addView(text("ssKaraoke Player", 26f, true))
        form.addView(text("Connect to your server", 17f, secondary = true), rowParams(8, 24))
        val (field, input) = inputField("Server URL", prefill, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        input.imeOptions = EditorInfo.IME_ACTION_GO
        form.addView(field, rowParams())
        val connectButton = button("Connect") {
            try {
                val address = ServerAddress.parse(input.text.toString())
                field.error = null
                confirmServer(address)
            } catch (failure: IllegalArgumentException) { field.error = failure.message }
        }
        form.addView(connectButton, rowParams(16))
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { connectButton.performClick(); true } else false
        }
        form.addView(text("v${BuildConfig.VERSION_NAME}", 13f, secondary = true).apply { gravity = Gravity.CENTER }, rowParams(24))
    }

    private fun confirmServer(address: ServerAddress) {
        val proceed = {
            store.update(SavedSession(origin = address.origin, route = address.initialRoute, theme = store.current.theme))
            connect()
        }
        if (address.encrypted) proceed() else alertBuilder()
            .setTitle("Use unencrypted HTTP?")
            .setMessage("Passwords and party traffic can be read on this network. Continue only on a trusted network. HTTPS is recommended.")
            .setNegativeButton("Cancel", null).setPositiveButton("Connect") { _, _ -> proceed() }.show().also { themeDialog(it) }
    }

    private fun connect(forceRenew: Boolean = false) {
        if (store.current.origin.isEmpty()) return
        connectionJob?.cancel()
        if (webView == null) {
            val form = formPage()
            form.addView(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(primary) },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            form.addView(text("Connecting", 20f, true).apply { gravity = Gravity.CENTER }, rowParams(20))
        }
        pageProgress.isVisible = true
        connectionJob = lifecycleScope.launch {
            try {
                val resumed = sessionClient.resume(store.current, forceRenew)
                store.update(store.current.copy(token = resumed.token, level = resumed.level))
                showWebView()
                if (forceRenew) toast("Session renewed. Please retry your last action.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expired: SignInRequired) {
                store.update(store.current.copy(token = "", level = "none", password = "", usernameRequired = false))
                showWebView()
                toast(expired.message.orEmpty())
            } catch (failure: Exception) {
                if (webView == null) showError(failure.message ?: "Cannot reach the server.") { connect(forceRenew) }
                else {
                    toast("Connection interrupted. Your sign-in is saved; use App settings > Refresh to reconnect.")
                    webView?.evaluateJavascript("window.KaraokeAndroid?.retryAuthentication()", null)
                }
            } finally { pageProgress.isVisible = false }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            showError("Update Android System WebView or Chrome to use secure session restoration.") {
                openExternal("https://play.google.com/store/apps/details?id=com.google.android.webview", false)
            }
            return
        }
        disposeWebView()
        content.removeAllViews()
        val server = ServerAddress.parse(store.current.origin)
        val browser = WebView(this)
        webView = browser
        browser.setBackgroundColor(surface)
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            safeBrowsingEnabled = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString ssKaraoke-Player/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        WebViewCompat.addWebMessageListener(browser, "KaraokeHost", setOf(server.origin)) { view, message, source, mainFrame, _ ->
            if (view === webView && mainFrame && server.owns(source.toString())) {
                val data = message.data
                if (data != null && data.length <= 32768) {
                    try { receiveMessage(JSONObject(data)) } catch (_: Exception) { }
                }
            }
        }
        installDocumentScript(browser)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return request.url.scheme in setOf("file", "content", "intent")
                if (server.owns(request.url.toString())) {
                    installDocumentScript(view)
                    return false
                }
                if (request.hasGesture()) openExternal(request.url.toString())
                else toast("A redirect to another server was blocked. Change the server address in App settings if needed.")
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!server.owns(url)) {
                    view.stopLoading()
                    showError("The server redirected to a different address.") { showServerSetup() }
                    return
                }
                pageProgress.isVisible = true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageProgress.isVisible = false
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showError("Cannot load this server. Check its address and your network connection.") { connect() }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) showError("Server returned HTTP ${response.statusCode}. Use the karaoke web address, not the backend /api address.") { connect() }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (server.owns(error.url)) showError("The server's TLS certificate is not trusted or does not match its hostname. Fix the certificate or install your private CA in Android settings.") { connect() }
            }
        }
        browser.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, progress: Int) { pageProgress.isVisible = progress < 100 }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customViewCallback != null) { callback.onCustomViewHidden(); return }
                customViewCallback = callback
                fullscreen.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreen.isVisible = true
                column.isVisible = false
                settingsButton.isVisible = false
                WindowInsetsControllerCompat(window, root).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
            override fun onHideCustomView() { hideFullscreen() }
        }
        browser.setDownloadListener { url, _, _, _, _ -> openExternal(url) }
        content.addView(browser, FrameLayout.LayoutParams(-1, -1))
        if (isForeground) {
            browser.resumeTimers()
            browser.onResume()
        }
        browser.loadUrl(server.origin + server.safeRoute(store.current.route))
        updateKeepAwake()
    }

    private fun installDocumentScript(browser: WebView) {
        documentScript?.remove()
        val script = "if(window===window.top){window.__karaokeSeed=${store.current.seed()};window.__karaokeThemes=$themeJson;window.__karaokeCss=${JSONObject.quote(shellCss)};\n$sessionScript\n$shellScript\n}"
        documentScript = WebViewCompat.addDocumentStartJavaScript(browser, script, setOf(store.current.origin))
    }

    private fun receiveMessage(message: JSONObject) {
        when (message.optString("type")) {
            "snapshot" -> {
                val snapshot = message.optJSONObject("state") ?: return
                store.update(store.current.withSnapshot(snapshot).copy(theme = store.current.theme))
                updateKeepAwake()
            }
            "credentials" -> {
                val token = message.optString("token")
                val level = message.optString("level")
                val password = message.optString("password")
                if (token.isEmpty() || token.length > 8192 || password.length > 4096 || level !in setOf("member", "admin")) return
                store.update(store.current.copy(token = token, level = level, password = password,
                    kind = if (message.optString("kind") == "qr") "qr" else "password"))
            }
            "expired" -> {
                if (connectionJob?.isActive == true) return
                if (isForeground) connect(true) else renewOnResume = true
            }
            "logout" -> clearSession(store.current.signedOut())
            "ready" -> webView?.evaluateJavascript("window.KaraokeAndroid?.applyTheme(${JSONObject.quote(store.current.theme)})", null)
            "haptic" -> webView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    private fun navigate(route: String) {
        val server = ServerAddress.parse(store.current.origin)
        store.update(store.current.copy(route = server.safeRoute(route), view = "{}"))
        webView?.let {
            installDocumentScript(it)
            it.loadUrl(server.origin + store.current.route)
        } ?: connect()
    }

    private fun refreshPage() {
        if (store.current.origin.isNotEmpty()) connect()
    }

    private fun showError(message: String, retry: () -> Unit) {
        disposeWebView()
        pageProgress.isVisible = false
        val form = formPage()
        form.addView(text("Connection unavailable", 22f, true))
        form.addView(text(message, 16f), rowParams(16, 12))
        form.addView(button("Try again", action = retry), rowParams(8))
    }

    private fun showSettings() {
        if (settingsDialog?.isShowing == true) return
        webView?.evaluateJavascript("window.KaraokeAndroid?.snapshot()", null)
        val dialog = Dialog(this, R.style.Theme_SsKaraoke)
        settingsDialog = dialog
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(surface) }
        val heading = MaterialToolbar(this).apply {
            title = "App settings"
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationContentDescription = "Close app settings"
            setNavigationOnClickListener { dialog.dismiss() }
        }
        layout.addView(heading, LinearLayout.LayoutParams(-1, dp(64)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(4), dp(24), dp(24)) }
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))
        layout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(section("App updates"))
        val status = text(updateStatus, 15f)
        body.addView(status, rowParams(4, 12))
        val indicator = LinearProgressIndicator(this).apply { max = 100; isVisible = false }
        body.addView(indicator, rowParams(0, 12))
        val check = button("Check for updates") { checkForUpdates(false) }
        check.id = View.generateViewId()
        body.addView(check, rowParams())
        val download = button("Download and install", outlined = true) {
            if (pendingApk != null) offerInstall() else confirmDownload()
        }
        body.addView(download, rowParams(8))
        val cancel = button("Cancel download", outlined = true) { cancelUpdate() }
        body.addView(cancel, rowParams(8))
        refreshUpdateViews = {
            status.text = updateStatus
            check.isEnabled = !checking && !downloading
            check.text = if (checking) "Checking..." else "Check for updates"
            download.isVisible = availableRelease != null
            download.isEnabled = !checking && !downloading
            download.text = if (pendingApk == null) "Download and install" else "Install downloaded update"
            cancel.isVisible = downloading
            indicator.isVisible = downloading
            indicator.isIndeterminate = updateProgress < 0
            if (updateProgress >= 0) indicator.setProgressCompat(updateProgress, true)
        }
        refreshUpdateViews?.invoke()
        body.addView(section("Color theme"))
        val choices = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        SavedSession.themes.forEach { (key, name) ->
            choices.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = key
                contentDescription = name
                buttonDrawable = null
                setPadding(0, 0, 0, 0)
                minWidth = dp(48)
                minHeight = dp(48)
                isChecked = store.current.theme == key
                tintView(this)
            }, RadioGroup.LayoutParams(dp(48), dp(48)))
        }
        choices.setOnCheckedChangeListener { group, checked ->
            val key = group.findViewById<RadioButton>(checked)?.tag as? String ?: return@setOnCheckedChangeListener
            store.update(store.current.copy(theme = key))
            applyNativeTheme()
            webView?.let {
                installDocumentScript(it)
                it.evaluateJavascript("window.KaraokeAndroid?.applyTheme(${JSONObject.quote(key)})", null)
            }
        }
        val themeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(choices)
        }
        body.addView(themeScroll, rowParams())
        choices.post {
            choices.findViewById<RadioButton>(choices.checkedRadioButtonId)?.let {
                it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height))
            }
        }
        body.addView(section("Server and sign-in"))
        body.addView(text(store.current.origin.ifEmpty { "No server selected" }, 15f).apply { setTextIsSelectable(true) }, rowParams(4))
        val identity = store.current.username.ifEmpty { store.current.memberName }.ifEmpty { "Not signed in" }
        body.addView(text(identity, 15f), rowParams(8, 12))
        if (store.current.origin.isNotEmpty()) {
            body.addView(button("Refresh", true) { dialog.dismiss(); refreshPage() }, rowParams(8))
            if (store.current.token.isNotEmpty() && store.current.level in setOf("member", "admin")) {
                body.addView(button("Switch user", true) {
                    dialog.dismiss()
                    switchUser()
                }, rowParams(8))
            }
            body.addView(button("Open party link", true) { promptJoinLink(dialog) }, rowParams(8))
            if (store.current.level == "admin") body.addView(button("Server administration", true) {
                dialog.dismiss(); navigate("/settings")
            }, rowParams(8))
            body.addView(button("Open in browser", true) { openExternal(store.current.origin + store.current.route, false) }, rowParams(8))
            body.addView(button("Change server", true) {
                confirm("Change server?", "This removes this app's saved password, login, and party location for the current server.", "Change server") {
                    dialog.dismiss()
                    clearSession(SavedSession(theme = store.current.theme))
                }
            }, rowParams(8))
            body.addView(button("Sign out", true) {
                confirm("Sign out?", "Remove the saved password and session from this device? Your party and its queue stay on the server.", "Sign out") {
                    dialog.dismiss()
                    clearSession(store.current.signedOut())
                }
            }, rowParams(8))
        }
        body.addView(text("ssKaraoke Player v${BuildConfig.VERSION_NAME}", 13f, secondary = true), rowParams(28))
        dialog.setContentView(layout)
        dialog.setOnDismissListener { refreshUpdateViews = null; settingsDialog = null }
        dialog.show()
        dialog.window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        applyInsets(layout)
        applyNativeTheme()
    }

    private fun checkForUpdates(silent: Boolean) {
        if (checking || downloading) return
        checking = true
        updateStatus = "Checking GitHub releases..."
        refreshUpdateViews?.invoke()
        updateJob = lifecycleScope.launch {
            try {
                val release = updates.latest()
                val current = AppVersion.parse(BuildConfig.VERSION_NAME)!!
                if (release?.tag != availableRelease?.tag) pendingApk = null
                availableRelease = release?.takeIf { it.version > current }
                updateStatus = when {
                    availableRelease != null -> "v${release!!.versionName} available. Installed v${BuildConfig.VERSION_NAME}."
                    release == null -> "No published release yet. Installed v${BuildConfig.VERSION_NAME}."
                    else -> "You're up to date: v${BuildConfig.VERSION_NAME}."
                }
                if (silent && availableRelease != null) toast("Update ${availableRelease!!.tag} is available in App settings.")
            } catch (cancelled: CancellationException) {
                updateStatus = "Update check canceled."
                throw cancelled
            } catch (failure: Exception) {
                updateStatus = failure.message ?: "Could not check for updates."
            } finally {
                checking = false
                refreshUpdateViews?.invoke()
            }
        }
    }

    private fun confirmDownload() {
        val release = availableRelease ?: return
        val megabytes = "%.1f".format(release.size / 1048576.0)
        confirm("Download ${release.tag}?", "$megabytes MB from GitHub. Android will ask before installing the update.", "Download") { downloadUpdate(release) }
    }

    private fun downloadUpdate(release: ReleaseInfo) {
        if (downloading || checking) return
        downloading = true
        pendingApk = null
        updateProgress = 0
        updateStatus = "Downloading ${release.tag}..."
        refreshUpdateViews?.invoke()
        updateJob = lifecycleScope.launch {
            try {
                val file = updates.download(release, File(cacheDir, "updates")) { received, total ->
                    runOnUiThread {
                        updateProgress = (received * 100 / total).toInt()
                        updateStatus = "Downloading ${release.tag}: $updateProgress%"
                        refreshUpdateViews?.invoke()
                    }
                }
                updateStatus = "Verifying APK..."
                refreshUpdateViews?.invoke()
                withContext(Dispatchers.IO) { UpdateSecurity.verifyApk(this@MainActivity, file, release) }
                pendingApk = file
                updateStatus = "${release.tag} is ready to install."
            } catch (cancelled: CancellationException) {
                updateStatus = "Download canceled."
                throw cancelled
            } catch (failure: Exception) {
                updateStatus = failure.message ?: "Download failed. Please try again."
            } finally {
                downloading = false
                updateProgress = -1
                refreshUpdateViews?.invoke()
            }
            if (pendingApk != null && isForeground) offerInstall()
        }
    }

    private fun offerInstall() {
        val file = pendingApk ?: return
        val release = availableRelease ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            confirm("Allow app updates", "Android requires permission for ssKaraoke Player to install the downloaded APK. Enable Allow from this source, then return here.", "Open Android settings") {
                awaitingInstallPermission = true
                try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                catch (_: ActivityNotFoundException) { awaitingInstallPermission = false; toast("This device does not allow APK installation from this app.") }
            }
            return
        }
        confirm("Install ${release.tag}?", "Your server, sign-in, and app settings will be kept. Android will ask for final approval.", "Install") {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { UpdateSecurity.verifyApk(this@MainActivity, file, release) }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.updates", file)
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (failure: Exception) { toast(failure.message ?: "Android could not open the installer.") }
            }
        }
    }

    private fun cancelUpdate() {
        updateJob?.cancel()
        updates.cancel()
    }

    private fun promptJoinLink(parent: Dialog? = null, prefill: String = "") {
        if (store.current.origin.isEmpty()) { showServerSetup(prefill); return }
        val (field, input) = inputField("Join code or link", prefill)
        val wrapper = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(field, rowParams()) }
        val dialog = alertBuilder().setTitle("Join a party").setView(wrapper)
            .setNegativeButton("Cancel", null).setPositiveButton("Open", null).create()
        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                val server = ServerAddress.parse(store.current.origin)
                val route = if (Regex("[a-zA-Z0-9]+").matches(value)) "/join/$value" else server.safeRoute(value)
                if (!Regex("/join/[a-zA-Z0-9]+/?").matches(route)) field.error = "Enter a join code or a link from the selected server."
                else { dialog.dismiss(); parent?.dismiss(); navigate(route) }
            }
        }
        dialog.show()
        themeDialog(dialog)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedLink(intent)
    }

    private fun receiveSharedLink(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val value = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.take(2048) ?: return
        val link = Regex("https?://\\S+").find(value)?.value ?: value
        if (store.current.origin.isEmpty()) showServerSetup(link) else promptJoinLink(prefill = link)
    }

    private fun switchUser() {
        connectionJob?.cancel()
        sessionClient.cancel()
        disposeWebView()
        store.update(store.current.switchUser())
        showWebView()
    }

    private fun clearSession(replacement: SavedSession) {
        connectionJob?.cancel()
        sessionClient.cancel()
        disposeWebView()
        store.update(replacement)
        store.flush()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            if (!isDestroyed) {
                if (replacement.origin.isEmpty()) showServerSetup() else connect()
            }
        }
    }

    private fun openExternal(address: String, ask: Boolean = true) {
        val uri = Uri.parse(address)
        if (uri.scheme !in setOf("https", "http") || uri.userInfo != null) return
        val open = {
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            catch (_: ActivityNotFoundException) { toast("No browser is installed.") }
        }
        if (ask) confirm("Open external link?", uri.host.orEmpty(), "Open browser", open) else open()
    }

    private fun hideFullscreen() {
        fullscreen.removeAllViews()
        fullscreen.isVisible = false
        column.isVisible = true
        settingsButton.isVisible = true
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun disposeWebView() {
        val previous = webView ?: return
        webView = null
        if (fullscreen.isVisible) hideFullscreen()
        documentScript?.remove()
        documentScript = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(previous, "KaraokeHost")
        (previous.parent as? ViewGroup)?.removeView(previous)
        previous.stopLoading()
        previous.destroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun updateKeepAwake() {
        if (isForeground && store.current.route.startsWith("/organizer/")) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    protected open fun automaticUpdateChecks(): Boolean = true

    override fun onResume() {
        super.onResume()
        isForeground = true
        webView?.onResume()
        webView?.resumeTimers()
        updateKeepAwake()
        if (renewOnResume || connectionPaused) {
            val force = renewOnResume
            renewOnResume = false
            connectionPaused = false
            connect(force)
        }
        if (!startupCheckDone && automaticUpdateChecks()) {
            startupCheckDone = true
            checkForUpdates(true)
        }
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            if (packageManager.canRequestPackageInstalls()) offerInstall()
            else toast("Install permission was not granted. The verified download is still available in App settings.")
        }
        if (store.writeFailed) toast("Android could not securely save this session. Your next launch may require sign-in.")
    }

    override fun onStop() {
        isForeground = false
        webView?.evaluateJavascript("window.KaraokeAndroid?.snapshot();document.querySelectorAll('iframe[src*=\"youtube.com\"]').forEach(frame=>frame.contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}),'https://www.youtube.com'));", null)
        webView?.onPause()
        webView?.pauseTimers()
        if (connectionJob?.isActive == true) {
            connectionPaused = true
            connectionJob?.cancel()
            sessionClient.cancel()
        }
        if (checking || downloading) cancelUpdate()
        store.flush()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        settingsDialog?.dismiss()
        disposeWebView()
        sessionClient.cancel()
        updates.cancel()
        store.close()
        super.onDestroy()
    }

    private fun formPage(): LinearLayout {
        content.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(32), dp(24), dp(80))
        }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        center.addView(form, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels - dp(48)).coerceAtMost(dp(480)), -2))
        scroll.addView(center, ViewGroup.LayoutParams(-1, -1))
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return form
    }

    private fun text(value: String, size: Float = 16f, bold: Boolean = false, secondary: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        tag = if (secondary) "muted" else null
        setTextColor(if (secondary) muted else ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    private fun section(value: String) = text(value, 18f, true).apply { setPadding(0, dp(24), 0, dp(8)) }

    private fun button(label: String, outlined: Boolean = false, action: () -> Unit) = MaterialButton(
        this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle
    ).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        minHeight = dp(52)
        cornerRadius = dp(8)
        insetTop = dp(2)
        insetBottom = dp(2)
        tag = if (outlined) "outlined" else null
        tintView(this)
        setOnClickListener { action() }
    }

    private fun inputField(hint: String, initial: String = "", type: Int = InputType.TYPE_CLASS_TEXT): Pair<TextInputLayout, TextInputEditText> {
        val field = TextInputLayout(this).apply { boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE; this.hint = hint }
        val input = TextInputEditText(field.context).apply { inputType = type; setSingleLine(true); setText(initial); textSize = 17f }
        field.addView(input, LinearLayout.LayoutParams(-1, -2))
        tintView(field)
        return field to input
    }

    private fun confirm(title: String, message: String, positive: String, action: () -> Unit) {
        alertBuilder().setTitle(title).setMessage(message).setNegativeButton("Cancel", null)
            .setPositiveButton(positive) { _, _ -> action() }.show().also { themeDialog(it) }
    }

    private fun alertBuilder() = MaterialAlertDialogBuilder(this).setBackground(GradientDrawable().apply {
        setColor(card)
        cornerRadius = dp(24).toFloat()
    })

    private fun themeDialog(dialog: Dialog) {
        dialog.window?.let { tintView(it.decorView); themeSystemBars(it) }
    }

    private fun applyNativeTheme() {
        root.setBackgroundColor(surface)
        webView?.setBackgroundColor(surface)
        settingsButton.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(card) }
        settingsButton.imageTintList = ColorStateList.valueOf(primary)
        tintView(column)
        themeSystemBars(window)
        settingsDialog?.window?.let {
            it.setBackgroundDrawableResource(android.R.color.transparent)
            it.decorView.setBackgroundColor(surface)
            (it.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))?.setBackgroundColor(surface)
            themeDialog(settingsDialog!!)
        }
    }

    @Suppress("DEPRECATION")
    private fun themeSystemBars(target: Window) {
        target.statusBarColor = surface
        target.navigationBarColor = surface
        WindowInsetsControllerCompat(target, target.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun tintView(view: View) {
        when (view) {
            is WebView -> return
            is MaterialToolbar -> {
                view.setBackgroundColor(card)
                view.setTitleTextColor(ink)
                view.setSubtitleTextColor(muted)
                view.navigationIcon?.setTint(ink)
                return
            }
            is MaterialButton -> {
                val outlined = view.tag == "outlined"
                val disabled = -android.R.attr.state_enabled
                view.backgroundTintList = ColorStateList(
                    arrayOf(intArrayOf(disabled), intArrayOf()), intArrayOf(card, if (outlined) Color.TRANSPARENT else primary))
                view.setTextColor(ColorStateList(
                    arrayOf(intArrayOf(disabled), intArrayOf()), intArrayOf(muted, if (outlined) primary else surface)))
                view.strokeColor = ColorStateList.valueOf(primary)
                view.rippleColor = ColorStateList.valueOf(themeColor("--bg-surface"))
            }
            is RadioButton -> {
                val color = themeColor("--primary", view.tag as String)
                fun swatch(selected: Boolean) = InsetDrawable(GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(3), if (selected) ink else Color.TRANSPARENT)
                }, dp(6))
                view.backgroundTintList = null
                view.background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_checked), swatch(true))
                    addState(intArrayOf(android.R.attr.state_focused), swatch(true))
                    addState(intArrayOf(), swatch(false))
                }
            }
            is TextInputLayout -> {
                view.boxBackgroundColor = card
                view.boxStrokeColor = primary
                view.defaultHintTextColor = ColorStateList.valueOf(muted)
                view.hintTextColor = ColorStateList.valueOf(primary)
                view.editText?.setTextColor(ink)
                view.editText?.setHintTextColor(muted)
                return
            }
            is LinearProgressIndicator -> {
                view.setIndicatorColor(primary)
                view.trackColor = card
            }
            is ProgressBar -> view.indeterminateTintList = ColorStateList.valueOf(primary)
            is android.widget.Button -> view.setTextColor(primary)
            is TextView -> view.setTextColor(if (view.tag == "muted") muted else ink)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) tintView(view.getChildAt(index))
    }

    private fun rowParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
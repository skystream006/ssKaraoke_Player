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
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
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
import android.widget.GridLayout
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
import androidx.browser.customtabs.CustomTabsIntent
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
    private var signOutJob: Job? = null
    private var signInJob: Job? = null
    private var signInGeneration = 0
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
        PointerNavigation.install(window)
        onBackPressedDispatcher.addCallback(this) {
            when {
                fullscreen.isVisible -> hideFullscreen()
                webView?.canGoBack() == true -> webView?.goBack()
                store.current.origin.isNotEmpty() && store.current.route != "/" -> navigate("/")
                else -> finish()
            }
        }
        if (!receiveBrowserCallback(intent)) {
            if (store.current.origin.isEmpty()) showServerSetup() else connect()
        }
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
        connectionJob?.cancel()
        connectionPaused = false
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
            .setMessage("Passkey sign-in requires the configured HTTPS hostname. Session tokens and party traffic can be read on HTTP. Continue only on a trusted network.")
            .setNegativeButton("Cancel", null).setPositiveButton("Connect") { _, _ -> proceed() }.show().also { themeDialog(it) }
    }

    private fun connect() {
        if (store.current.origin.isEmpty() || signOutJob?.isActive == true || signInJob?.isActive == true) return
        connectionJob?.cancel()
        if (store.current.token.isEmpty() && store.current.hostScreenKey.isEmpty() &&
            (store.current.pendingLogin != null || !store.current.route.startsWith("/join/invite#invite="))) {
            showSignIn()
            return
        }
        if (webView == null) {
            val form = formPage()
            form.addView(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(primary) },
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            form.addView(text("Connecting", 20f, true).apply { gravity = Gravity.CENTER }, rowParams(20))
        }
        pageProgress.isVisible = true
        val saved = store.current
        connectionJob = lifecycleScope.launch {
            try {
                val resumed = sessionClient.resume(saved)
                if (store.current.origin != saved.origin || store.current.token != saved.token) return@launch
                store.update(if (resumed.token != saved.token) resumed.copy(theme = store.current.theme)
                    else store.current.copy(level = resumed.level, username = resumed.username, hostScreenKey = resumed.hostScreenKey))
                store.flush()
                showWebView()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expired: SignInRequired) {
                if (store.current.origin != saved.origin || store.current.token != saved.token) return@launch
                store.update(store.current.expired())
                if (store.current.route.startsWith("/join/invite#invite=")) showWebView() else showSignIn()
                toast(expired.message.orEmpty())
            } catch (failure: HostScreenSignInException) {
                if (store.current.origin != saved.origin || store.current.token != saved.token) return@launch
                if (failure.credentialsRejected) {
                    store.update(store.current.signedOut())
                    store.flush()
                    showHostScreenSignIn(failure.message.orEmpty())
                } else showError(failure.message.orEmpty()) { connect() }
            } catch (failure: Exception) {
                if (webView == null) showError(failure.message ?: "Cannot reach the server.") { connect() }
                else {
                    toast("Connection interrupted. Your sign-in is saved; use App settings > Refresh to reconnect.")
                }
            } finally { pageProgress.isVisible = false }
        }
    }

    private fun showSignIn(message: String = "") {
        connectionJob?.cancel()
        connectionPaused = false
        disposeWebView()
        pageProgress.isVisible = false
        val saved = store.current
        val form = formPage()
        form.addView(ProgressBar(this).apply { indeterminateTintList = ColorStateList.valueOf(primary) },
            LinearLayout.LayoutParams(dp(40), dp(40)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        form.addView(text("Checking server connection...", 22f, true), rowParams(20))
        form.addView(text(saved.origin, 15f, secondary = true), rowParams(12))
        connectionJob = lifecycleScope.launch {
            try {
                sessionClient.ping(saved.origin)
                if (store.current.origin != saved.origin || store.current.token != saved.token) return@launch
                showSignInOptions(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (store.current.origin != saved.origin || store.current.token != saved.token) return@launch
                showServerConnectionError(saved.origin) { showSignIn(message) }
            }
        }
    }

    private fun showServerConnectionError(origin: String, retry: () -> Unit) {
        val server = ServerAddress.parse(origin)
        val form = formPage()
        form.addView(text("Unable to connect to server", 24f, true))
        form.addView(text(origin, 16f).apply { setTextIsSelectable(true) }, rowParams(16))
        form.addView(text("Server name: ${server.url.host}\nProtocol: ${server.url.scheme.uppercase()}\nPort: ${server.url.port}",
            15f, secondary = true), rowParams(12))
        form.addView(text("Is this the correct server name? Check the address and your network connection. If it is correct, make sure the server is running and its certificate is trusted.", 16f), rowParams(16))
        form.addView(button("Try again", action = retry), rowParams(20))
        form.addView(button("Edit server address", true) {
            cancelSignIn()
            showServerSetup(origin)
        }, rowParams(12))
    }

    private fun showSignInOptions(message: String = "") {
        disposeWebView()
        pageProgress.isVisible = false
        val pending = store.current.pendingLogin
        if (pending != null && (pending.origin != store.current.origin ||
                System.currentTimeMillis() !in pending.createdAt..(pending.createdAt + BrowserAuth.LOGIN_LIFETIME_MS))) {
            cancelSignIn()
        }
        val waiting = store.current.pendingLogin != null
        val form = formPage()
        form.addView(text(if (waiting) "Finish sign-in in your browser" else "Sign in to karaoke", 24f, true))
        form.addView(text(store.current.origin, 15f, secondary = true), rowParams(12))
        form.addView(text(message.ifEmpty {
            if (waiting) "Complete the passkey prompt and choose Return to app. Closing the browser does not sign you in."
            else "Use your website passkey in your browser. You can also create an account there; approval is controlled by the server."
        }, 16f), rowParams(16))
        form.addView(button(if (waiting) "Cancel sign-in" else "Sign in with passkey") {
            if (waiting) { cancelSignIn(); showSignInOptions() } else beginBrowserLogin()
        }, rowParams(20))
        form.addView(button("Sign in as HOSTSCREEN", true) { cancelSignIn(); showHostScreenSignIn() }, rowParams(12))
        form.addView(button("Open party link", true) { promptJoinLink() }, rowParams(12))
        form.addView(text("Passkeys require browser app sign-in support (/app-login). HOSTSCREEN uses the server's shared login key.", 13f, secondary = true), rowParams(16))
    }

    private fun showHostScreenSignIn(message: String = "") {
        disposeWebView()
        pageProgress.isVisible = false
        val form = formPage()
        form.addView(text("Sign in as HOSTSCREEN", 24f, true))
        form.addView(text(store.current.origin, 15f, secondary = true), rowParams(12))
        form.addView(text(message.ifEmpty {
            "Enter the server's HOSTSCREEN login key to join an existing party as a playback screen. After successful sign-in, the key is saved securely for automatic sign-in. Switching user or server removes it."
        }, 16f), rowParams(16))
        val (field, input) = inputField("HOSTSCREEN login key", type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        input.isSaveEnabled = false
        input.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        input.imeOptions = EditorInfo.IME_ACTION_GO
        form.addView(field, rowParams(16))
        val signIn = button("Sign in") {
            val key = input.text.toString()
            if (key.isBlank()) field.error = "Enter the HOSTSCREEN login key."
            else {
                input.text?.clear()
                beginHostScreenLogin(key)
            }
        }
        form.addView(signIn, rowParams(20))
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) { signIn.performClick(); true } else false
        }
        form.addView(button("Back to sign-in options", true) { input.text?.clear(); showSignInOptions() }, rowParams(12))
    }

    private fun beginHostScreenLogin(key: String) {
        if (store.current.token.isNotEmpty() || signOutJob?.isActive == true || signInJob?.isActive == true) return
        cancelSignIn()
        connectionJob?.cancel()
        connectionPaused = false
        val origin = store.current.origin
        val generation = signInGeneration
        formPage().addView(text("Signing in as HOSTSCREEN...", 22f, true))
        pageProgress.isVisible = true
        signInJob = lifecycleScope.launch {
            try {
                val session = sessionClient.loginHostScreen(origin, key)
                if (generation != signInGeneration || store.current.origin != origin || store.current.token.isNotEmpty()) return@launch
                store.update(session.copy(theme = store.current.theme))
                store.flush()
                if (store.writeFailed) toast("Signed in for this launch, but Android could not securely save the session.")
                signInJob = null
                connect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == signInGeneration) {
                    showHostScreenSignIn(if (failure is HostScreenSignInException) failure.message.orEmpty()
                        else "HOSTSCREEN sign-in could not be completed. Please try again.")
                }
            } finally {
                if (generation == signInGeneration) {
                    signInJob = null
                    pageProgress.isVisible = false
                }
            }
        }
    }

    private fun beginBrowserLogin() {
        if (store.current.token.isNotEmpty() || signOutJob?.isActive == true || signInJob?.isActive == true) return
        connectionJob?.cancel()
        connectionPaused = false
        disposeWebView()
        try {
            val pending = BrowserAuth.begin(store.current.origin)
            store.update(store.current.copy(pendingLogin = pending, hostScreenKey = ""))
            store.flush()
            check(!store.writeFailed)
            showSignInOptions()
            CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(BrowserAuth.loginUrl(pending)))
        } catch (_: Exception) {
            cancelSignIn()
            showSignIn("Could not start sign-in. Use an HTTPS hostname, install a passkey-capable browser, and ensure Android can securely save the pending sign-in.")
        }
    }

    private fun cancelSignIn() {
        signInGeneration++
        signInJob?.cancel()
        signInJob = null
        store.update(store.current.copy(pendingLogin = null))
        store.flush()
    }

    private fun receiveBrowserCallback(incoming: Intent?): Boolean {
        if (incoming?.action != Intent.ACTION_VIEW || incoming.data?.scheme != "com.sskaraoke.player") return false
        val callback = incoming.dataString.orEmpty()
        setIntent(Intent(this, MainActivity::class.java))
        val pending = store.current.pendingLogin
        val authorization = try {
            require(store.current.token.isEmpty() && pending?.origin == store.current.origin)
            BrowserAuth.acceptCallback(callback, pending)
        } catch (_: Exception) {
            toast("Invalid or expired sign-in return. Use the sign-in started on this device.")
            if (store.current.origin.isEmpty()) showServerSetup() else connect()
            return true
        }
        connectionJob?.cancel()
        connectionPaused = false
        settingsDialog?.dismiss()
        disposeWebView()
        cancelSignIn()
        if (store.writeFailed) {
            showSignIn("Cannot securely finish sign-in. Please retry after device storage is available.")
            return true
        }
        val generation = signInGeneration
        formPage().addView(text("Completing sign-in...", 22f, true))
        pageProgress.isVisible = true
        signInJob = lifecycleScope.launch {
            try {
                val session = sessionClient.exchange(authorization)
                if (generation != signInGeneration || store.current.origin != authorization.origin ||
                    store.current.token.isNotEmpty()) return@launch
                store.update(session.copy(theme = store.current.theme, route = store.current.route))
                store.flush()
                if (store.writeFailed) toast("Signed in for this launch, but Android could not securely save the session.")
                signInJob = null
                connect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == signInGeneration) {
                    showSignIn("Sign-in could not be completed. Start a new sign-in; the server must support /app-login and /api/auth/app/token.")
                }
            } finally {
                if (generation == signInGeneration) {
                    signInJob = null
                    pageProgress.isVisible = false
                }
            }
        }
        return true
    }

    private fun openBrowserAccount() {
        val server = ServerAddress.parse(store.current.origin)
        if (!server.encrypted) {
            toast("Account and passkey management requires HTTPS.")
            return
        }
        try { CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(server.origin + "/account")) }
        catch (_: ActivityNotFoundException) { toast("Install a passkey-capable browser to manage your account.") }
    }

    private fun showBrowserAccount() {
        disposeWebView()
        store.update(store.current.copy(route = "/", view = "{}"))
        val form = formPage()
        form.addView(text("Account and passkeys", 24f, true))
        form.addView(text("Manage passkeys in your browser using the website's HTTPS origin. You may need to sign in separately there.", 16f), rowParams(16))
        form.addView(button("Open account in browser") { openBrowserAccount() }, rowParams(20))
        form.addView(button("Home", true) { connect() }, rowParams(12))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        if (store.current.route.trimEnd('/') == "/account") {
            showBrowserAccount()
            return
        }
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
                    if (request.url.path?.trimEnd('/') == "/account") {
                        showBrowserAccount()
                        return true
                    }
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
            "unavailable" -> showError("Session verification is temporarily unavailable. Your sign-in is saved.") { connect() }
            "expired" -> clearSession(store.current.expired())
            "logout" -> clearSession(store.current.signedOut().copy(
                route = store.current.route.takeIf { it.startsWith("/join/") } ?: "/"))
            "ready" -> webView?.evaluateJavascript("window.KaraokeAndroid?.applyTheme(${JSONObject.quote(store.current.theme)})", null)
            "haptic" -> webView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            "signin" -> if (store.current.token.isEmpty()) showSignIn()
            "account" -> showBrowserAccount()
        }
    }

    private fun navigate(route: String) {
        val server = ServerAddress.parse(store.current.origin)
        if (server.safeRoute(route).trimEnd('/') == "/account") { openBrowserAccount(); return }
        if (signInJob?.isActive == true) return
        if (store.current.pendingLogin != null) cancelSignIn()
        store.update(store.current.copy(route = server.safeRoute(route), view = "{}"))
        if (store.current.token.isEmpty() && !store.current.route.startsWith("/join/invite#invite=")) {
            showSignIn()
            return
        }
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
        val updateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val status = text(updateStatus, 15f)
        updateRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
        val check = button("Check for updates") { checkForUpdates(false) }
        check.id = View.generateViewId()
        updateRow.addView(check, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(updateRow, rowParams(4, 12))
        val indicator = LinearProgressIndicator(this).apply { max = 100; isVisible = false }
        body.addView(indicator, rowParams(0, 12))
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
        val identity = if (store.current.token.isEmpty()) "Not signed in"
            else store.current.memberName.takeIf { store.current.level == "guest" }
                ?.ifEmpty { "Guest" } ?: store.current.username.ifEmpty { store.current.memberName }.ifEmpty { "Signed in" }
        body.addView(text(identity, 15f), rowParams(8, 12))
        if (store.current.origin.isNotEmpty()) {
            val actions = GridLayout(this).apply { columnCount = 3 }
            actions.addView(button("Home", true) { dialog.dismiss(); navigate("/") })
            actions.addView(button("Refresh", true) { dialog.dismiss(); refreshPage() })
            if (store.current.token.isNotEmpty() && store.current.level in setOf("member", "admin")) {
                actions.addView(button("Account and passkeys", true) { dialog.dismiss(); openBrowserAccount() })
            }
            if (store.current.token.isNotEmpty()) {
                actions.addView(button("Switch user", true) {
                    confirm("Switch user?", "Sign out and revoke this session and its host invitations, then sign in with a passkey, HOSTSCREEN key, or invitation?", "Switch user") {
                        dialog.dismiss(); signOut()
                    }
                })
            }
            actions.addView(button("Open party link", true) { promptJoinLink(dialog) })
            if (store.current.level == "admin") actions.addView(button("Server administration", true) {
                dialog.dismiss(); navigate("/settings")
            })
            actions.addView(button("Open in browser", true) { openExternal(store.current.origin + store.current.route, false) })
            actions.addView(button("Change server", true) {
                confirm("Change server?", "Sign out of this server and revoke this session and its host invitations before choosing another server?", "Change server") {
                    dialog.dismiss()
                    signOut(changeServer = true)
                }
            })
            actions.addView(button("Sign out", true) {
                confirm("Sign out?", "Revoke this session and its host invitations and remove the saved login? Your party and its queue stay on the server.", "Sign out") {
                    dialog.dismiss()
                    signOut()
                }
            })
            for (index in 0 until actions.childCount) {
                val action = actions.getChildAt(index) as MaterialButton
                action.minWidth = 0
                action.setPadding(dp(8), action.paddingTop, dp(8), action.paddingBottom)
                action.layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / 3, GridLayout.FILL), GridLayout.spec(index % 3, 1f)
                ).apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    topMargin = dp(8)
                    marginEnd = if (index % 3 < 2) dp(8) else 0
                }
            }
            body.addView(actions, rowParams())
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
        val (field, input) = inputField("Host invitation link", prefill)
        val wrapper = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(field, rowParams()) }
        val dialog = alertBuilder().setTitle("Join a party").setView(wrapper)
            .setNegativeButton("Cancel", null).setPositiveButton("Open", null).create()
        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                val server = ServerAddress.parse(store.current.origin)
                val route = server.safeRoute(value)
                if (!Regex("/join/invite#invite=[A-Za-z0-9_-]{43}").matches(route)) field.error = "Enter a current host invitation link from the selected server."
                else { dialog.dismiss(); parent?.dismiss(); navigate(route) }
            }
        }
        dialog.show()
        themeDialog(dialog)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!receiveBrowserCallback(intent)) receiveSharedLink(intent)
    }

    private fun receiveSharedLink(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val value = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.take(2048) ?: return
        val link = Regex("https?://\\S+").find(value)?.value ?: value
        if (store.current.origin.isEmpty()) showServerSetup(link) else promptJoinLink(prefill = link)
    }

    private fun signOut(changeServer: Boolean = false) {
        if (signOutJob?.isActive == true) return
        cancelSignIn()
        connectionJob?.cancel()
        connectionJob = null
        connectionPaused = false
        sessionClient.cancel()
        val saved = store.current
        disposeWebView()
        formPage().addView(text("Signing out...", 20f, true))
        pageProgress.isVisible = true
        signOutJob = lifecycleScope.launch {
            try {
                sessionClient.logout(saved)
                if (store.current.origin == saved.origin && store.current.token == saved.token) {
                    signOutJob = null
                    clearSession(if (changeServer) SavedSession(theme = store.current.theme) else store.current.signedOut())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showError("Sign-out could not be confirmed. Your login is saved. Retry to revoke this session and its invitations.") { signOut(changeServer) }
            } finally { pageProgress.isVisible = false }
        }
    }

    private fun clearSession(replacement: SavedSession) {
        cancelSignIn()
        connectionPaused = false
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
        if (connectionPaused) {
            connectionPaused = false
            connect()
        }
        if (store.current.pendingLogin != null && signInJob?.isActive != true && connectionJob?.isActive != true) showSignIn()
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
        if (signInJob?.isActive == true) {
            cancelSignIn()
            sessionClient.cancel()
            connectionPaused = true
            pageProgress.isVisible = false
            formPage().addView(text("Sign-in was interrupted. Start a new sign-in; the previous request will not be retried.", 20f, true))
        }
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

    override fun onPause() {
        (window.callback as? PointerNavigation)?.reset()
        settingsDialog?.window?.let { (it.callback as? PointerNavigation)?.reset() }
        super.onPause()
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
        dialog.window?.let {
            tintView(it.decorView)
            themeSystemBars(it)
            PointerNavigation.install(it)
        }
    }

    private fun applyNativeTheme() {
        root.setBackgroundColor(surface)
        webView?.setBackgroundColor(surface)
        settingsButton.background = RippleDrawable(ColorStateList.valueOf(primary),
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(card) }, null)
        settingsButton.imageTintList = ColorStateList.valueOf(primary)
        tintView(column)
        themeSystemBars(window)
        settingsDialog?.let { dialog ->
            dialog.window?.decorView?.let {
                it.setBackgroundColor(surface)
                it.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)?.setBackgroundColor(surface)
            }
            themeDialog(dialog)
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
                view.setBoxStrokeColorStateList(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(primary, muted)))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) view.setCursorColor(ColorStateList.valueOf(primary))
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
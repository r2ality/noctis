/*
 * Noctis - lightweight, privacy-first Android browser
 * Copyright (C) 2026 Noctis contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.noctis.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.noctis.browser.core.BrowserTab
import dev.noctis.browser.privacy.PrivacyWebViewClient
import dev.noctis.browser.privacy.RequestFilter
import dev.noctis.browser.settings.Bookmark
import dev.noctis.browser.settings.BrowserSettings
import dev.noctis.browser.settings.SearchEngine
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    lateinit var settings: BrowserSettings
        private set

    private val requestFilter by lazy { RequestFilter(settings) }
    private val tabs = mutableListOf<BrowserTab>()
    private var currentTabId = -1L
    private var nextTabId = 1L

    private lateinit var root: LinearLayout
    private lateinit var contentFrame: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var homePanel: LinearLayout
    private lateinit var settingsPanel: ScrollView
    private lateinit var addressBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var tabButton: Button
    private lateinit var menuButton: Button
    private var showingSettings = false
    private var showingHome = true

    private var bg = Color.rgb(0, 0, 0)
    private var surface = Color.rgb(16, 16, 16)
    private var surfaceRaised = Color.rgb(26, 26, 26)
    private var primary = Color.rgb(242, 242, 242)
    private var secondary = Color.rgb(166, 166, 166)
    private var accent = Color.rgb(189, 189, 189)
    private var outline = Color.rgb(76, 76, 76)

    private data class ThemePalette(
        val bg: Int,
        val surface: Int,
        val surfaceRaised: Int,
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val outline: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedTheme = getSharedPreferences("noctis_settings", Context.MODE_PRIVATE)
            .getString("theme_mode", BrowserSettings.THEME_AMOLED)
        setTheme(if (savedTheme == BrowserSettings.THEME_LIGHT) R.style.NoctisTheme_Light else R.style.NoctisTheme)
        super.onCreate(savedInstanceState)
        try {
            settings = BrowserSettings(this)
            applyThemePalette(updateViews = false)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WebView.setWebContentsDebuggingEnabled(false)

            buildLayout()
            // Apply system-bar styling only after setContentView has created the window decor.
            // On Android 11+, Window.insetsController may dereference a not-yet-created DecorView.
            root.post { updateSystemBars() }
            // A missing or broken system WebView provider must not crash the whole browser UI.
            if (createTab() == null) return

            val incoming = intent?.data?.takeIf { it.scheme == "http" || it.scheme == "https" }
            if (settings.privateSessionWasInterrupted()) {
                // A force-stop can skip Activity.onDestroy; do not load a new page until stale session data is gone.
                CookieManager.getInstance().setAcceptCookie(false)
                clearBrowsingData {
                    settings.setPrivateSession(false)
                    applyCookiePolicy()
                    Toast.makeText(this, "Recovered and cleared an interrupted private session", Toast.LENGTH_LONG).show()
                    if (incoming != null) navigateTo(incoming.toString()) else showHome()
                }
            } else {
                if (incoming != null) navigateTo(incoming.toString()) else showHome()
            }
        } catch (error: RuntimeException) {
            showStartupFailure(error)
        }
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            fitsSystemWindows = false
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }

        // One compact Chrome-inspired toolbar: navigation, address/search, new tab,
        // tab count and the overflow menu. There is intentionally no bottom bar.
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(6), dp(7), dp(6))
            setBackgroundColor(bg)
        }
        val backButton = toolbarButton("‹", "Go back") {
            currentTab()?.webView?.let { if (it.canGoBack()) it.goBack() }
        }
        toolbar.addView(backButton, LinearLayout.LayoutParams(dp(36), dp(42)))

        val forwardButton = toolbarButton("›", "Go forward") {
            currentTab()?.webView?.let { if (it.canGoForward()) it.goForward() }
        }
        toolbar.addView(forwardButton, LinearLayout.LayoutParams(dp(36), dp(42)).apply {
            leftMargin = dp(2)
        })

        addressBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(primary)
            setHintTextColor(secondary)
            hint = "Search privately or enter address"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), 0, dp(10), 0)
            background = rounded(surfaceRaised, 24)
            setSelectAllOnFocus(true)
            maxLines = 1
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    navigateTo(addressBar.text.toString())
                    true
                } else false
            }
        }
        toolbar.addView(addressBar, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            leftMargin = dp(5)
            rightMargin = dp(5)
        })

        val newTabButton = toolbarButton("+", "Open a new tab") {
            if (createTab() != null) showHome()
        }.apply { textSize = 25f }
        toolbar.addView(newTabButton, LinearLayout.LayoutParams(dp(38), dp(42)))

        tabButton = textButton("1").apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            contentDescription = "Open tabs"
            background = tabBadgeBackground()
            setOnClickListener { showTabsDialog() }
        }
        toolbar.addView(tabButton, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            leftMargin = dp(4)
        })

        menuButton = toolbarButton("⋮", "Browser menu") {
            showBrowserMenu(menuButton)
        }.apply { textSize = 23f }
        toolbar.addView(menuButton, LinearLayout.LayoutParams(dp(34), dp(42)).apply {
            leftMargin = dp(2)
        })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(54)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(surface)
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))

        contentFrame = FrameLayout(this).apply { setBackgroundColor(bg) }
        webContainer = FrameLayout(this)
        contentFrame.addView(webContainer, FrameLayout.LayoutParams(-1, -1))
        homePanel = buildHomePanel()
        contentFrame.addView(homePanel, FrameLayout.LayoutParams(-1, -1))
        settingsPanel = ScrollView(this).apply {
            visibility = View.GONE
            setFillViewport(true)
            setBackgroundColor(bg)
            isVerticalScrollBarEnabled = false
        }
        contentFrame.addView(settingsPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(contentFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        updateToolbarState()
    }

    private fun toolbarButton(label: String, description: String, action: () -> Unit): Button =
        textButton(label).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            contentDescription = description
            background = rounded(surface, 12)
            setOnClickListener { action() }
        }

    private fun tabBadgeBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(surface)
        setStroke(dp(1), outline)
        cornerRadius = dp(9).toFloat()
    }

    private fun buildHomePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(bg)
        }
        val emblem = ImageView(this).apply {
            setImageResource(dev.noctis.browser.R.drawable.ic_noctis)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        panel.addView(emblem, LinearLayout.LayoutParams(dp(76), dp(76)))
        val title = TextView(this).apply {
            text = "Browse quietly."
            textSize = 25f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primary)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(4))
        }
        panel.addView(title)
        val subtitle = TextView(this).apply {
            text = "No history database. No unnecessary services.\nYour settings stay on this device."
            textSize = 14f
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        panel.addView(subtitle)

        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(28), 0, 0)
        }
        shortcuts.addView(shortcut("DuckDuckGo", "Search without a Google account") {
            navigateTo("https://duckduckgo.com/")
        })
        shortcuts.addView(shortcut("Brave Search", "An independent search provider") {
            navigateTo("https://search.brave.com/")
        })
        shortcuts.addView(shortcut("Open bookmarks", "Saved locally on this device") {
            showBookmarksDialog()
        })
        panel.addView(shortcuts, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        val note = TextView(this).apply {
            text = "JavaScript is blocked on new sites until you allow it. Open the menu to allow JavaScript for the current site."
            textSize = 12f
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(28), dp(8), 0)
        }
        panel.addView(note)
        return panel
    }

    private fun shortcut(title: String, description: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(surface, 18)
            setOnClickListener { action() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(primary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        row.addView(TextView(this).apply {
            text = description
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, dp(3), 0, 0)
        })
        val params = LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = dp(9)
        row.layoutParams = params
        return row
    }

    private fun textButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(primary)
        background = rounded(surface, 16)
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun createTab(): BrowserTab? {
        val id = nextTabId++
        val webView = try {
            WebView(this)
        } catch (error: RuntimeException) {
            showWebViewUnavailable(error)
            return null
        }

        try {
            webView.setBackgroundColor(bg)
            configureWebView(webView, id)
            webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))
            webView.visibility = View.GONE
        } catch (error: RuntimeException) {
            runCatching { webView.stopLoading(); webView.removeAllViews(); webView.destroy() }
            showWebViewUnavailable(error)
            return null
        }

        val tab = BrowserTab(id, webView)
        tabs.add(tab)
        currentTabId = id
        updateTabButton()
        return tab
    }

    private fun webViewProviderDescription(): String {
        return try {
            val provider = if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
            provider?.let { "${it.packageName} (${it.versionName})" } ?: "No compatible provider reported"
        } catch (error: RuntimeException) {
            "Provider lookup failed: ${error.javaClass.simpleName}"
        }
    }

    private fun showWebViewUnavailable(error: RuntimeException) {
        val detail = "${error.javaClass.simpleName}: ${error.message ?: "No additional error details"}"
        android.util.Log.e("NoctisStartup", "Android System WebView could not be initialized", error)
        if (!::homePanel.isInitialized || !::contentFrame.isInitialized) {
            showStartupFailure(error)
            return
        }

        homePanel.removeAllViews()
        homePanel.gravity = Gravity.CENTER
        homePanel.setPadding(dp(28), dp(24), dp(28), dp(24))
        homePanel.addView(TextView(this).apply {
            text = "No compatible WebView"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(primary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        homePanel.addView(TextView(this).apply {
            text = "Noctis uses the Android System WebView installed by your ROM. It could not be started. Check Settings → Developer options → WebView implementation and make sure a supported provider is installed and enabled."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(secondary)
            setPadding(0, dp(12), 0, dp(16))
        })
        homePanel.addView(TextView(this).apply {
            text = "Provider: ${webViewProviderDescription()}\nError: $detail"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(secondary)
            setTextIsSelectable(true)
        })
        homePanel.addView(textButton("Retry").apply {
            setOnClickListener { retryWebViewInitialization() }
        }, LinearLayout.LayoutParams(dp(132), dp(46)).apply { topMargin = dp(20) })

        showingSettings = false
        showingHome = true
        webContainer.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        settingsPanel.visibility = View.GONE
        progress.visibility = View.GONE
        updateTabButton()
    }

    private fun retryWebViewInitialization() {
        contentFrame.removeView(homePanel)
        homePanel = buildHomePanel()
        contentFrame.addView(homePanel, 1, FrameLayout.LayoutParams(-1, -1))
        if (createTab() != null) showHome()
    }

    private fun showStartupFailure(error: RuntimeException) {
        android.util.Log.e("NoctisStartup", "Noctis failed during startup", error)
        val detail = "${error.javaClass.name}\n${error.message ?: "No additional error details"}"
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(bg)
        }
        panel.addView(TextView(this).apply {
            text = "Noctis could not start"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(primary)
        })
        panel.addView(TextView(this).apply {
            text = detail
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(secondary)
            setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        })
        setContentView(panel)
    }

    private fun configureWebView(webView: WebView, tabId: Long) {
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = false
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = false
        webSettings.allowFileAccess = false
        webSettings.allowContentAccess = false
        @Suppress("DEPRECATION")
        webSettings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        webSettings.allowUniversalAccessFromFileURLs = false
        webSettings.javaScriptCanOpenWindowsAutomatically = false
        webSettings.setSupportMultipleWindows(true)
        webSettings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webSettings.cacheMode = if (settings.privateSession || !settings.diskCacheEnabled) android.webkit.WebSettings.LOAD_NO_CACHE else android.webkit.WebSettings.LOAD_DEFAULT
        webSettings.setGeolocationEnabled(false)
        webSettings.setSaveFormData(false)
        if (android.os.Build.VERSION.SDK_INT >= 26) webSettings.setSafeBrowsingEnabled(settings.safeBrowsing)
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        CookieManager.getInstance().setAcceptCookie(!settings.privateSession && settings.allowFirstPartyCookies)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !settings.privateSession && !settings.blockThirdPartyCookies)

        webView.webViewClient = PrivacyWebViewClient(this, requestFilter, tabId)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tabId == currentTabId && !showingSettings && !showingHome) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tabs.firstOrNull { it.id == tabId }?.title = title?.take(120) ?: "Noctis"
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                // Camera, microphone, protected media and other web permissions are denied by default.
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (settings.blockPopups || !isUserGesture || resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val popupTab = createTab() ?: return false
                transport.webView = popupTab.webView
                resultMsg.sendToTarget()
                showBrowser()
                return true
            }
        }

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            confirmDownload(url, userAgent, contentDisposition, mimeType)
        })
    }

    fun handleWebNavigation(tabId: Long, rawUrl: String, isMainFrame: Boolean): Boolean {
        val uri = try { Uri.parse(rawUrl) } catch (_: Exception) { return true }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return true
        if (scheme == "http" || scheme == "https") {
            if (!isMainFrame) return false
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            val tab = tabs.firstOrNull { it.id == tabId } ?: return true
            tab.webView.settings.javaScriptEnabled = settings.javascriptAllowed(host)
            if (scheme == "http" && settings.httpsOnly && !isLocalHost(host)) {
                val upgraded = uri.buildUpon().scheme("https").build().toString()
                tab.webView.loadUrl(upgraded)
                return true
            }
            return false
        }
        if (scheme in setOf("mailto", "tel", "sms", "geo")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: Exception) {
                showSecurityMessage("No app can open that link.")
            }
            return true
        }
        if (scheme == "about" && rawUrl == "about:blank") return false
        return true
    }

    private fun isLocalHost(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".localhost")

    private fun navigateTo(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        var candidate = text
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(candidate)
        if (!hasScheme) {
            candidate = if (candidate.contains(' ') || !candidate.contains('.') && !candidate.startsWith("localhost")) {
                settings.selectedSearchEngine().searchUrl(candidate)
            } else {
                "https://$candidate"
            }
        }
        val uri = try { Uri.parse(candidate) } catch (_: Exception) { null }
        val scheme = uri?.scheme?.lowercase(Locale.ROOT)
        if (uri == null || (scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            Toast.makeText(this, "Enter a web address or search terms", Toast.LENGTH_SHORT).show()
            return
        }
        val host = uri.host!!.lowercase(Locale.ROOT)
        val target = if (uri.scheme.equals("http", true) && settings.httpsOnly && !isLocalHost(host)) {
            uri.buildUpon().scheme("https").build().toString()
        } else candidate
        val tab = currentTab() ?: createTab() ?: return
        tab.webView.settings.javaScriptEnabled = settings.javascriptAllowed(host)
        tab.url = target
        tab.webView.loadUrl(target)
        showBrowser()
        addressBar.clearFocus()
        addressBar.setText(target)
        hideKeyboard()
    }

    private fun currentTab(): BrowserTab? = tabs.firstOrNull { it.id == currentTabId }

    fun onPageStarted(tabId: Long, url: String) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        val startedUri = try { Uri.parse(url) } catch (_: Exception) { null }
        if (settings.httpsOnly && startedUri?.scheme?.equals("http", true) == true) {
            val host = startedUri?.host.orEmpty()
            if (!isLocalHost(host)) {
                tab.webView.loadUrl(startedUri!!.buildUpon().scheme("https").build().toString())
                return
            }
        }
        tab.webView.settings.javaScriptEnabled = settings.javascriptAllowed(startedUri?.host)
        tab.url = url
        if (tabId == currentTabId) {
            showingHome = false
            updateAddress(url)
            if (showingSettings) showBrowser()
        }
    }

    fun onPageFinished(tabId: Long, url: String, title: String) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        tab.url = url
        if (title.isNotBlank()) tab.title = title.take(120)
        if (tabId == currentTabId) {
            updateAddress(url)
            progress.visibility = View.GONE
        }
    }

    fun onWebViewRendererGone(tabId: Long) {
        val tab = tabs.firstOrNull { it.id == tabId } ?: return
        webContainer.removeView(tab.webView)
        tab.webView.destroy()
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            if (createTab() == null) return
        } else currentTabId = tabs.last().id
        Toast.makeText(this, "A page process stopped unexpectedly. The tab was closed.", Toast.LENGTH_LONG).show()
        if (currentTab()?.url.isNullOrBlank()) showHome() else showBrowser()
        updateTabButton()
    }

    fun showSecurityMessage(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun showBrowser() {
        showingSettings = false
        showingHome = false
        homePanel.visibility = View.GONE
        settingsPanel.visibility = View.GONE
        webContainer.visibility = View.VISIBLE
        tabs.forEach { it.webView.visibility = if (it.id == currentTabId) View.VISIBLE else View.GONE }
        updateCurrentUrl()
        updateToolbarState()
    }

    private fun showHome() {
        showingSettings = false
        showingHome = true
        webContainer.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        settingsPanel.visibility = View.GONE
        progress.visibility = View.GONE
        addressBar.setText("")
        updateToolbarState()
    }

    private fun showSettings() {
        showingSettings = true
        showingHome = false
        webContainer.visibility = View.GONE
        homePanel.visibility = View.GONE
        settingsPanel.visibility = View.VISIBLE
        rebuildSettingsPanel()
        updateToolbarState()
    }

    private fun updateCurrentUrl() {
        val url = currentTab()?.url.orEmpty()
        updateAddress(url)
    }

    private fun updateAddress(url: String) {
        if (!addressBar.hasFocus()) addressBar.setText(url)
    }

    private fun updateTabButton() {
        if (::tabButton.isInitialized) {
            tabButton.text = tabs.size.coerceAtMost(99).toString()
            tabButton.contentDescription = "${tabs.size} open tabs"
        }
    }

    private fun updateToolbarState() {
        updateTabButton()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun togglePrivateSession() {
        val entering = !settings.privateSession
        if (entering) {
            settings.setPrivateSession(true)
            applyCookiePolicy()
            tabs.forEach { it.webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE }
        }
        clearBrowsingData {
            if (!entering) settings.setPrivateSession(false)
            applyCookiePolicy()
            tabs.forEach { tab ->
                tab.webView.settings.cacheMode = if (entering || !settings.diskCacheEnabled) android.webkit.WebSettings.LOAD_NO_CACHE else android.webkit.WebSettings.LOAD_DEFAULT
            }
            updateToolbarState()
            Toast.makeText(
                this,
                if (entering) "Private session started. Cookies are disabled; temporary site data is cleared when it ends." else "Private session ended. Temporary site data was cleared.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun applyCookiePolicy() {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(!settings.privateSession && settings.allowFirstPartyCookies)
        tabs.forEach { tab ->
            manager.setAcceptThirdPartyCookies(tab.webView, !settings.privateSession && !settings.blockThirdPartyCookies)
            tab.webView.settings.setSafeBrowsingEnabled(settings.safeBrowsing)
        }
    }

    private fun clearBrowsingData(onDone: (() -> Unit)? = null) {
        tabs.forEach { tab ->
            tab.webView.clearCache(true)
            tab.webView.clearHistory()
            tab.webView.clearFormData()
        }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            onDone?.let { callback -> runOnUiThread { callback() } }
        }
    }

    private fun confirmDownload(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        val uri = try { Uri.parse(url) } catch (_: Exception) { return }
        if (uri.scheme != "http" && uri.scheme != "https") return
        val title = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        AlertDialog.Builder(this)
            .setTitle("Download file?")
            .setMessage(title)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download") { _, _ ->
                try {
                    val request = DownloadManager.Request(uri)
                        .setTitle(title)
                        .setDescription("Downloading with Noctis")
                        .setMimeType(mimeType)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, title)
                    if (userAgent.isNotBlank()) request.addRequestHeader("User-Agent", userAgent)
                    val cookie = CookieManager.getInstance().getCookie(url)
                    if (!cookie.isNullOrBlank() && !settings.privateSession) request.addRequestHeader("Cookie", cookie)
                    val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                    Toast.makeText(this, "Download queued", Toast.LENGTH_SHORT).show()
                } catch (error: Exception) {
                    showSecurityMessage("Download could not be started: ${error.message}")
                }
            }
            .show()
    }

    private fun showBrowserMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, 1, 1, "New tab")
        popup.menu.add(Menu.NONE, 2, 2, "Bookmark this page")
        popup.menu.add(Menu.NONE, 3, 3, "Bookmarks")
        popup.menu.add(Menu.NONE, 4, 4, "Allow JavaScript for this site")
        popup.menu.add(Menu.NONE, 5, 5, "Find in page")
        popup.menu.add(Menu.NONE, 6, 6, "Share page")
        popup.menu.add(Menu.NONE, 7, 7, "Reload")
        popup.menu.add(Menu.NONE, 8, 8, "Clear browsing data")
        popup.menu.add(Menu.NONE, 9, 9, "Settings")
        popup.menu.add(Menu.NONE, 10, 10, if (settings.privateSession) "End private session" else "Start private session")
        popup.menu.add(Menu.NONE, 11, 11, "About & license")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { if (createTab() != null) showHome(); true }
                2 -> { bookmarkCurrentPage(); true }
                3 -> { showBookmarksDialog(); true }
                4 -> { toggleJavascriptForCurrentSite(); true }
                5 -> { showFindDialog(); true }
                6 -> { shareCurrentPage(); true }
                7 -> { currentTab()?.webView?.reload(); showBrowser(); true }
                8 -> { confirmClearData(); true }
                9 -> { showSettings(); true }
                10 -> { togglePrivateSession(); true }
                11 -> { showAboutDialog(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun bookmarkCurrentPage() {
        val tab = currentTab() ?: return
        if (tab.url.isBlank()) {
            Toast.makeText(this, "There is no page to bookmark", Toast.LENGTH_SHORT).show()
            return
        }
        settings.addBookmark(tab.url, tab.title)
        Toast.makeText(this, "Bookmark saved on this device", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarksDialog() {
        val bookmarks = settings.bookmarks()
        if (bookmarks.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Bookmarks").setMessage("No bookmarks saved yet.")
                .setPositiveButton("OK", null).show()
            return
        }
        val labels = bookmarks.map { it.title }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Bookmarks").setItems(labels) { _, which ->
            navigateTo(bookmarks[which].url)
        }.setNeutralButton("Manage") { _, _ -> showBookmarkManager(bookmarks) }
            .setNegativeButton("Close", null).show()
    }

    private fun showBookmarkManager(bookmarks: List<Bookmark>) {
        val labels = bookmarks.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Remove a bookmark").setItems(labels) { _, which ->
            settings.removeBookmark(bookmarks[which].url)
            Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Close", null).show()
    }

    private fun showTabsDialog() {
        if (tabs.isEmpty()) return
        val labels = tabs.mapIndexed { index, tab ->
            val selected = if (tab.id == currentTabId) "● " else ""
            "$selected${index + 1}. ${tab.title.ifBlank { "New tab" }}"
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Open tabs · ${tabs.size}")
            .setItems(labels) { _, which ->
                currentTabId = tabs[which].id
                if (tabs[which].url.isBlank()) showHome() else showBrowser()
            }
            .setNeutralButton("New tab") { _, _ -> if (createTab() != null) showHome() }
            .setPositiveButton("Close current") { _, _ -> closeCurrentTab() }
            .setNegativeButton("Done", null).show()
    }

    private fun closeCurrentTab() {
        val tab = currentTab() ?: return
        webContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        tabs.remove(tab)
        if (tabs.isEmpty()) {
            if (createTab() == null) return
        } else currentTabId = tabs.last().id
        if (currentTab()?.url.isNullOrBlank()) showHome() else showBrowser()
        updateTabButton()
    }

    private fun toggleJavascriptForCurrentSite() {
        val tab = currentTab() ?: return
        val host = try { Uri.parse(tab.url).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
        if (host.isNullOrBlank()) {
            Toast.makeText(this, "Open a website first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!settings.scriptGateEnabled || settings.javascriptEverywhere) {
            Toast.makeText(this, "Enable the NoScript-style gate to manage per-site JavaScript exceptions", Toast.LENGTH_LONG).show()
            return
        }
        val allowed = !settings.javascriptAllowed(host)
        settings.setJavascriptAllowed(host, allowed)
        tab.webView.settings.javaScriptEnabled = settings.javascriptAllowed(host)
        tab.webView.reload()
        Toast.makeText(this, if (allowed) "JavaScript allowed for $host" else "JavaScript blocked for $host", Toast.LENGTH_LONG).show()
    }

    private fun showFindDialog() {
        val input = EditText(this).apply { hint = "Find text"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Find in page").setView(input)
            .setPositiveButton("Find") { _, _ ->
                currentTab()?.webView?.findAllAsync(input.text.toString())
            }.setNegativeButton("Cancel", null).show()
    }

    private fun shareCurrentPage() {
        val tab = currentTab() ?: return
        if (tab.url.isBlank()) return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, tab.url)
        }
        startActivity(Intent.createChooser(share, "Share page"))
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this).setTitle("Clear browsing data?")
            .setMessage("This clears WebView cookies, cache, site storage and in-memory page history. Your saved bookmarks and settings remain.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                clearBrowsingData { Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show() }
            }.show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.data?.let { if (it.scheme == "http" || it.scheme == "https") navigateTo(it.toString()) }
    }

    @Deprecated("Use system back navigation")
    override fun onBackPressed() {
        when {
            showingSettings -> if (currentTab()?.url.isNullOrBlank()) showHome() else showBrowser()
            showingHome -> super.onBackPressed()
            currentTab()?.webView?.canGoBack() == true -> currentTab()?.webView?.goBack()
            else -> showHome()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::settings.isInitialized && !settings.privateSession) CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (::settings.isInitialized && isFinishing && (settings.clearOnExit || settings.privateSession)) {
            // Best-effort cleanup on a normal Activity finish; Android may kill a process without callbacks.
            tabs.forEach {
                it.webView.clearCache(true)
                it.webView.clearHistory()
                it.webView.clearFormData()
            }
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }

        // WebView holds native resources; explicitly detach and destroy every tab when
        // the Activity goes away instead of leaving renderer-backed views referenced.
        tabs.toList().forEach { tab ->
            tab.webView.stopLoading()
            tab.webView.onPause()
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
            tab.webView.removeAllViews()
            tab.webView.destroy()
        }
        tabs.clear()
        super.onDestroy()
    }

    private fun rebuildSettingsPanel() {
        settingsPanel.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(bg)
        }
        page.addView(TextView(this).apply {
            text = "Settings"
            textSize = 28f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primary)
        })
        page.addView(TextView(this).apply {
            text = "Configure Noctis locally. Changes apply without rebuilding the app."
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(5), 0, dp(18))
        })

        addSettingsSection(page, "APPEARANCE")
        addSettingsAction(
            page,
            "Theme",
            if (settings.themeMode == BrowserSettings.THEME_LIGHT) "Light" else "AMOLED black",
            "Change"
        ) { showThemePicker() }

        addSettingsSection(page, "SECURITY & CONNECTION")
        addSettingsSwitch(page, "HTTPS-only mode", "Upgrade typed HTTP links and block insecure HTTP subresources.",
            { settings.httpsOnly }, { settings.httpsOnly = it; applySettingsToTabs() })
        addSettingsSwitch(page, "Safe Browsing", "Use the Android WebView provider's phishing and malware checks when supported.",
            { settings.safeBrowsing }, { settings.safeBrowsing = it; applySettingsToTabs() })
        addSettingsSwitch(page, "Allow first-party cookies", "Off by default. Some sign-in and preference features require first-party cookies.",
            { settings.allowFirstPartyCookies }, { settings.allowFirstPartyCookies = it; applyCookiePolicy() })
        addSettingsSwitch(page, "Allow on-disk web cache", "Off by default to avoid retaining cached page resources between launches.",
            { settings.diskCacheEnabled }, { settings.diskCacheEnabled = it; applySettingsToTabs() })
        addSettingsSwitch(page, "Block third-party cookies", "Prevent cookies from unrelated embedded sites.",
            { settings.blockThirdPartyCookies }, { settings.blockThirdPartyCookies = it; applyCookiePolicy() })
        addSettingsSwitch(page, "Clear site data on exit", "Best-effort cleanup of cookies, cache and site storage when Noctis is normally closed.",
            { settings.clearOnExit }, { settings.clearOnExit = it })

        addSettingsSection(page, "BUILT-IN EXTENSIONS")
        addSettingsSwitch(page, "Tracker & ad shield", "Block a small built-in list of known advertising and tracking hosts.",
            { settings.blockTrackers }, { settings.blockTrackers = it })
        addSettingsSwitch(page, "NoScript-style script gate", "JavaScript starts blocked. Allow trusted sites individually or enable it globally below.",
            { settings.scriptGateEnabled }, { settings.scriptGateEnabled = it; applySettingsToTabs() })
        addSettingsSwitch(page, "Allow JavaScript on all sites", "Overrides the per-site script gate. This improves compatibility but increases attack surface.",
            { settings.javascriptEverywhere }, { settings.javascriptEverywhere = it; applySettingsToTabs() })
        addSettingsSwitch(page, "Block pop-up windows", "Reject unsolicited window.open requests. Turn off to allow user-initiated pop-up tabs.",
            { settings.blockPopups }, { settings.blockPopups = it; applySettingsToTabs() })
        addSettingsAction(page, "Per-site JavaScript exceptions", "Review sites allowed to run scripts", "Manage") {
            showJavascriptSitesDialog()
        }
        addSettingsAction(page, "Custom blocked hosts", "Add domains to the local tracker/block list", "Edit") {
            showCustomHostsDialog()
        }

        addSettingsSection(page, "SEARCH")
        addSettingsAction(page, "Default search engine", settings.selectedSearchEngine().name, "Choose") {
            showSearchEnginePicker()
        }
        addSettingsAction(page, "Manage search engines", "Add a provider with a %s query placeholder", "Manage") {
            showSearchEngineManager()
        }

        addSettingsSection(page, "STORAGE & SESSION")
        addSettingsAction(page, "Clear browsing data now", "Remove cookies, cache, site storage and page history", "Clear") {
            confirmClearData()
        }
        addSettingsAction(page, "Bookmarks", "Bookmarks are stored locally; browsing history is not saved", "Open") {
            showBookmarksDialog()
        }
        addSettingsAction(page, "About Noctis", "Version 0.2.1 · Kotlin · Android System WebView · AGPL-3.0", "View") {
            showAboutDialog()
        }

        page.addView(TextView(this).apply {
            text = "Privacy note: WebView's built-in request callbacks do not observe every network path (including all service-worker and WebSocket traffic). Host filtering is useful but is not a complete network firewall. Use a trusted system-level DNS/VPN filter for stronger coverage."
            textSize = 12f
            setTextColor(secondary)
            setPadding(dp(4), dp(20), dp(4), dp(12))
        })
        settingsPanel.addView(page)
    }

    private fun addSettingsSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 11f
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accent)
            setPadding(dp(4), dp(18), dp(4), dp(8))
        })
    }

    private fun addSettingsSwitch(
        parent: LinearLayout,
        title: String,
        description: String,
        checked: () -> Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = rounded(surface, 16)
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primary)
        })
        textColumn.addView(TextView(this).apply {
            text = description
            textSize = 11f
            setTextColor(secondary)
            setPadding(0, dp(3), dp(6), 0)
        })
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = checked()
            setTextColor(primary)
            showText = false
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        row.addView(toggle)
        val params = LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = dp(7)
        parent.addView(row, params)
    }

    private fun addSettingsAction(
        parent: LinearLayout,
        title: String,
        description: String,
        actionLabel: String,
        action: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(10), dp(13))
            background = rounded(surface, 16)
            setOnClickListener { action() }
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(primary)
        })
        textColumn.addView(TextView(this).apply {
            text = description
            textSize = 11f
            setTextColor(secondary)
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = actionLabel
            textSize = 12f
            setTextColor(accent)
            setPadding(dp(8), dp(6), dp(4), dp(6))
        })
        val params = LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = dp(7)
        parent.addView(row, params)
    }

    private fun applySettingsToTabs() {
        tabs.forEach { tab ->
            val host = try { Uri.parse(tab.url).host } catch (_: Exception) { null }
            tab.webView.settings.javaScriptEnabled = settings.javascriptAllowed(host)
            tab.webView.settings.cacheMode = if (settings.privateSession || !settings.diskCacheEnabled) android.webkit.WebSettings.LOAD_NO_CACHE else android.webkit.WebSettings.LOAD_DEFAULT
            tab.webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            tab.webView.settings.setSupportMultipleWindows(true)
            if (android.os.Build.VERSION.SDK_INT >= 26) tab.webView.settings.setSafeBrowsingEnabled(settings.safeBrowsing)
        }
        if (showingSettings) rebuildSettingsPanel()
    }

    private fun showJavascriptSitesDialog() {
        val hosts = settings.javascriptHosts().sorted()
        if (hosts.isEmpty()) {
            AlertDialog.Builder(this).setTitle("JavaScript exceptions")
                .setMessage("No sites have been allowed yet. Open a site and choose 'Allow JavaScript for this site' from the menu.")
                .setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Remove a site exception")
            .setItems(hosts.toTypedArray()) { _, index ->
                settings.setJavascriptAllowed(hosts[index], false)
                applySettingsToTabs()
                Toast.makeText(this, "JavaScript exception removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null).show()
    }

    private fun showCustomHostsDialog() {
        val input = EditText(this).apply {
            setText(settings.customBlockedHosts().sorted().joinToString("\n"))
            hint = "tracker.example\nad-domain.example"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 6
            gravity = Gravity.TOP
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(input, LinearLayout.LayoutParams(-1, dp(160)))
        }
        AlertDialog.Builder(this).setTitle("Custom blocked hosts").setView(container)
            .setMessage("Enter one hostname per line. Wildcards are not supported.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                settings.setCustomBlockedHosts(RequestFilter.normalizeHostList(input.text.toString()))
                Toast.makeText(this, "Local host list saved", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showThemePicker() {
        val choices = arrayOf("AMOLED black", "Light")
        val selected = if (settings.themeMode == BrowserSettings.THEME_LIGHT) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Appearance")
            .setSingleChoiceItems(choices, selected) { dialog, which ->
                settings.themeMode = if (which == 1) BrowserSettings.THEME_LIGHT else BrowserSettings.THEME_AMOLED
                setTheme(if (settings.themeMode == BrowserSettings.THEME_LIGHT) R.style.NoctisTheme_Light else R.style.NoctisTheme)
                applyThemePalette(updateViews = true)
                updateSystemBars()
                if (showingSettings) rebuildSettingsPanel()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun currentPalette() = ThemePalette(bg, surface, surfaceRaised, primary, secondary, accent, outline)

    private fun applyThemePalette(updateViews: Boolean) {
        val previous = currentPalette()
        if (settings.themeMode == BrowserSettings.THEME_LIGHT) {
            bg = Color.rgb(250, 250, 250)
            surface = Color.rgb(255, 255, 255)
            surfaceRaised = Color.rgb(239, 239, 239)
            primary = Color.rgb(22, 22, 22)
            secondary = Color.rgb(98, 98, 98)
            accent = Color.rgb(58, 58, 58)
            outline = Color.rgb(202, 202, 202)
        } else {
            bg = Color.rgb(0, 0, 0)
            surface = Color.rgb(16, 16, 16)
            surfaceRaised = Color.rgb(26, 26, 26)
            primary = Color.rgb(242, 242, 242)
            secondary = Color.rgb(166, 166, 166)
            accent = Color.rgb(189, 189, 189)
            outline = Color.rgb(76, 76, 76)
        }
        if (updateViews && ::root.isInitialized) {
            recolorViewTree(root, previous, currentPalette())
            tabs.forEach { it.webView.setBackgroundColor(bg) }
            if (::tabButton.isInitialized) tabButton.background = tabBadgeBackground()
        }
    }

    private fun updateSystemBars() {
        // Access decorView first so the Window has initialized its DecorView. Avoid
        // Window.insetsController here: on some Android builds it dereferences the
        // internal decor before that view has been created.
        val decorView = window.decorView
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val lightSystemIcons = settings.themeMode == BrowserSettings.THEME_LIGHT
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            // post() waits until the decor is attached and its insets controller exists.
            decorView.post {
                decorView.windowInsetsController?.setSystemBarsAppearance(
                    if (lightSystemIcons) appearance else 0,
                    appearance
                )
            }
        } else {
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = if (lightSystemIcons) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else 0
        }
    }

    private fun recolorViewTree(view: View, previous: ThemePalette, next: ThemePalette) {
        when (val drawable = view.background) {
            is GradientDrawable -> {
                val color = drawable.color?.defaultColor
                val replacement = replacementColor(color, previous, next)
                if (replacement != null) drawable.setColor(replacement)
                if (view === tabButton) drawable.setStroke(dp(1), next.outline)
            }
            is android.graphics.drawable.ColorDrawable -> {
                val replacement = replacementColor(drawable.color, previous, next)
                if (replacement != null) view.setBackgroundColor(replacement)
            }
        }

        if (view is TextView) {
            replacementColor(view.textColors.defaultColor, previous, next)?.let(view::setTextColor)
            replacementColor(view.hintTextColors.defaultColor, previous, next)?.let(view::setHintTextColor)
        }
        if (view is ProgressBar) {
            view.progressTintList = android.content.res.ColorStateList.valueOf(next.accent)
            view.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(next.surface)
        }
        if (view is Switch) {
            view.thumbTintList = android.content.res.ColorStateList.valueOf(next.accent)
            view.trackTintList = android.content.res.ColorStateList.valueOf(next.outline)
        }
        if (view is WebView) view.setBackgroundColor(next.bg)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) recolorViewTree(view.getChildAt(index), previous, next)
        }
    }

    private fun replacementColor(color: Int?, previous: ThemePalette, next: ThemePalette): Int? = when (color) {
        previous.bg -> next.bg
        previous.surface -> next.surface
        previous.surfaceRaised -> next.surfaceRaised
        previous.primary -> next.primary
        previous.secondary -> next.secondary
        previous.accent -> next.accent
        previous.outline -> next.outline
        else -> null
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Noctis 0.2.1")
            .setMessage(
                "Noctis is a small, local-first Android browser inspired by Monivo.\n\n" +
                    "Copyright (C) 2026 Noctis contributors.\n" +
                    "Licensed under the GNU Affero General Public License, version 3 or later (AGPL-3.0-or-later).\n\n" +
                    "This program comes with no warranty. You may redistribute and modify it under the terms of the AGPL-3.0.\n\n" +
                    "Noctis does not use Google Play Services, analytics SDKs, crash-reporting SDKs or a remote account system. " +
                    "Web content is rendered by the Android System WebView provider installed on your device."
            )
            .setNeutralButton("View license") { _, _ -> showLicenseDialog() }
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLicenseDialog() {
        val licenseText = try {
            assets.open("AGPL-3.0.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "GNU Affero General Public License, version 3.\n\nSee https://www.gnu.org/licenses/agpl-3.0.html"
        }
        val textView = TextView(this).apply {
            text = licenseText
            textSize = 12f
            setTextColor(primary)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val scroller = ScrollView(this).apply {
            addView(textView)
            setBackgroundColor(bg)
        }
        AlertDialog.Builder(this)
            .setTitle("GNU AGPL-3.0")
            .setView(scroller)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showSearchEnginePicker() {
        val engines = settings.searchEngines()
        val selected = engines.indexOfFirst { it.id == settings.activeSearchEngineId }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Default search engine")
            .setSingleChoiceItems(engines.map { it.name }.toTypedArray(), selected) { dialog, which ->
                settings.activeSearchEngineId = engines[which].id
                dialog.dismiss()
                if (showingSettings) rebuildSettingsPanel()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showSearchEngineManager() {
        val current = settings.searchEngines()
        val labels = current.map { "${it.name}\n${it.urlTemplate}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Search engines")
            .setItems(labels) { _, which ->
                val engine = current[which]
                AlertDialog.Builder(this).setTitle(engine.name)
                    .setMessage(engine.urlTemplate)
                    .setItems(arrayOf("Set as default", "Edit", "Remove")) { _, action ->
                        when (action) {
                            0 -> settings.activeSearchEngineId = engine.id
                            1 -> showEditSearchEngineDialog(engine)
                            2 -> if (current.size > 1) settings.saveSearchEngines(current.filterNot { it.id == engine.id })
                                 else Toast.makeText(this, "Keep at least one search engine", Toast.LENGTH_SHORT).show()
                        }
                        if (showingSettings) rebuildSettingsPanel()
                    }.setNegativeButton("Cancel", null).show()
            }
            .setPositiveButton("Add") { _, _ -> showAddSearchEngineDialog() }
            .setNegativeButton("Close", null).show()
    }


    private fun showEditSearchEngineDialog(engine: SearchEngine) {
        val name = EditText(this).apply { setText(engine.name); setSingleLine(true) }
        val template = EditText(this).apply {
            setText(engine.urlTemplate)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(name, LinearLayout.LayoutParams(-1, dp(50)))
            addView(template, LinearLayout.LayoutParams(-1, dp(50)))
        }
        AlertDialog.Builder(this).setTitle("Edit search engine").setView(form)
            .setMessage("Use an HTTPS URL and keep %s where the encoded query belongs.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val engineName = name.text.toString().trim()
                val urlTemplate = template.text.toString().trim()
                val parsed = try { Uri.parse(urlTemplate.replace("%s", "noctis-test")) } catch (_: Exception) { null }
                if (engineName.isBlank() || !urlTemplate.contains("%s") || parsed?.scheme != "https" || parsed?.host.isNullOrBlank()) {
                    Toast.makeText(this, "Enter a name and a valid HTTPS URL containing %s", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val updated = settings.searchEngines().map {
                    if (it.id == engine.id) engine.copy(name = engineName, urlTemplate = urlTemplate) else it
                }
                settings.saveSearchEngines(updated)
                if (showingSettings) rebuildSettingsPanel()
            }.show()
    }

    private fun showAddSearchEngineDialog() {
        val name = EditText(this).apply { hint = "Engine name"; setSingleLine(true) }
        val template = EditText(this).apply {
            hint = "https://search.example/?q=%s"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
            addView(name, LinearLayout.LayoutParams(-1, dp(50)))
            addView(template, LinearLayout.LayoutParams(-1, dp(50)))
        }
        AlertDialog.Builder(this).setTitle("Add search engine").setView(form)
            .setMessage("Use %s where the URL-encoded search query belongs. HTTPS is required.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val engineName = name.text.toString().trim()
                val urlTemplate = template.text.toString().trim()
                val testUrl = urlTemplate.replace("%s", "noctis-test")
                val parsed = try { Uri.parse(testUrl) } catch (_: Exception) { null }
                if (engineName.isBlank() || !urlTemplate.contains("%s") || parsed?.scheme != "https" || parsed.host.isNullOrBlank()) {
                    Toast.makeText(this, "Enter a name and a valid HTTPS URL containing %s", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val newEngine = SearchEngine(UUID.randomUUID().toString(), engineName, urlTemplate)
                settings.saveSearchEngines(settings.searchEngines() + newEngine)
                settings.activeSearchEngineId = newEngine.id
                if (showingSettings) rebuildSettingsPanel()
            }.show()
    }
}

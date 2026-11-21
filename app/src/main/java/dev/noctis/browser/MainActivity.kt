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
    private lateinit var privateButton: Button
    private lateinit var tabButton: Button
    private lateinit var bottomBar: LinearLayout
    private var showingSettings = false
    private var showingHome = true

    private val bg = Color.rgb(8, 8, 8)
    private val surface = Color.rgb(21, 21, 21)
    private val surfaceRaised = Color.rgb(31, 31, 31)
    private val primary = Color.rgb(238, 238, 238)
    private val secondary = Color.rgb(164, 164, 164)
    private val accent = Color.rgb(189, 189, 189)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = BrowserSettings(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WebView.setWebContentsDebuggingEnabled(false)

        buildLayout()
        createTab()

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
        val brandBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(8))
            setBackgroundColor(bg)
        }
        val logo = ImageView(this).apply {
            setImageResource(dev.noctis.browser.R.drawable.ic_noctis)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        brandBar.addView(logo, LinearLayout.LayoutParams(dp(34), dp(34)))
        val brandText = TextView(this).apply {
            text = "Noctis"
            textSize = 20f
            setTextColor(primary)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(10), 0, 0, 0)
        }
        brandBar.addView(brandText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        privateButton = textButton("◌ Private")
        privateButton.setOnClickListener { togglePrivateSession() }
        brandBar.addView(privateButton)
        val menuButton = textButton("⋮")
        menuButton.textSize = 24f
        menuButton.setOnClickListener { showBrowserMenu(menuButton) }
        brandBar.addView(menuButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(brandBar)

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(10))
            setBackgroundColor(bg)
        }
        addressBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            setTextColor(primary)
            setHintTextColor(secondary)
            hint = "Search privately or enter an address"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(surfaceRaised, 24)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    navigateTo(addressBar.text.toString())
                    true
                } else false
            }
        }
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        val go = textButton("Go")
        go.setOnClickListener { navigateTo(addressBar.text.toString()) }
        addressRow.addView(go, LinearLayout.LayoutParams(dp(54), dp(48)).apply { leftMargin = dp(6) })
        root.addView(addressRow)

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

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(13, 13, 13))
            setPadding(dp(5), dp(7), dp(5), dp(7))
        }
        addBottomButton("‹", "Back") { currentTab()?.webView?.let { if (it.canGoBack()) it.goBack() } }
        addBottomButton("›", "Forward") { currentTab()?.webView?.let { if (it.canGoForward()) it.goForward() } }
        addBottomButton("⌂", "Home") { showHome() }
        tabButton = textButton("▢ 1")
        tabButton.setOnClickListener { showTabsDialog() }
        bottomBar.addView(tabButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        addBottomButton("⚙", "Settings") { showSettings() }
        root.addView(bottomBar)

        setContentView(root)
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
            text = "JavaScript is blocked on new sites until you allow it. Tap the shield menu to make a per-site exception."
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

    private fun addBottomButton(symbol: String, label: String, action: () -> Unit) {
        val button = textButton("$symbol\n$label").apply {
            textSize = 12f
            setOnClickListener { action() }
        }
        bottomBar.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f))
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

    private fun createTab(): BrowserTab {
        val id = nextTabId++
        val webView = WebView(this)
        webView.setBackgroundColor(bg)
        configureWebView(webView, id)
        webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))
        webView.visibility = View.GONE
        val tab = BrowserTab(id, webView)
        tabs.add(tab)
        currentTabId = id
        updateTabButton()
        return tab
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
                val popupTab = createTab()
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
        val tab = currentTab() ?: createTab()
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
        if (tabs.isEmpty()) createTab() else currentTabId = tabs.last().id
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
        updateBottomState()
    }

    private fun showHome() {
        showingSettings = false
        showingHome = true
        webContainer.visibility = View.GONE
        homePanel.visibility = View.VISIBLE
        settingsPanel.visibility = View.GONE
        progress.visibility = View.GONE
        addressBar.setText("")
        updateBottomState()
    }

    private fun showSettings() {
        showingSettings = true
        showingHome = false
        webContainer.visibility = View.GONE
        homePanel.visibility = View.GONE
        settingsPanel.visibility = View.VISIBLE
        rebuildSettingsPanel()
        updateBottomState()
    }

    private fun updateCurrentUrl() {
        val url = currentTab()?.url.orEmpty()
        updateAddress(url)
    }

    private fun updateAddress(url: String) {
        if (!addressBar.hasFocus()) addressBar.setText(url)
    }

    private fun updateTabButton() {
        if (::tabButton.isInitialized) tabButton.text = "▢ ${tabs.size}"
    }

    private fun updateBottomState() {
        updateTabButton()
        if (::privateButton.isInitialized) {
            privateButton.text = if (settings.privateSession) "● Private" else "◌ Private"
            privateButton.setTextColor(if (settings.privateSession) accent else primary)
        }
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
            updateBottomState()
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
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { createTab(); showHome(); true }
                2 -> { bookmarkCurrentPage(); true }
                3 -> { showBookmarksDialog(); true }
                4 -> { toggleJavascriptForCurrentSite(); true }
                5 -> { showFindDialog(); true }
                6 -> { shareCurrentPage(); true }
                7 -> { currentTab()?.webView?.reload(); showBrowser(); true }
                8 -> { confirmClearData(); true }
                9 -> { showSettings(); true }
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
            .setNeutralButton("New tab") { _, _ -> createTab(); showHome() }
            .setPositiveButton("Close current") { _, _ -> closeCurrentTab() }
            .setNegativeButton("Done", null).show()
    }

    private fun closeCurrentTab() {
        val tab = currentTab() ?: return
        webContainer.removeView(tab.webView)
        tab.webView.stopLoading()
        tab.webView.destroy()
        tabs.remove(tab)
        if (tabs.isEmpty()) createTab() else currentTabId = tabs.last().id
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
        if (!settings.privateSession) CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (isFinishing && (settings.clearOnExit || settings.privateSession)) {
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
        addSettingsAction(page, "About Noctis", "Version 0.1.0 · Kotlin · Android System WebView", "View") {
            AlertDialog.Builder(this)
                .setTitle("Noctis 0.1.0")
                .setMessage(
                    "A small, local-first Android browser inspired by Monivo.\n\n" +
                        "Noctis does not use Google Play Services, analytics SDKs, crash-reporting SDKs or a remote account system.\n\n" +
                        "Web content is rendered by the Android System WebView provider installed on your device. That provider may be supplied by your ROM vendor."
                )
                .setPositiveButton("OK", null).show()
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

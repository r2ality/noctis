package dev.noctis.browser.core

import android.webkit.WebView

class BrowserTab(
    val id: Long,
    val webView: WebView,
    var title: String = "New tab",
    var url: String = ""
)

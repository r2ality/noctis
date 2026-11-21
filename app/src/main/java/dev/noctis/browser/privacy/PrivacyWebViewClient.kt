package dev.noctis.browser.privacy

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import dev.noctis.browser.MainActivity
import java.io.ByteArrayInputStream

class PrivacyWebViewClient(
    private val activity: MainActivity,
    private val filter: RequestFilter,
    private val tabId: Long
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return activity.handleWebNavigation(tabId, request.url.toString(), request.isForMainFrame)
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return activity.handleWebNavigation(tabId, url, true)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!request.isForMainFrame && activity.settings.httpsOnly && request.url.scheme.equals("http", true)) {
            return blockedResponse("Insecure HTTP resource blocked")
        }
        if (filter.shouldBlock(url)) return blockedResponse("Blocked by Noctis privacy filter")
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        activity.onPageStarted(tabId, url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        activity.onPageFinished(tabId, url, view.title.orEmpty())
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // Never offer a tap-through for certificate errors.
        handler.cancel()
        activity.showSecurityMessage("TLS certificate error. The connection was blocked.")
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            activity.showSecurityMessage("Page failed to load: ${error.description}")
        }
    }

    @TargetApi(27)
    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse
    ) {
        callback.backToSafety(true)
        activity.showSecurityMessage("Noctis Safe Browsing blocked a potentially harmful page.")
    }

    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
        activity.onWebViewRendererGone(tabId)
        return true
    }

    private fun blockedResponse(message: String): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        mapOf("Cache-Control" to "no-store", "X-Noctis-Block" to message),
        ByteArrayInputStream(ByteArray(0))
    )
}

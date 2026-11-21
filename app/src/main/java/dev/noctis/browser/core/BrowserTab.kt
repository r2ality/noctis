/*
 * Noctis - lightweight, privacy-first Android browser
 * Copyright (C) 2026 Noctis contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.noctis.browser.core

import android.webkit.WebView

class BrowserTab(
    val id: Long,
    val webView: WebView,
    var title: String = "New tab",
    var url: String = ""
)

package dev.noctis.browser.privacy

import dev.noctis.browser.settings.BrowserSettings
import java.util.Locale

/** Small built-in host list plus a user-managed host list. This is not a full EasyList engine. */
class RequestFilter(private val settings: BrowserSettings) {
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "google-analytics.com",
        "googletagmanager.com",
        "adservice.google.com",
        "adsystem.com",
        "adnxs.com",
        "adsrvr.org",
        "taboola.com",
        "outbrain.com",
        "scorecardresearch.com",
        "quantserve.com",
        "criteo.com",
        "hotjar.com",
        "clarity.ms",
        "connect.facebook.net",
        "pixel.facebook.com",
        "analytics.tiktok.com",
        "bat.bing.com",
        "segment.io",
        "segment.com",
        "mixpanel.com",
        "amplitude.com"
    )

    fun shouldBlock(url: String): Boolean {
        if (!settings.blockTrackers) return false
        val host = try {
            java.net.URI(url).host?.lowercase(Locale.ROOT) ?: return false
        } catch (_: Exception) {
            return false
        }
        val custom = settings.customBlockedHosts()
        return (blockedHosts + custom).any { blocked ->
            val normalized = blocked.trim().lowercase(Locale.ROOT)
            normalized.isNotEmpty() && (host == normalized || host.endsWith(".$normalized"))
        }
    }

    companion object {
        fun normalizeHostList(raw: String): Set<String> = raw
            .lineSequence()
            .map { it.substringBefore('#').trim().lowercase(Locale.ROOT) }
            .mapNotNull { line ->
                when {
                    line.isBlank() -> null
                    line.startsWith("127.0.0.1 ") -> line.substringAfter(' ').trim()
                    line.startsWith("0.0.0.0 ") -> line.substringAfter(' ').trim()
                    line.contains(' ') -> line.substringAfterLast(' ').trim()
                    else -> line
                }
            }
            .filter { it.matches(Regex("(?i)[a-z0-9.-]+")) && !it.startsWith(".") && !it.endsWith(".") }
            .toSet()
    }
}

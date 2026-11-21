/*
 * Noctis - lightweight, privacy-first Android browser
 * Copyright (C) 2026 Noctis contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.noctis.browser.settings

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Persistent browser preferences. No navigation history is stored here. */
class BrowserSettings(context: Context) {
    private val preferences = context.getSharedPreferences("noctis_settings", Context.MODE_PRIVATE)

    var themeMode: String
        get() = preferences.getString("theme_mode", THEME_AMOLED) ?: THEME_AMOLED
        set(value) = preferences.edit()
            .putString("theme_mode", if (value == THEME_LIGHT) THEME_LIGHT else THEME_AMOLED)
            .apply()

    var httpsOnly: Boolean
        get() = preferences.getBoolean("https_only", true)
        set(value) = preferences.edit().putBoolean("https_only", value).apply()

    var blockTrackers: Boolean
        get() = preferences.getBoolean("block_trackers", true)
        set(value) = preferences.edit().putBoolean("block_trackers", value).apply()

    var scriptGateEnabled: Boolean
        get() = preferences.getBoolean("script_gate_enabled", true)
        set(value) = preferences.edit().putBoolean("script_gate_enabled", value).apply()

    var javascriptEverywhere: Boolean
        get() = preferences.getBoolean("javascript_everywhere", false)
        set(value) = preferences.edit().putBoolean("javascript_everywhere", value).apply()

    var allowFirstPartyCookies: Boolean
        get() = preferences.getBoolean("first_party_cookies", false)
        set(value) = preferences.edit().putBoolean("first_party_cookies", value).apply()

    var diskCacheEnabled: Boolean
        get() = preferences.getBoolean("disk_cache_enabled", false)
        set(value) = preferences.edit().putBoolean("disk_cache_enabled", value).apply()

    var blockThirdPartyCookies: Boolean
        get() = preferences.getBoolean("block_third_party_cookies", true)
        set(value) = preferences.edit().putBoolean("block_third_party_cookies", value).apply()

    var safeBrowsing: Boolean
        get() = preferences.getBoolean("safe_browsing", true)
        set(value) = preferences.edit().putBoolean("safe_browsing", value).apply()

    var clearOnExit: Boolean
        get() = preferences.getBoolean("clear_on_exit", true)
        set(value) = preferences.edit().putBoolean("clear_on_exit", value).apply()

    var blockPopups: Boolean
        get() = preferences.getBoolean("block_popups", true)
        set(value) = preferences.edit().putBoolean("block_popups", value).apply()

    var privateSession: Boolean = false
        private set

    fun setPrivateSession(enabled: Boolean) {
        privateSession = enabled
        preferences.edit().putBoolean("private_session_active", enabled).commit()
    }

    fun privateSessionWasInterrupted(): Boolean =
        preferences.getBoolean("private_session_active", false)

    var activeSearchEngineId: String
        get() = preferences.getString("search_engine", "duckduckgo") ?: "duckduckgo"
        set(value) = preferences.edit().putString("search_engine", value).apply()

    fun javascriptAllowed(host: String?): Boolean {
        if (!scriptGateEnabled || javascriptEverywhere) return true
        if (host.isNullOrBlank()) return false
        return javascriptHosts().contains(host.lowercase())
    }

    fun javascriptHosts(): Set<String> =
        preferences.getStringSet("javascript_hosts", emptySet())?.toSet() ?: emptySet()

    fun setJavascriptAllowed(host: String, allowed: Boolean) {
        val hosts = javascriptHosts().toMutableSet()
        if (allowed) hosts.add(host.lowercase()) else hosts.remove(host.lowercase())
        preferences.edit().putStringSet("javascript_hosts", hosts).apply()
    }

    fun customBlockedHosts(): Set<String> =
        preferences.getStringSet("custom_blocked_hosts", emptySet())?.toSet() ?: emptySet()

    fun setCustomBlockedHosts(hosts: Set<String>) {
        preferences.edit().putStringSet("custom_blocked_hosts", hosts).apply()
    }

    fun searchEngines(): List<SearchEngine> {
        val raw = preferences.getString("search_engines", null) ?: return SearchEngine.defaults
        return try {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    add(
                        SearchEngine(
                            id = item.getString("id"),
                            name = item.getString("name"),
                            urlTemplate = item.getString("url")
                        )
                    )
                }
            }.ifEmpty { SearchEngine.defaults }
        } catch (_: Exception) {
            SearchEngine.defaults
        }
    }

    fun saveSearchEngines(engines: List<SearchEngine>) {
        val json = JSONArray()
        engines.forEach { engine ->
            json.put(
                JSONObject()
                    .put("id", engine.id)
                    .put("name", engine.name)
                    .put("url", engine.urlTemplate)
            )
        }
        preferences.edit().putString("search_engines", json.toString()).apply()
        if (engines.none { it.id == activeSearchEngineId } && engines.isNotEmpty()) {
            activeSearchEngineId = engines.first().id
        }
    }

    fun selectedSearchEngine(): SearchEngine =
        searchEngines().firstOrNull { it.id == activeSearchEngineId } ?: SearchEngine.defaults.first()

    fun addBookmark(url: String, title: String) {
        val values = bookmarks().toMutableList()
        if (values.none { it.url == url }) values.add(Bookmark(title.ifBlank { url }, url))
        saveBookmarks(values)
    }

    fun removeBookmark(url: String) {
        saveBookmarks(bookmarks().filterNot { it.url == url })
    }

    fun bookmarks(): List<Bookmark> {
        val raw = preferences.getString("bookmarks", null) ?: return emptyList()
        return try {
            val json = JSONArray(raw)
            buildList {
                for (i in 0 until json.length()) {
                    val item = json.getJSONObject(i)
                    add(Bookmark(item.getString("title"), item.getString("url")))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveBookmarks(bookmarks: List<Bookmark>) {
        val json = JSONArray()
        bookmarks.forEach { json.put(JSONObject().put("title", it.title).put("url", it.url)) }
        preferences.edit().putString("bookmarks", json.toString()).apply()
    }
    companion object {
        const val THEME_AMOLED = "amoled"
        const val THEME_LIGHT = "light"
    }
}

data class SearchEngine(val id: String, val name: String, val urlTemplate: String) {
    fun searchUrl(query: String): String = urlTemplate.replace("%s", Uri.encode(query))

    companion object {
        val defaults = listOf(
            SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
            SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
            SearchEngine("startpage", "Startpage", "https://www.startpage.com/sp/search?query=%s"),
            SearchEngine("searx", "SearXNG (custom instance)", "https://searx.be/search?q=%s")
        )
    }
}

data class Bookmark(val title: String, val url: String)

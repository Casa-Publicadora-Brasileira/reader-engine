package com.readerengine.app

import android.content.Context
import android.content.SharedPreferences
import com.readerengine.core.model.ReadingMode
import com.readerengine.core.model.ReadingProgress

/**
 * Local host store demonstrating how any app listens to [com.readerengine.core.session.ReaderEvent]
 * and persists to its own local storage or remote backend without fixed page limits.
 */
class HostBookPersistenceStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("kmp_reader_host_store", Context.MODE_PRIVATE)

    fun saveProgress(progress: ReadingProgress) {
        prefs.edit()
            .putInt("${progress.documentId}_page", progress.pageIndex)
            .putInt("${progress.documentId}_subPage", progress.subPage)
            .putInt("${progress.documentId}_total", progress.totalPages)
            .putInt("${progress.documentId}_scrollY", progress.scrollY)
            .putFloat("${progress.documentId}_percent", progress.totalPercentage)
            .apply()
    }

    fun loadProgress(documentId: String): ReadingProgress? {
        if (!prefs.contains("${documentId}_page")) return null
        val page = prefs.getInt("${documentId}_page", 0)
        return ReadingProgress(
            documentId = documentId,
            pageIndex = page,
            pageNumber = page + 1,
            totalPages = prefs.getInt("${documentId}_total", 1),
            scrollY = prefs.getInt("${documentId}_scrollY", 0),
            subPage = prefs.getInt("${documentId}_subPage", 0),
            totalPercentage = prefs.getFloat("${documentId}_percent", 0f),
        )
    }

    fun savePageHighlight(documentId: String, pageIndex: Int, compressedPayload: String) {
        prefs.edit()
            .putString("${documentId}_hl_$pageIndex", compressedPayload)
            .apply()
    }

    fun loadHighlights(documentId: String): Map<Int, String> {
        val prefix = "${documentId}_hl_"
        val result = mutableMapOf<Int, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String && value.isNotBlank()) {
                val pageIdx = key.removePrefix(prefix).toIntOrNull()
                if (pageIdx != null) {
                    result[pageIdx] = value
                }
            }
        }
        return result
    }

    fun savePageForm(documentId: String, pageIndex: Int, compressedPayload: String) {
        prefs.edit()
            .putString("${documentId}_form_$pageIndex", compressedPayload)
            .apply()
    }

    fun loadPageForms(documentId: String): Map<Int, String> {
        val prefix = "${documentId}_form_"
        val result = mutableMapOf<Int, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String && value.isNotBlank()) {
                val pageIdx = key.removePrefix(prefix).toIntOrNull()
                if (pageIdx != null) {
                    result[pageIdx] = value
                }
            }
        }
        return result
    }

    fun saveBookmarksJson(documentId: String, json: String) {
        prefs.edit()
            .putString("${documentId}_bookmarks", json)
            .apply()
    }

    fun loadBookmarksJson(documentId: String): String? =
        prefs.getString("${documentId}_bookmarks", null)

    fun savePreferences(
        fontScale: Float,
        isNightMode: Boolean,
        fontFamily: String,
        readingMode: ReadingMode,
    ) {
        prefs.edit()
            .putFloat("pref_font_scale", fontScale)
            .putBoolean("pref_night_mode", isNightMode)
            .putString("pref_font_family", fontFamily)
            .putString("pref_reading_mode", readingMode.name)
            .apply()
    }

    fun loadFontScale(): Float = prefs.getFloat("pref_font_scale", 1.0f)
    fun loadNightMode(): Boolean = prefs.getBoolean("pref_night_mode", false)
    fun loadFontFamily(): String = prefs.getString("pref_font_family", "Default") ?: "Default"
    fun loadReadingMode(): ReadingMode =
        runCatching {
            ReadingMode.valueOf(prefs.getString("pref_reading_mode", ReadingMode.SCROLL.name) ?: ReadingMode.SCROLL.name)
        }.getOrDefault(ReadingMode.SCROLL)
}

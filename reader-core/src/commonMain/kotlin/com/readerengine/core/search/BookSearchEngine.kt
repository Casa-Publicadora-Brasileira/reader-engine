package com.readerengine.core.search

import com.readerengine.core.model.Publication
import com.readerengine.core.model.SearchResult
import com.readerengine.core.model.TocItem

/**
 * Motor de busca textual em todos os capítulos do `spine` da publicação.
 * Remove tags HTML/scripts/estilos, encontra ocorrências case-insensitive e gera snippets com contexto.
 */
object BookSearchEngine {

    private val scriptOrStyleRegex = Regex("""<(script|style)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    fun search(
        publication: Publication,
        resolvedToc: List<TocItem>,
        query: String,
        maxResults: Int = 50,
    ): List<SearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty() || maxResults <= 0) return emptyList()

        val results = mutableListOf<SearchResult>()

        for ((pageIndex, href) in publication.spine.withIndex()) {
            val rawHtml = runCatching { publication.readString(href) }.getOrNull() ?: continue
            val plainText = htmlToPlainText(rawHtml)
            if (plainText.isEmpty()) continue

            val chapterTitle = resolvedToc.firstOrNull { it.spineIndex == pageIndex }?.title
                ?: "Capítulo ${pageIndex + 1}"

            var searchFrom = 0
            while (searchFrom < plainText.length && results.size < maxResults) {
                val matchIdx = plainText.indexOf(cleanQuery, startIndex = searchFrom, ignoreCase = true)
                if (matchIdx < 0) break

                val snippetStart = (matchIdx - 45).coerceAtLeast(0)
                val snippetEnd = (matchIdx + cleanQuery.length + 55).coerceAtMost(plainText.length)
                val prefix = if (snippetStart > 0) "…" else ""
                val suffix = if (snippetEnd < plainText.length) "…" else ""
                val snippet = prefix + plainText.substring(snippetStart, snippetEnd).trim() + suffix

                val progression = (matchIdx.toFloat() / plainText.length.coerceAtLeast(1).toFloat())
                    .coerceIn(0f, 1f)

                results.add(
                    SearchResult(
                        pageIndex = pageIndex,
                        chapterTitle = chapterTitle,
                        snippet = snippet,
                        matchIndex = matchIdx,
                        progression = progression,
                    ),
                )

                searchFrom = matchIdx + cleanQuery.length.coerceAtLeast(1)
            }

            if (results.size >= maxResults) break
        }

        return results
    }

    internal fun htmlToPlainText(html: String): String {
        val withoutScripts = scriptOrStyleRegex.replace(html, " ")
        val withoutTags = htmlTagRegex.replace(withoutScripts, " ")
        val decoded = withoutTags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
        return whitespaceRegex.replace(decoded, " ").trim()
    }
}

package com.readerengine.core.search

import com.readerengine.core.model.Locator
import com.readerengine.core.model.Publication
import com.readerengine.core.model.ReaderStrings
import com.readerengine.core.model.SearchResult
import com.readerengine.core.model.TocItem
import com.readerengine.core.parser.XmlEntities

/**
 * Motor de busca textual em todos os capítulos do `spine` da publicação.
 * Remove tags HTML/scripts/estilos, realiza busca case-insensitive e insensível a acentos (diacríticos),
 * utiliza cache por capítulo e gera snippets com contexto e `Locator`.
 */
public object BookSearchEngine {

    private val scriptOrStyleRegex = Regex("""<(script|style)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    public fun search(
        publication: Publication,
        resolvedToc: List<TocItem>,
        query: String,
        maxResults: Int = 50,
        strings: ReaderStrings = ReaderStrings.portuguese(),
    ): List<SearchResult> {
        val cleanQuery = query.trim().replace(whitespaceRegex, " ")
        if (cleanQuery.isEmpty() || maxResults <= 0) return emptyList()

        val foldedQuery = foldDiacritics(cleanQuery)
        val results = mutableListOf<SearchResult>()

        for ((pageIndex, href) in publication.spine.withIndex()) {
            val plainText = runCatching {
                publication.cachedPlainText(href, ::htmlToPlainText)
            }.getOrNull() ?: continue
            if (plainText.isEmpty()) continue

            val foldedPlain = foldDiacritics(plainText)
            val chapterTitle = resolvedToc.firstOrNull { it.spineIndex == pageIndex }?.title
                ?: strings.formatChapterLabel(pageIndex + 1)

            var searchFrom = 0
            while (searchFrom < foldedPlain.length && results.size < maxResults) {
                val matchIdx = foldedPlain.indexOf(foldedQuery, startIndex = searchFrom, ignoreCase = true)
                if (matchIdx < 0) break

                val snippetStart = (matchIdx - 45).coerceAtLeast(0)
                val snippetEnd = (matchIdx + cleanQuery.length + 55).coerceAtMost(plainText.length)
                val prefix = if (snippetStart > 0) "…" else ""
                val suffix = if (snippetEnd < plainText.length) "…" else ""
                val snippet = prefix + plainText.substring(snippetStart, snippetEnd).trim() + suffix
                val matchedQuote = plainText.substring(
                    matchIdx,
                    (matchIdx + cleanQuery.length).coerceAtMost(plainText.length),
                )

                val progression = (matchIdx.toFloat() / plainText.length.coerceAtLeast(1).toFloat())
                    .coerceIn(0f, 1f)

                results.add(
                    SearchResult(
                        pageIndex = pageIndex,
                        chapterTitle = chapterTitle,
                        snippet = snippet,
                        matchIndex = matchIdx,
                        progression = progression,
                        locator = Locator(
                            href = href,
                            pageIndex = pageIndex,
                            progression = progression,
                            title = chapterTitle,
                            textQuote = matchedQuote,
                        ),
                    ),
                )

                searchFrom = matchIdx + foldedQuery.length.coerceAtLeast(1)
            }

            if (results.size >= maxResults) break
        }

        return results
    }

    internal fun htmlToPlainText(html: String): String {
        val withoutScripts = scriptOrStyleRegex.replace(html, " ")
        val withoutTags = htmlTagRegex.replace(withoutScripts, " ")
        val decoded = XmlEntities.decode(withoutTags)
        return whitespaceRegex.replace(decoded, " ").trim()
    }

    /**
     * Remove acentos/diacríticos latinos mantendo relação 1-para-1 de índices com a string original,
     * permitindo que buscas por `"licao"` ou `"noite"` encontrem `"Lição"` ou `"nôite"`.
     */
    internal fun foldDiacritics(input: String): String {
        var changed = false
        val chars = CharArray(input.length)
        for (i in input.indices) {
            val c = input[i]
            val folded = foldChar(c)
            if (folded != c) changed = true
            chars[i] = folded
        }
        return if (changed) chars.concatToString() else input
    }

    private fun foldChar(c: Char): Char = when (c) {
        'á', 'à', 'â', 'ã', 'ä', 'å', 'Á', 'À', 'Â', 'Ã', 'Ä', 'Å' -> 'a'
        'é', 'è', 'ê', 'ë', 'É', 'È', 'Ê', 'Ë' -> 'e'
        'í', 'ì', 'î', 'ï', 'Í', 'Ì', 'Î', 'Ï' -> 'i'
        'ó', 'ò', 'ô', 'õ', 'ö', 'Ó', 'Ò', 'Ô', 'Õ', 'Ö' -> 'o'
        'ú', 'ù', 'û', 'ü', 'Ú', 'Ù', 'Û', 'Ü' -> 'u'
        'ç', 'Ç' -> 'c'
        'ñ', 'Ñ' -> 'n'
        'ý', 'ÿ', 'Ý', 'Ÿ' -> 'y'
        else -> c.lowercaseChar()
    }
}

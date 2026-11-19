package com.readerengine.core.search

import com.readerengine.core.model.Locator
import com.readerengine.core.model.PersistedSearchIndex
import com.readerengine.core.model.Publication
import com.readerengine.core.model.ReaderStrings
import com.readerengine.core.model.SearchResult
import com.readerengine.core.model.TocItem
import com.readerengine.core.model.TtsSentence
import com.readerengine.core.parser.XmlEntities
import com.readerengine.core.platform.defaultFileSystem
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Motor de busca textual em todos os capítulos do `spine` da publicação.
 * Remove tags HTML/scripts/estilos, realiza busca case-insensitive e insensível a acentos (diacríticos),
 * utiliza cache por capítulo e gera snippets com contexto e `Locator`.
 */
public object BookSearchEngine {

    private val json = Json { ignoreUnknownKeys = true }
    private val scriptOrStyleRegex = Regex("""<(head|script|style)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
    private val htmlTagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")
    private val sentenceSplitRegex = Regex("""[^.!?\n]+(?:[.!?]+["'”’)\]]*|\n|$)""")

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

    /**
     * Constrói o índice de texto limpo para todos os capítulos do `spine` da publicação.
     */
    public fun buildIndex(publication: Publication, documentId: String): PersistedSearchIndex {
        val chapters = LinkedHashMap<String, String>(publication.spine.size)
        for (href in publication.spine) {
            val plain = runCatching {
                publication.cachedPlainText(href, ::htmlToPlainText)
            }.getOrDefault("")
            chapters[href] = plain
        }
        return PersistedSearchIndex(
            version = 1,
            documentId = documentId,
            spine = publication.spine.toList(),
            chapters = chapters,
        )
    }

    /** Exporta o índice de busca da publicação como JSON serializado. */
    public fun exportIndexJson(publication: Publication, documentId: String): String {
        val index = buildIndex(publication, documentId)
        return json.encodeToString(PersistedSearchIndex.serializer(), index)
    }

    /**
     * Restaura o cache interno de texto limpo da publicação a partir de um JSON exportado previamente.
     * Retorna `true` se o índice for compatível com o `spine` atual e populou o cache.
     */
    public fun importIndexJson(publication: Publication, rawJson: String): Boolean {
        val parsed = runCatching {
            json.decodeFromString(PersistedSearchIndex.serializer(), rawJson)
        }.getOrNull() ?: return false
        if (parsed.chapters.isEmpty()) return false
        val spineSet = publication.spine.toSet()
        val matching = parsed.chapters.filterKeys { it in spineSet }
        if (matching.isEmpty()) return false
        publication.restorePlainTextCache(matching)
        return true
    }

    /** Persiste o índice de busca em disco usando `Okio FileSystem`. */
    public fun saveIndexToDisk(
        publication: Publication,
        documentId: String,
        path: Path,
        fileSystem: FileSystem = defaultFileSystem,
    ) {
        path.parent?.let { parent ->
            if (!fileSystem.exists(parent)) {
                fileSystem.createDirectories(parent)
            }
        }
        val payload = exportIndexJson(publication, documentId)
        fileSystem.write(path) {
            writeUtf8(payload)
        }
    }

    /** Carrega o índice de busca do disco (`Okio FileSystem`) e popula o cache da publicação. */
    public fun loadIndexFromDisk(
        publication: Publication,
        path: Path,
        fileSystem: FileSystem = defaultFileSystem,
    ): Boolean {
        if (!fileSystem.exists(path)) return false
        val raw = runCatching {
            fileSystem.read(path) { readUtf8() }
        }.getOrNull() ?: return false
        return importIndexJson(publication, raw)
    }

    /**
     * Extrai as sentenças do capítulo indicado para leitura em voz alta (TTS),
     * retornando texto limpo, offsets de caracteres e `Locator`.
     */
    public fun extractSentences(publication: Publication, pageIndex: Int): List<TtsSentence> {
        val href = publication.spine.getOrNull(pageIndex) ?: return emptyList()
        val plainText = runCatching {
            publication.cachedPlainText(href, ::htmlToPlainText)
        }.getOrDefault("")
        if (plainText.isBlank()) return emptyList()

        val totalLen = plainText.length.coerceAtLeast(1)
        val out = mutableListOf<TtsSentence>()
        var idx = 0
        for (match in sentenceSplitRegex.findAll(plainText)) {
            val rawChunk = match.value
            val trimmed = rawChunk.trim()
            if (trimmed.isEmpty()) continue
            val leadingTrim = rawChunk.indexOf(trimmed)
            val charStart = (match.range.first + leadingTrim).coerceAtLeast(0)
            val charEnd = (charStart + trimmed.length).coerceAtMost(plainText.length)
            val progression = (charStart.toFloat() / totalLen.toFloat()).coerceIn(0f, 1f)
            out.add(
                TtsSentence(
                    index = idx++,
                    pageIndex = pageIndex,
                    href = href,
                    text = trimmed,
                    charStart = charStart,
                    charEnd = charEnd,
                    locator = Locator(
                        href = href,
                        pageIndex = pageIndex,
                        progression = progression,
                        textQuote = trimmed.take(120),
                    ),
                ),
            )
        }
        return out
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

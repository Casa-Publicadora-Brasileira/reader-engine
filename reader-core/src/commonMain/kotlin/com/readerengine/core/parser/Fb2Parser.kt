package com.readerengine.core.parser

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.Publication
import com.readerengine.core.model.Resource
import com.readerengine.core.model.TocEntry
import com.readerengine.core.provider.MemoryResourceProvider
import okio.ByteString.Companion.decodeBase64

/**
 * Parser multiplataforma para livros no formato **FictionBook 2** (`.fb2` e `.fb2.zip`).
 *
 * Reutiliza [XmlTokenizer] em `commonMain` para extrair metadados (`<title-info>`),
 * capa e imagens embutidas em Base64 (`<binary>`), notas de rodapé (`<body name="notes">`)
 * como `<aside epub:type="footnote">` e capítulos (`<section>`) em XHTML semântico.
 */
public class Fb2Parser : FormatParser {

    override val id: String = "fb2"

    override fun canParse(source: Source): Boolean {
        val lower = source.name.lowercase()
        if (lower.endsWith(".fb2") || lower.endsWith(".fb2.zip")) return true
        val sample = runCatching {
            val raw = source.bytes
            val limit = raw.size.coerceAtMost(512)
            raw.decodeToString(0, limit)
        }.getOrDefault("")
        return sample.contains("<FictionBook", ignoreCase = true)
    }

    override fun parse(source: Source): Publication {
        val xmlText = readFb2Xml(source)
        val doc = XmlTokenizer.parseDocument(xmlText)
        val root = doc.findFirst("FictionBook")
            ?: throw ReaderException.InvalidContent("Elemento raiz <FictionBook> ausente em '${source.name}'")

        val description = root.findFirst("description")
        val titleInfo = description?.findFirst("title-info")
        val docInfo = description?.findFirst("document-info")

        val title = titleInfo?.findFirst("book-title")?.collectText()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: source.name.substringBeforeLast('.')
        val authors = titleInfo?.childElements("author").orEmpty().mapNotNull { authorEl ->
            val parts = listOfNotNull(
                authorEl.findFirst("first-name")?.collectText()?.trim()?.takeIf { it.isNotEmpty() },
                authorEl.findFirst("middle-name")?.collectText()?.trim()?.takeIf { it.isNotEmpty() },
                authorEl.findFirst("last-name")?.collectText()?.trim()?.takeIf { it.isNotEmpty() },
            )
            val full = parts.joinToString(" ").trim()
            full.takeIf { it.isNotEmpty() }
                ?: authorEl.findFirst("nickname")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
        }
        val language = titleInfo?.findFirst("lang")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
        val identifier = docInfo?.findFirst("id")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }

        val files = LinkedHashMap<String, ByteArray>()
        val resources = mutableListOf<Resource>()
        val binaryIdToHref = LinkedHashMap<String, String>()

        for (binary in root.childElements("binary")) {
            val binId = binary.attr("id")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val mediaType = binary.attr("content-type")?.trim()?.takeIf { it.isNotEmpty() }
                ?: guessMediaType(binId)
            val cleanedBase64 = binary.collectText().filterNot { it.isWhitespace() }
            val decoded = cleanedBase64.decodeBase64()?.toByteArray() ?: continue
            val href = "images/$binId"
            files[href] = decoded
            binaryIdToHref[binId] = href
            resources.add(Resource(href = href, mediaType = mediaType))
        }

        val rawCoverId = titleInfo?.findFirst("coverpage")
            ?.findFirst("image")
            ?.attr("href")
            ?.trim()
            ?.removePrefix("#")
        val coverHref = rawCoverId?.let { binaryIdToHref[it] }
            ?: binaryIdToHref.values.firstOrNull()

        val tokens = XmlTokenizer.tokenize(xmlText)
        val parsedBodies = parseBodiesFromTokens(tokens, binaryIdToHref)
        val notesHtmlById = parsedBodies.notesById
        val chapters = parsedBodies.chapters

        if (chapters.isEmpty()) {
            throw ReaderException.InvalidContent("Arquivo FB2 sem capítulos legíveis: '${source.name}'")
        }

        val spine = mutableListOf<String>()
        val toc = mutableListOf<TocEntry>()
        val notesBlock = if (notesHtmlById.isNotEmpty()) {
            buildString {
                append("<div class=\"fb2-notes\" style=\"display:none;\">")
                notesHtmlById.forEach { (noteId, noteContent) ->
                    append("<aside id=\"").append(escapeHtmlAttr(noteId))
                        .append("\" epub:type=\"footnote\">")
                        .append(noteContent)
                        .append("</aside>")
                }
                append("</div>")
            }
        } else {
            ""
        }

        val langAttr = language?.let { " lang=\"${escapeHtmlAttr(it)}\"" }.orEmpty()
        for ((idx, chapter) in chapters.withIndex()) {
            val href = "chapters/chapter-$idx.xhtml"
            val chapterTitle = chapter.title.ifBlank {
                if (chapters.size == 1) title else "Capítulo ${idx + 1}"
            }
            val html = buildString {
                append("<!doctype html><html xmlns:epub=\"http://www.idpf.org/2007/ops\"$langAttr><head>")
                append("<meta charset=\"utf-8\">")
                append("<title>").append(escapeHtmlText(chapterTitle)).append("</title>")
                append("</head><body>")
                append(chapter.bodyHtml)
                if (notesBlock.isNotEmpty()) {
                    append(notesBlock)
                }
                append("</body></html>")
            }
            files[href] = html.encodeToByteArray()
            spine.add(href)
            resources.add(Resource(href = href, mediaType = "application/xhtml+xml"))
            toc.add(TocEntry(title = chapterTitle, href = href))
        }

        val metadata = Metadata(
            title = title,
            authors = authors,
            language = language,
            identifier = identifier,
            coverHref = coverHref,
        )

        return Publication(
            metadata = metadata,
            spine = spine,
            resources = resources,
            toc = toc,
            provider = MemoryResourceProvider(files),
        )
    }

    private fun readFb2Xml(source: Source): String {
        val lower = source.name.lowercase()
        if (lower.endsWith(".zip")) {
            val zip = source.openZip()
            return try {
                val fb2Entry = zip.entryNames().firstOrNull { it.lowercase().endsWith(".fb2") }
                    ?: throw ReaderException.InvalidContent("Arquivo ZIP sem entrada .fb2: '${source.name}'")
                zip.read(fb2Entry).decodeToString()
            } finally {
                zip.close()
            }
        }
        return source.bytes.decodeToString()
    }

    private data class Fb2Chapter(
        val title: String,
        val bodyHtml: String,
    )

    private data class ParsedFb2Bodies(
        val chapters: List<Fb2Chapter>,
        val notesById: Map<String, String>,
    )

    private fun parseBodiesFromTokens(
        tokens: List<XmlToken>,
        binaryIdToHref: Map<String, String>,
    ): ParsedFb2Bodies {
        val chapters = mutableListOf<Fb2Chapter>()
        val notesById = LinkedHashMap<String, String>()

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            if (token is XmlToken.StartTag && token.localName.equals("body", ignoreCase = true) && !token.selfClosing) {
                val bodyName = token.attr("name")?.lowercase()
                val isNotesBody = bodyName == "notes" || bodyName == "comments"
                val endIdx = findMatchingEndTag(tokens, i, "body")
                val bodySlice = tokens.subList(i + 1, endIdx)

                if (isNotesBody) {
                    extractNotesFromBody(bodySlice, binaryIdToHref, notesById)
                } else {
                    extractChaptersFromBody(bodySlice, binaryIdToHref, chapters)
                }
                i = endIdx + 1
                continue
            }
            i++
        }

        return ParsedFb2Bodies(chapters = chapters, notesById = notesById)
    }

    private fun extractNotesFromBody(
        bodyTokens: List<XmlToken>,
        binaryIdToHref: Map<String, String>,
        notesById: MutableMap<String, String>,
    ) {
        var i = 0
        while (i < bodyTokens.size) {
            val token = bodyTokens[i]
            if (token is XmlToken.StartTag && token.localName.equals("section", ignoreCase = true) && !token.selfClosing) {
                val noteId = token.attr("id")?.trim()
                val endIdx = findMatchingEndTag(bodyTokens, i, "section")
                if (!noteId.isNullOrEmpty()) {
                    val inner = bodyTokens.subList(i + 1, endIdx)
                    val rendered = renderTokensToHtml(inner, binaryIdToHref).first.trim()
                    if (rendered.isNotEmpty()) {
                        notesById[noteId] = rendered
                    }
                }
                i = endIdx + 1
                continue
            }
            i++
        }
    }

    private fun extractChaptersFromBody(
        bodyTokens: List<XmlToken>,
        binaryIdToHref: Map<String, String>,
        chapters: MutableList<Fb2Chapter>,
    ) {
        var i = 0
        var foundTopSection = false
        while (i < bodyTokens.size) {
            val token = bodyTokens[i]
            if (token is XmlToken.StartTag && token.localName.equals("section", ignoreCase = true) && !token.selfClosing) {
                foundTopSection = true
                val endIdx = findMatchingEndTag(bodyTokens, i, "section")
                val sectionTokens = bodyTokens.subList(i + 1, endIdx)
                val (html, title) = renderTokensToHtml(sectionTokens, binaryIdToHref)
                if (html.isNotBlank()) {
                    chapters.add(Fb2Chapter(title = title, bodyHtml = html))
                }
                i = endIdx + 1
                continue
            }
            i++
        }
        if (!foundTopSection) {
            val (html, title) = renderTokensToHtml(bodyTokens, binaryIdToHref)
            if (html.isNotBlank()) {
                chapters.add(Fb2Chapter(title = title, bodyHtml = html))
            }
        }
    }

    private fun renderTokensToHtml(
        tokens: List<XmlToken>,
        binaryIdToHref: Map<String, String>,
    ): Pair<String, String> {
        val html = StringBuilder()
        val firstTitle = StringBuilder()
        var inFirstTitle = false
        var hasCapturedTitle = false
        var titleDepth = 0

        for (token in tokens) {
            when (token) {
                is XmlToken.Text -> {
                    val text = token.content
                    if (inFirstTitle) {
                        if (firstTitle.isNotEmpty() && text.isNotBlank() && !firstTitle.endsWith(' ')) {
                            firstTitle.append(' ')
                        }
                        firstTitle.append(text.trim())
                    }
                    html.append(escapeHtmlText(text))
                }

                is XmlToken.CData -> {
                    if (inFirstTitle) firstTitle.append(token.content.trim())
                    html.append(escapeHtmlText(token.content))
                }

                is XmlToken.StartTag -> {
                    val name = token.localName.lowercase()
                    val idAttr = token.attr("id")?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { " id=\"${escapeHtmlAttr(it)}\"" }.orEmpty()
                    when (name) {
                        "title" -> {
                            titleDepth++
                            if (!hasCapturedTitle && titleDepth == 1) {
                                inFirstTitle = true
                            }
                            if (!token.selfClosing) html.append("<h2$idAttr>")
                        }
                        "subtitle" -> if (!token.selfClosing) html.append("<h3$idAttr>")
                        "p" -> if (token.selfClosing) html.append("<p$idAttr></p>") else html.append("<p$idAttr>")
                        "emphasis" -> if (!token.selfClosing) html.append("<em>")
                        "strong" -> if (!token.selfClosing) html.append("<strong>")
                        "strikethrough" -> if (!token.selfClosing) html.append("<del>")
                        "sup" -> if (!token.selfClosing) html.append("<sup>")
                        "sub" -> if (!token.selfClosing) html.append("<sub>")
                        "code" -> if (!token.selfClosing) html.append("<code>")
                        "epigraph", "cite" -> if (!token.selfClosing) html.append("<blockquote$idAttr>")
                        "poem" -> if (!token.selfClosing) html.append("<div class=\"poem\"$idAttr>")
                        "stanza" -> if (!token.selfClosing) html.append("<p class=\"stanza\">")
                        "v" -> if (!token.selfClosing) html.append("<span class=\"verse\">")
                        "empty-line" -> html.append("<br/>")
                        "section" -> if (!token.selfClosing) html.append("<section$idAttr>")
                        "a" -> {
                            val rawHref = token.attr("href")?.trim().orEmpty()
                            val isNote = token.attr("type").equals("note", ignoreCase = true)
                            val noteAttrs = if (isNote) " epub:type=\"noteref\" role=\"doc-noteref\"" else ""
                            html.append("<a href=\"").append(escapeHtmlAttr(rawHref)).append("\"$noteAttrs>")
                        }
                        "image" -> {
                            val rawRef = token.attr("href")?.trim()?.removePrefix("#").orEmpty()
                            val resolved = binaryIdToHref[rawRef]?.let { "../$it" } ?: rawRef
                            val alt = token.attr("alt")?.trim().orEmpty()
                            if (resolved.isNotEmpty()) {
                                html.append("<figure><img src=\"")
                                    .append(escapeHtmlAttr(resolved))
                                    .append("\" alt=\"")
                                    .append(escapeHtmlAttr(alt))
                                    .append("\"/></figure>")
                            }
                        }
                        else -> Unit
                    }
                }

                is XmlToken.EndTag -> {
                    when (token.localName.lowercase()) {
                        "title" -> {
                            if (inFirstTitle && titleDepth == 1) {
                                inFirstTitle = false
                                hasCapturedTitle = true
                            }
                            if (titleDepth > 0) titleDepth--
                            html.append("</h2>")
                        }
                        "subtitle" -> html.append("</h3>")
                        "p" -> html.append("</p>")
                        "emphasis" -> html.append("</em>")
                        "strong" -> html.append("</strong>")
                        "strikethrough" -> html.append("</del>")
                        "sup" -> html.append("</sup>")
                        "sub" -> html.append("</sub>")
                        "code" -> html.append("</code>")
                        "epigraph", "cite" -> html.append("</blockquote>")
                        "poem" -> html.append("</div>")
                        "stanza" -> html.append("</p>")
                        "v" -> html.append("</span><br/>")
                        "section" -> html.append("</section>")
                        "a" -> html.append("</a>")
                        else -> Unit
                    }
                }
            }
        }

        return html.toString() to firstTitle.toString().replace(Regex("""\s+"""), " ").trim()
    }

    private fun findMatchingEndTag(
        tokens: List<XmlToken>,
        startIndex: Int,
        localName: String,
    ): Int {
        var depth = 0
        for (idx in startIndex until tokens.size) {
            when (val t = tokens[idx]) {
                is XmlToken.StartTag -> {
                    if (t.localName.equals(localName, ignoreCase = true) && !t.selfClosing) {
                        depth++
                    }
                }
                is XmlToken.EndTag -> {
                    if (t.localName.equals(localName, ignoreCase = true)) {
                        depth--
                        if (depth == 0) return idx
                    }
                }
                else -> Unit
            }
        }
        return tokens.size
    }

    private fun guessMediaType(id: String): String = when (id.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        else -> "image/jpeg"
    }

    private fun escapeHtmlText(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeHtmlAttr(raw: String): String = escapeHtmlText(raw)
        .replace("\"", "&quot;")
}

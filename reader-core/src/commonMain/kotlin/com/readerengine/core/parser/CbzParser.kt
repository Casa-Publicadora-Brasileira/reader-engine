package com.readerengine.core.parser

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.PageProgressionDirection
import com.readerengine.core.model.Publication
import com.readerengine.core.model.RenditionLayout
import com.readerengine.core.model.Resource
import com.readerengine.core.model.ResourceProvider
import com.readerengine.core.model.TocEntry
import com.readerengine.core.observability.ZipCacheStats
import com.readerengine.core.provider.ZipResourceProvider
import com.readerengine.core.provider.normalizeHref

/**
 * Parser multiplataforma para quadrinhos e mangás compactados no formato **CBZ** (`.cbz` — Comic Book Archive ZIP).
 *
 * Descobre e ordena naturalmente todas as imagens do arquivo ZIP, suporta metadados opcionais `ComicInfo.xml`
 * (título, série, autores, idioma e direção de leitura mangá `RTL`), gera páginas fixas XHTML
 * (`RenditionLayout.PRE_PAGINATED`) com a imagem centralizada em tela cheia e define automaticamente `coverHref`.
 */
public class CbzParser : FormatParser {

    override val id: String = "cbz"

    override fun canParse(source: Source): Boolean =
        source.extension() == "cbz"

    override fun parse(source: Source): Publication {
        val zip = source.openZip()
        val imageEntries = try {
            zip.entryNames()
                .filter { isComicImagePath(it) }
                .sortedWith(::naturalCompare)
        } catch (e: ReaderException) {
            zip.close()
            throw e
        } catch (e: Exception) {
            zip.close()
            throw ReaderException.InvalidContent("Falha ao abrir arquivo CBZ '${source.name}'", e)
        }

        if (imageEntries.isEmpty()) {
            zip.close()
            throw ReaderException.InvalidContent("Arquivo CBZ sem imagens suportadas: '${source.name}'")
        }

        val comicInfo = parseComicInfo(zip, source.name)

        val spine = mutableListOf<String>()
        val resources = mutableListOf<Resource>()
        val toc = mutableListOf<TocEntry>()
        val generatedPages = LinkedHashMap<String, ByteArray>(imageEntries.size)

        val padWidth = imageEntries.size.toString().length.coerceAtLeast(4)
        for ((idx, imageHref) in imageEntries.withIndex()) {
            val pageNumber = idx + 1
            val pageHref = "cbz-page-${pageNumber.toString().padStart(padWidth, '0')}.xhtml"
            val html = buildComicPageHtml(
                title = "${comicInfo.title} — $pageNumber",
                imageHref = imageHref,
                pageNumber = pageNumber,
            )
            generatedPages[pageHref] = html.encodeToByteArray()
            spine.add(pageHref)
            resources.add(Resource(href = pageHref, mediaType = "application/xhtml+xml"))
            toc.add(TocEntry(title = "Página $pageNumber", href = pageHref))
        }

        for (imageHref in imageEntries) {
            resources.add(Resource(href = imageHref, mediaType = guessImageMediaType(imageHref)))
        }

        val provider = CbzResourceProvider(
            delegate = zip,
            generatedPages = generatedPages,
        )

        val metadata = Metadata(
            title = comicInfo.title,
            authors = comicInfo.authors,
            language = comicInfo.language,
            coverHref = imageEntries.first(),
            layout = RenditionLayout.PRE_PAGINATED,
            pageProgressionDirection = comicInfo.direction,
        )

        return Publication(
            metadata = metadata,
            spine = spine,
            resources = resources,
            toc = toc,
            provider = provider,
        )
    }

    private data class ComicMetadata(
        val title: String,
        val authors: List<String>,
        val language: String?,
        val direction: PageProgressionDirection,
    )

    private fun parseComicInfo(zip: ZipResourceProvider, sourceName: String): ComicMetadata {
        val fallbackTitle = sourceName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "CBZ" }
        val comicInfoEntry = zip.entryNames().firstOrNull {
            it.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true)
        } ?: return ComicMetadata(
            title = fallbackTitle,
            authors = emptyList(),
            language = null,
            direction = PageProgressionDirection.DEFAULT,
        )

        val rawXml = runCatching { zip.readString(comicInfoEntry) }.getOrNull()
            ?: return ComicMetadata(
                title = fallbackTitle,
                authors = emptyList(),
                language = null,
                direction = PageProgressionDirection.DEFAULT,
            )

        val doc = XmlTokenizer.parseDocument(rawXml)
        val root = doc.findFirst("ComicInfo") ?: doc

        val explicitTitle = root.findFirst("Title")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
        val series = root.findFirst("Series")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
        val number = root.findFirst("Number")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedTitle = when {
            explicitTitle != null -> explicitTitle
            series != null && number != null -> "$series #$number"
            series != null -> series
            else -> fallbackTitle
        }

        val authors = listOf("Writer", "Penciller", "Inker", "Colorist", "Author")
            .flatMap { tag -> root.findAll(tag) }
            .flatMap { el -> el.collectText().split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val language = root.findFirst("LanguageISO")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
        val mangaFlag = root.findFirst("Manga")?.collectText()?.trim()?.lowercase().orEmpty()
        val direction = if (mangaFlag == "yes" || mangaFlag == "yesandrighttoleft") {
            PageProgressionDirection.RTL
        } else {
            PageProgressionDirection.DEFAULT
        }

        return ComicMetadata(
            title = resolvedTitle,
            authors = authors,
            language = language,
            direction = direction,
        )
    }

    private fun buildComicPageHtml(title: String, imageHref: String, pageNumber: Int): String {
        val safeTitle = escapeHtml(title)
        val encodedSrc = percentEncodePathSegments(imageHref)
        val safeAlt = escapeHtml("Página $pageNumber")
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes"/>
              <title>$safeTitle</title>
              <style>
                html,body{margin:0;padding:0;width:100%;height:100%;display:flex;align-items:center;justify-content:center;overflow:hidden;}
                img.cbz-page{display:block;max-width:100%;max-height:100vh;width:auto;height:auto;object-fit:contain;margin:auto;}
              </style>
            </head>
            <body>
              <img class="cbz-page" src="$encodedSrc" alt="$safeAlt"/>
            </body>
            </html>
        """.trimIndent()
    }

    private fun isComicImagePath(path: String): Boolean {
        if (path.startsWith("__MACOSX/", ignoreCase = true) || path.contains("/__MACOSX/", ignoreCase = true)) {
            return false
        }
        val fileName = path.substringAfterLast('/')
        if (fileName.isEmpty() || fileName.startsWith(".")) return false
        val ext = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return ext in supportedImageExtensions
    }

    private fun guessImageMediaType(href: String): String {
        return when (href.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "avif" -> "image/avif"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }
    }

    private fun escapeHtml(input: String): String = buildString(input.length) {
        for (c in input) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun percentEncodePathSegments(path: String): String =
        path.split('/').joinToString("/") { segment ->
            buildString(segment.length) {
                for (b in segment.encodeToByteArray()) {
                    val code = b.toInt() and 0xFF
                    val ch = code.toChar()
                    val isUnreserved = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                        ch == '-' || ch == '_' || ch == '.' || ch == '~'
                    if (isUnreserved) {
                        append(ch)
                    } else {
                        append('%')
                        val hex = code.toString(16).uppercase()
                        if (hex.length < 2) append('0')
                        append(hex)
                    }
                }
            }
        }

    private class CbzResourceProvider(
        private val delegate: ZipResourceProvider,
        private val generatedPages: Map<String, ByteArray>,
    ) : ResourceProvider {
        override fun read(href: String): ByteArray {
            val clean = normalizeHref(href)
            generatedPages[clean]?.let { return it }
            val decoded = normalizeHref(percentDecodePath(href))
            generatedPages[decoded]?.let { return it }
            return try {
                delegate.read(clean)
            } catch (e: ReaderException.ResourceNotFound) {
                if (decoded != clean) delegate.read(decoded) else throw e
            }
        }

        override fun size(href: String): Long? {
            val clean = normalizeHref(href)
            generatedPages[clean]?.let { return it.size.toLong() }
            val decoded = normalizeHref(percentDecodePath(href))
            generatedPages[decoded]?.let { return it.size.toLong() }
            return delegate.size(clean) ?: if (decoded != clean) delegate.size(decoded) else null
        }

        override fun readRange(href: String, offset: Long, byteCount: Int): ByteArray {
            val clean = normalizeHref(href)
            val generated = generatedPages[clean] ?: generatedPages[normalizeHref(percentDecodePath(href))]
            if (generated != null) {
                if (offset < 0L || offset >= generated.size.toLong() || byteCount <= 0) return ByteArray(0)
                val start = offset.toInt()
                val end = (start + byteCount).coerceAtMost(generated.size)
                return generated.copyOfRange(start, end)
            }
            return try {
                delegate.readRange(clean, offset, byteCount)
            } catch (e: ReaderException.ResourceNotFound) {
                val decoded = normalizeHref(percentDecodePath(href))
                if (decoded != clean) delegate.readRange(decoded, offset, byteCount) else throw e
            }
        }

        override fun cacheStats(): ZipCacheStats? = delegate.cacheStats()

        override fun close() {
            delegate.close()
        }

        private fun percentDecodePath(raw: String): String {
            val out = ByteArray(raw.length)
            var outIdx = 0
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '%' && i + 2 < raw.length) {
                    val hex = raw.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex != null) {
                        out[outIdx++] = hex.toByte()
                        i += 3
                        continue
                    }
                }
                val bytes = c.toString().encodeToByteArray()
                for (b in bytes) out[outIdx++] = b
                i++
            }
            return out.decodeToString(0, outIdx)
        }
    }

    public companion object {
        private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "svg")

        /**
         * Comparação em ordem natural (ex.: `"page2.jpg"` antes de `"page10.jpg"`).
         */
        internal fun naturalCompare(a: String, b: String): Int {
            var ia = 0
            var ib = 0
            while (ia < a.length && ib < b.length) {
                val ca = a[ia]
                val cb = b[ib]
                if (ca.isDigit() && cb.isDigit()) {
                    val startA = ia
                    val startB = ib
                    while (ia < a.length && a[ia].isDigit()) ia++
                    while (ib < b.length && b[ib].isDigit()) ib++
                    val rawNumA = a.substring(startA, ia)
                    val rawNumB = b.substring(startB, ib)
                    val trimA = rawNumA.trimStart('0')
                    val trimB = rawNumB.trimStart('0')
                    if (trimA.length != trimB.length) return trimA.length.compareTo(trimB.length)
                    val numCmp = trimA.compareTo(trimB)
                    if (numCmp != 0) return numCmp
                    if (rawNumA.length != rawNumB.length) return rawNumA.length.compareTo(rawNumB.length)
                } else {
                    val la = ca.lowercaseChar()
                    val lb = cb.lowercaseChar()
                    if (la != lb) return la.compareTo(lb)
                    if (ca != cb) return ca.compareTo(cb)
                    ia++
                    ib++
                }
            }
            return (a.length - ia).compareTo(b.length - ib)
        }
    }
}

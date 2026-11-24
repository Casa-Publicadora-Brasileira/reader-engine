package com.readerengine.core.parser

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.Publication
import com.readerengine.core.model.RenditionLayout
import com.readerengine.core.model.Resource
import com.readerengine.core.model.ResourceProvider
import com.readerengine.core.model.TocEntry
import com.readerengine.core.pdf.PdfDocumentBackend
import com.readerengine.core.pdf.PdfHeaderScanner
import com.readerengine.core.pdf.PdfOutlineItem
import com.readerengine.core.pdf.PdfPageSize
import com.readerengine.core.pdf.PdfTextExtractor
import com.readerengine.core.pdf.openPlatformPdfBackend
import com.readerengine.core.provider.normalizeHref

/**
 * Parser híbrido nativo para documentos **PDF** (`.pdf`).
 *
 * Delega a abertura e rasterização para o motor nativo da plataforma via [PdfDocumentBackend]
 * (`android.graphics.pdf.PdfRenderer` no Android, `PDFKit` no iOS e scanner estrutural na JVM),
 * sem adicionar dependências externas ao `:reader-core`.
 *
 * Integra o PDF diretamente à [com.readerengine.core.session.ReaderSession] com `RenditionLayout.PRE_PAGINATED`,
 * sumário (`PDFOutline`), capa (`pdf-page-0.png`), páginas XHTML compatíveis com WebView
 * e API nativa de renderização em alta resolução (`renderPdfPagePng`).
 */
public class PdfParser(
    private val customBackendFactory: ((Source) -> PdfDocumentBackend)? = null,
    private val textExtractor: PdfTextExtractor? = null,
) : FormatParser {

    public constructor() : this(customBackendFactory = null, textExtractor = null)

    override val id: String = "pdf"

    override fun canParse(source: Source): Boolean {
        if (source.extension() == "pdf") return true
        return runCatching { PdfHeaderScanner.hasPdfMagicHeader(source.bytes) }.getOrDefault(false)
    }

    override fun parse(source: Source): Publication {
        val backend = customBackendFactory?.invoke(source) ?: openPlatformPdfBackend(source)
        val info = backend.info
        if (info.pageCount <= 0) {
            backend.close()
            throw ReaderException.InvalidContent("PDF sem páginas legíveis: '${source.name}'")
        }

        val title = info.title?.takeIf { it.isNotBlank() } ?: source.name.substringBeforeLast('.')
        val authors = listOfNotNull(info.author?.takeIf { it.isNotBlank() })
        val spine = mutableListOf<String>()
        val resources = mutableListOf<Resource>()
        val pageCount = info.pageCount
        val padWidth = pageCount.toString().length.coerceAtLeast(4)

        for (idx in 0 until pageCount) {
            val pageHref = pageHref(idx, padWidth)
            val imgHref = imageHref(idx, padWidth)
            spine.add(pageHref)
            resources.add(Resource(href = pageHref, mediaType = "application/xhtml+xml"))
            resources.add(Resource(href = imgHref, mediaType = "image/png"))
        }

        val toc = if (info.outline.isNotEmpty()) {
            mapOutline(info.outline, pageCount, padWidth)
        } else {
            (0 until pageCount).map { idx ->
                TocEntry(title = "Página ${idx + 1}", href = pageHref(idx, padWidth))
            }
        }

        val provider = PdfResourceProvider(
            source = source,
            backend = backend,
            textExtractor = textExtractor,
            title = title,
            pageCount = pageCount,
            padWidth = padWidth,
        )

        val metadata = Metadata(
            title = title,
            authors = authors,
            coverHref = imageHref(0, padWidth),
            layout = RenditionLayout.PRE_PAGINATED,
        )

        return Publication(
            metadata = metadata,
            spine = spine,
            resources = resources,
            toc = toc,
            provider = provider,
        )
    }

    private fun mapOutline(items: List<PdfOutlineItem>, pageCount: Int, padWidth: Int): List<TocEntry> =
        items.map { item ->
            val clamped = item.pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            TocEntry(
                title = item.title,
                href = pageHref(clamped, padWidth),
                children = mapOutline(item.children, pageCount, padWidth),
            )
        }

    private fun pageHref(idx: Int, padWidth: Int): String =
        "pdf-page-${idx.toString().padStart(padWidth, '0')}.xhtml"

    private fun imageHref(idx: Int, padWidth: Int): String =
        "pdf-page-${idx.toString().padStart(padWidth, '0')}.png"
}

/**
 * [ResourceProvider] lazy para páginas PDF: gera HTML/PNG sob demanda e expõe acesso
 * direto ao [PdfDocumentBackend] para renderização vetorial nativa na [com.readerengine.core.session.ReaderSession].
 */
public class PdfResourceProvider internal constructor(
    private val source: Source,
    internal val backend: PdfDocumentBackend,
    private val textExtractor: PdfTextExtractor?,
    private val title: String,
    private val pageCount: Int,
    private val padWidth: Int,
) : ResourceProvider {

    public fun pageSize(pageIndex: Int): PdfPageSize = backend.pageSize(pageIndex)

    public fun renderPagePng(pageIndex: Int, widthPx: Int, heightPx: Int = 0): ByteArray =
        backend.renderPagePng(pageIndex = pageIndex, widthPx = widthPx, heightPx = heightPx)

    public fun extractPageText(pageIndex: Int): String {
        val nativeText = runCatching { backend.extractPageText(pageIndex) }.getOrDefault("")
        if (nativeText.isNotBlank()) return nativeText
        return textExtractor?.let { runCatching { it.extractText(source, pageIndex) }.getOrDefault("") }
            .orEmpty()
    }

    override fun read(href: String): ByteArray {
        val clean = normalizeHref(href)
        val idx = parsePageIndex(clean)
            ?: throw ReaderException.ResourceNotFound(href)
        if (idx !in 0 until pageCount) {
            throw ReaderException.ResourceNotFound(href)
        }
        return when {
            clean.endsWith(".png", ignoreCase = true) ->
                backend.renderPagePng(pageIndex = idx, widthPx = DEFAULT_WEBVIEW_RENDER_WIDTH_PX, heightPx = 0)
            clean.endsWith(".xhtml", ignoreCase = true) -> {
                val imgHref = "pdf-page-${idx.toString().padStart(padWidth, '0')}.png"
                val extractedText = extractPageText(idx)
                buildPdfPageHtml(title = title, pageNumber = idx + 1, imageHref = imgHref, accessibleText = extractedText)
                    .encodeToByteArray()
            }
            else -> throw ReaderException.ResourceNotFound(href)
        }
    }

    override fun close() {
        backend.close()
    }

    private fun parsePageIndex(cleanHref: String): Int? {
        val fileName = cleanHref.substringAfterLast('/')
        if (!fileName.startsWith("pdf-page-")) return null
        val numPart = fileName.removePrefix("pdf-page-").substringBefore('.')
        return numPart.toIntOrNull()
    }

    private fun buildPdfPageHtml(
        title: String,
        pageNumber: Int,
        imageHref: String,
        accessibleText: String,
    ): String {
        val escapedTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val hiddenText = if (accessibleText.isNotBlank()) {
            val escaped = accessibleText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            "<div class=\"pdf-text-layer\" style=\"position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;\">$escaped</div>"
        } else {
            ""
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes\">" +
            "<title>$escapedTitle — $pageNumber</title>" +
            "<style>html,body{margin:0;padding:0;width:100%;height:100%;display:flex;align-items:center;justify-content:center;background:#111;}" +
            "img{max-width:100%;max-height:100%;object-fit:contain;display:block;}</style>" +
            "</head><body><img src=\"$imageHref\" alt=\"Página $pageNumber\"/>$hiddenText</body></html>"
    }

    private companion object {
        private const val DEFAULT_WEBVIEW_RENDER_WIDTH_PX = 1080
    }
}

package com.readerengine.core.pdf

import com.readerengine.core.parser.Source
import kotlinx.serialization.Serializable

/** Dimensões em pontos tipográficos (1/72 polegada) de uma página PDF. */
@Serializable
public data class PdfPageSize(
    val widthPt: Float,
    val heightPt: Float,
)

/** Item hierárquico do sumário (`Outline` / Bookmarks) de um PDF. */
@Serializable
public data class PdfOutlineItem(
    val title: String,
    val pageIndex: Int,
    val children: List<PdfOutlineItem> = emptyList(),
)

/** Metadados estruturais básicos extraídos de um documento PDF. */
@Serializable
public data class PdfDocumentInfo(
    val pageCount: Int,
    val title: String? = null,
    val author: String? = null,
    val outline: List<PdfOutlineItem> = emptyList(),
)

/**
 * Contrato multiplataforma para acesso nativo a documentos PDF:
 * - **Android (`androidMain`)**: `android.graphics.pdf.PdfRenderer`
 * - **iOS (`iosMain`)**: `PDFKit` (`PDFDocument`, `PDFPage`, `PDFOutline`)
 * - **JVM (`jvmMain`)**: parser estrutural leve + gancho plugável
 */
public interface PdfDocumentBackend {
    public val info: PdfDocumentInfo

    public fun pageSize(pageIndex: Int): PdfPageSize

    public fun extractPageText(pageIndex: Int): String = ""

    public fun renderPagePng(pageIndex: Int, widthPx: Int, heightPx: Int): ByteArray

    public fun close(): Unit = Unit
}

/**
 * Gancho opcional plugável para extração de texto em PDF (útil no Android < API 35 ou JVM
 * caso o app host queira conectar PDFBox/Pdfium sem acoplar o `:reader-core`).
 */
public interface PdfTextExtractor {
    public fun extractText(source: Source, pageIndex: Int): String
}

/**
 * Scanner leve em Kotlin puro (`commonMain`) para validação do cabeçalho `%PDF-`,
 * extração de metadados `/Info` (`/Title`, `/Author`) e estimativa estrutural de contagem de páginas.
 */
internal object PdfHeaderScanner {
    private val titleRegex = Regex("""/Title\s*\(([^)]{1,240})\)""")
    private val authorRegex = Regex("""/Author\s*\(([^)]{1,240})\)""")
    private val pageObjRegex = Regex("""/Type\s*/Page(?![sA-Za-z])""")

    fun hasPdfMagicHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        val limit = bytes.size.coerceAtMost(1024)
        val head = bytes.decodeToString(0, limit)
        return head.contains("%PDF-")
    }

    fun scanBasicInfo(bytes: ByteArray): PdfDocumentInfo {
        val text = bytes.decodeToString()
        val title = titleRegex.find(text)?.groupValues?.getOrNull(1)?.let(::unescapePdfLiteral)?.trim()
            ?.takeIf { it.isNotEmpty() }
        val author = authorRegex.find(text)?.groupValues?.getOrNull(1)?.let(::unescapePdfLiteral)?.trim()
            ?.takeIf { it.isNotEmpty() }
        val count = pageObjRegex.findAll(text).count().coerceAtLeast(1)
        return PdfDocumentInfo(
            pageCount = count,
            title = title,
            author = author,
        )
    }

    private fun unescapePdfLiteral(raw: String): String = raw
        .replace("\\(", "(")
        .replace("\\)", ")")
        .replace("\\\\", "\\")
        .replace("\\n", " ")
        .replace("\\r", " ")
}

internal expect fun openPlatformPdfBackend(source: Source): PdfDocumentBackend

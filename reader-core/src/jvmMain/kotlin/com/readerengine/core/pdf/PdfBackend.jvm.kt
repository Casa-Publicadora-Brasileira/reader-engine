package com.readerengine.core.pdf

import com.readerengine.core.error.ReaderException
import com.readerengine.core.parser.Source

internal actual fun openPlatformPdfBackend(source: Source): PdfDocumentBackend {
    val bytes = source.bytes
    if (!PdfHeaderScanner.hasPdfMagicHeader(bytes)) {
        throw ReaderException.InvalidContent("Cabeçalho %PDF- ausente em '${source.name}'")
    }
    val info = PdfHeaderScanner.scanBasicInfo(bytes)
    return JvmLightweightPdfBackend(info = info)
}

private class JvmLightweightPdfBackend(
    override val info: PdfDocumentInfo,
) : PdfDocumentBackend {

    override fun pageSize(pageIndex: Int): PdfPageSize = PdfPageSize(widthPt = 595f, heightPt = 842f)

    override fun renderPagePng(pageIndex: Int, widthPx: Int, heightPx: Int): ByteArray =
        if (pageIndex in 0 until info.pageCount) FALLBACK_1X1_PNG else ByteArray(0)

    private companion object {
        private val FALLBACK_1X1_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x3F,
            0x00, 0x05, 0xFE.toByte(), 0x02, 0xFE.toByte(), 0xDC.toByte(), 0xCC.toByte(), 0x59,
            0xE7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
            0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}

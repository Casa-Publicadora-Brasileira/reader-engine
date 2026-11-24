package com.readerengine.core.pdf

import com.readerengine.core.error.ReaderException
import com.readerengine.core.parser.Source
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFDocumentAuthorAttribute
import platform.PDFKit.PDFDocumentTitleAttribute
import platform.PDFKit.PDFOutline
import platform.PDFKit.kPDFDisplayBoxMediaBox
import platform.UIKit.UIImagePNGRepresentation
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun openPlatformPdfBackend(source: Source): PdfDocumentBackend {
    val pdfDoc: PDFDocument = if (source.path != null) {
        val url = NSURL.fileURLWithPath(source.path.toString())
        PDFDocument(uRL = url)
    } else {
        val bytes = source.bytes
        if (!PdfHeaderScanner.hasPdfMagicHeader(bytes)) {
            throw ReaderException.InvalidContent("Cabeçalho %PDF- ausente em '${source.name}'")
        }
        val nsData = bytes.toNSData()
        PDFDocument(data = nsData)
    }

    val attrs = pdfDoc.documentAttributes
    val title = (attrs?.get(PDFDocumentTitleAttribute) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val author = (attrs?.get(PDFDocumentAuthorAttribute) as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val outline = extractOutline(pdfDoc, pdfDoc.outlineRoot)

    val info = PdfDocumentInfo(
        pageCount = pdfDoc.pageCount.toInt().coerceAtLeast(0),
        title = title,
        author = author,
        outline = outline,
    )
    return IosPdfDocumentBackend(document = pdfDoc, info = info)
}

@OptIn(ExperimentalForeignApi::class)
private class IosPdfDocumentBackend(
    private val document: PDFDocument,
    override val info: PdfDocumentInfo,
) : PdfDocumentBackend {

    override fun pageSize(pageIndex: Int): PdfPageSize {
        val page = document.pageAtIndex(pageIndex.toULong()) ?: return PdfPageSize(595f, 842f)
        val bounds = page.boundsForBox(kPDFDisplayBoxMediaBox)
        return bounds.useContents {
            PdfPageSize(
                widthPt = size.width.toFloat().coerceAtLeast(1f),
                heightPt = size.height.toFloat().coerceAtLeast(1f),
            )
        }
    }

    override fun extractPageText(pageIndex: Int): String {
        val page = document.pageAtIndex(pageIndex.toULong()) ?: return ""
        return page.string?.trim().orEmpty()
    }

    override fun renderPagePng(pageIndex: Int, widthPx: Int, heightPx: Int): ByteArray {
        val page = document.pageAtIndex(pageIndex.toULong()) ?: return ByteArray(0)
        val pSize = pageSize(pageIndex)
        val safeW = widthPx.coerceIn(64, 4096).toDouble()
        val aspect = if (pSize.widthPt > 0f) pSize.heightPt.toDouble() / pSize.widthPt.toDouble() else 1.414
        val safeH = if (heightPx > 0) heightPx.coerceIn(64, 4096).toDouble() else (safeW * aspect).coerceIn(64.0, 4096.0)
        val image = page.thumbnailOfSize(CGSizeMake(safeW, safeH), forBox = kPDFDisplayBoxMediaBox)
        val pngData = UIImagePNGRepresentation(image) ?: return ByteArray(0)
        return pngData.toByteArray()
    }
}

private fun extractOutline(doc: PDFDocument, root: PDFOutline?): List<PdfOutlineItem> {
    if (root == null) return emptyList()
    val count = root.numberOfChildren.toInt()
    if (count <= 0) return emptyList()
    val items = mutableListOf<PdfOutlineItem>()
    for (i in 0 until count) {
        val child = root.childAtIndex(i.toULong()) ?: continue
        val label = child.label?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val destPage = child.destination?.page
        val pageIdx = if (destPage != null) {
            doc.indexForPage(destPage).toInt().coerceAtLeast(0)
        } else {
            0
        }
        items.add(
            PdfOutlineItem(
                title = label,
                pageIndex = pageIdx,
                children = extractOutline(doc, child),
            ),
        )
    }
    return items
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len <= 0) return ByteArray(0)
    val bytesPtr = bytes ?: return ByteArray(0)
    val out = ByteArray(len)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytesPtr, len.toULong())
    }
    return out
}

package com.readerengine.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.readerengine.core.error.ReaderException
import com.readerengine.core.parser.Source
import okio.FileSystem
import java.io.ByteArrayOutputStream
import java.io.File

internal actual fun openPlatformPdfBackend(source: Source): PdfDocumentBackend {
    var tempFile: File? = null
    val targetFile: File = if (source.path != null && source.fileSystem === FileSystem.SYSTEM) {
        source.path.toFile()
    } else {
        val bytes = source.bytes
        if (!PdfHeaderScanner.hasPdfMagicHeader(bytes)) {
            throw ReaderException.InvalidContent("Cabeçalho %PDF- ausente em '${source.name}'")
        }
        val tmp = File.createTempFile("reader-engine-", ".pdf")
        tmp.writeBytes(bytes)
        tempFile = tmp
        tmp
    }

    val basicInfo = runCatching {
        val sample = if (targetFile.length() <= 2L * 1024L * 1024L) {
            targetFile.readBytes()
        } else {
            ByteArray(0)
        }
        if (sample.isNotEmpty()) PdfHeaderScanner.scanBasicInfo(sample) else null
    }.getOrNull()

    val pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        runCatching { pfd.close() }
        runCatching { tempFile?.delete() }
        throw ReaderException.InvalidContent("Falha ao abrir PDF '${source.name}': ${e.message}", e)
    }

    val info = PdfDocumentInfo(
        pageCount = renderer.pageCount.coerceAtLeast(0),
        title = basicInfo?.title,
        author = basicInfo?.author,
    )
    return AndroidPdfDocumentBackend(
        renderer = renderer,
        pfd = pfd,
        tempFile = tempFile,
        info = info,
    )
}

private class AndroidPdfDocumentBackend(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
    private val tempFile: File?,
    override val info: PdfDocumentInfo,
) : PdfDocumentBackend {

    private val lock = Any()

    override fun pageSize(pageIndex: Int): PdfPageSize = synchronized(lock) {
        if (pageIndex !in 0 until info.pageCount) return PdfPageSize(595f, 842f)
        renderer.openPage(pageIndex).use { page ->
            PdfPageSize(widthPt = page.width.toFloat(), heightPt = page.height.toFloat())
        }
    }

    override fun renderPagePng(pageIndex: Int, widthPx: Int, heightPx: Int): ByteArray = synchronized(lock) {
        if (pageIndex !in 0 until info.pageCount) return ByteArray(0)
        val safeW = widthPx.coerceIn(64, 4096)
        renderer.openPage(pageIndex).use { page ->
            val aspect = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.414f
            val safeH = if (heightPx > 0) heightPx.coerceIn(64, 4096) else (safeW * aspect).toInt().coerceIn(64, 4096)
            val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                out.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun close() = synchronized(lock) {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
        runCatching { tempFile?.delete() }
        Unit
    }
}

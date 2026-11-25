package com.readerengine.ui

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.readerengine.core.bridge.ReaderJsBridge
import com.readerengine.core.model.FormEntry
import com.readerengine.core.model.ReadingMode
import com.readerengine.core.session.ReaderSession
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream

/** Fatia calculada a partir de um cabeçalho HTTP `Range: bytes=...`. */
public data class ByteRangeSlice(
    val start: Long,
    val endInclusive: Long,
    val totalSize: Long,
) {
    public val length: Int
        get() = (endInclusive - start + 1L).toInt()

    public val contentRangeHeader: String
        get() = "bytes $start-$endInclusive/$totalSize"
}

/** Resultado do parser de cabeçalhos HTTP `Range` para streaming 206/416. */
public sealed interface ByteRangeParseResult {
    public data class Valid(val slice: ByteRangeSlice) : ByteRangeParseResult
    public data class Unsatisfiable(val totalSize: Long) : ByteRangeParseResult
    public data object Invalid : ByteRangeParseResult
}

/**
 * Faz o parse de um cabeçalho HTTP `Range: bytes=<start>-<end?>`, limitando cada resposta
 * parcial a [maxChunkBytes] (padrão 1 MB) para streaming eficiente de vídeo e áudio.
 */
public fun parseByteRangeHeader(
    rangeHeader: String?,
    totalSize: Long,
    maxChunkBytes: Long = 1024L * 1024L,
): ByteRangeParseResult {
    if (rangeHeader.isNullOrBlank() || totalSize <= 0L || maxChunkBytes <= 0L) {
        return ByteRangeParseResult.Invalid
    }
    val trimmed = rangeHeader.trim()
    if (!trimmed.startsWith("bytes=", ignoreCase = true)) {
        return ByteRangeParseResult.Invalid
    }
    val rangeValue = trimmed.substring("bytes=".length).substringBefore(',').trim()
    val dashIndex = rangeValue.indexOf('-')
    if (dashIndex < 0) return ByteRangeParseResult.Invalid

    val startRaw = rangeValue.substring(0, dashIndex).trim()
    val endRaw = rangeValue.substring(dashIndex + 1).trim()
    if (startRaw.isEmpty()) return ByteRangeParseResult.Invalid

    val start = startRaw.toLongOrNull() ?: return ByteRangeParseResult.Invalid
    val endRequested = if (endRaw.isEmpty()) null else endRaw.toLongOrNull() ?: return ByteRangeParseResult.Invalid

    if (start < 0L || start >= totalSize) {
        return ByteRangeParseResult.Unsatisfiable(totalSize)
    }
    if (endRequested != null && endRequested < start) {
        return ByteRangeParseResult.Unsatisfiable(totalSize)
    }

    val boundedEnd = if (endRequested != null) {
        minOf(endRequested, totalSize - 1L, start + maxChunkBytes - 1L)
    } else {
        minOf(start + maxChunkBytes - 1L, totalSize - 1L)
    }
    return ByteRangeParseResult.Valid(
        ByteRangeSlice(
            start = start,
            endInclusive = boundedEnd,
            totalSize = totalSize,
        ),
    )
}

/** Infere o MIME type apropriado a partir da extensão de um recurso interno do livro. */
public fun guessReaderMimeType(href: String): String {
    val lower = href.substringBefore('#').substringBefore('?').lowercase()
    return when {
        lower.endsWith(".html") || lower.endsWith(".xhtml") -> "application/xhtml+xml"
        lower.endsWith(".css") -> "text/css"
        lower.endsWith(".js") -> "application/javascript"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".svg") -> "image/svg+xml"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".woff2") -> "font/woff2"
        lower.endsWith(".woff") -> "font/woff"
        lower.endsWith(".ttf") -> "font/ttf"
        lower.endsWith(".otf") -> "font/otf"
        lower.endsWith(".mp4") || lower.endsWith(".m4v") -> "video/mp4"
        lower.endsWith(".webm") -> "video/webm"
        lower.endsWith(".ogv") -> "video/ogg"
        lower.endsWith(".mov") -> "video/quicktime"
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".m4a") -> "audio/mp4"
        lower.endsWith(".aac") -> "audio/aac"
        lower.endsWith(".wav") -> "audio/wav"
        lower.endsWith(".ogg") || lower.endsWith(".oga") -> "audio/ogg"
        lower.endsWith(".smil") || lower.endsWith(".xml") -> "application/xml"
        lower.endsWith(".json") -> "application/json"
        else -> "application/octet-stream"
    }
}

/**
 * `WebViewClient` reutilizável para sessões do `reader-core`.
 *
 * Responsabilidades:
 * - Servir recursos internos via esquema `book://content/` com suporte completo a HTTP 200,
 *   HTTP 206 Partial Content (streaming por range de vídeo/áudio) e HTTP 416.
 * - Delegar interceptação de links (`verse://`, `footnote://`, âncoras e capítulos internos)
 *   para `session.handleUrlNavigation(url)`.
 * - Restaurar highlights, formulários (`annotations`/`answers`), `scrollY` e estado de paginação
 *   automaticamente em `onPageFinished`.
 */
public open class ReaderResourceWebViewClient(
    private val sessionProvider: () -> ReaderSession?,
    private val scheme: String = "book",
    private val host: String = "content",
) : WebViewClient() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        val session = sessionProvider() ?: return false
        return session.handleUrlNavigation(url)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val webView = view ?: return
        val session = sessionProvider() ?: return
        val state = session.state.value

        if (state.currentPageSerializedHighlights.isNotBlank()) {
            val restoreCmd = ReaderJsBridge.setHighlightsCommand(state.currentPageSerializedHighlights)
            webView.evaluateJavascript(restoreCmd.removePrefix("javascript:"), null)
        }

        if (state.currentPageFormState.annotations.isNotEmpty()) {
            val annotationsRaw = json.encodeToString(
                ListSerializer(FormEntry.serializer()),
                state.currentPageFormState.annotations,
            )
            val cmd = ReaderJsBridge.setAnnotationsCommand(annotationsRaw)
            webView.evaluateJavascript(cmd.removePrefix("javascript:"), null)
        }

        if (state.currentPageFormState.answers.isNotEmpty()) {
            val answersRaw = json.encodeToString(
                ListSerializer(FormEntry.serializer()),
                state.currentPageFormState.answers,
            )
            val cmd = ReaderJsBridge.setAnswersCommand(answersRaw)
            webView.evaluateJavascript(cmd.removePrefix("javascript:"), null)
        }

        if (state.scrollY > 0 && state.readingMode == ReadingMode.SCROLL) {
            val scrollCmd = ReaderJsBridge.scrollToYCommand(state.scrollY)
            webView.evaluateJavascript(scrollCmd.removePrefix("javascript:"), null)
        }

        if (state.readingMode == ReadingMode.PAGINATED) {
            val density = webView.resources.displayMetrics.density.coerceAtLeast(1f)
            val widthCssPx = (webView.width / density).toInt()
            val heightCssPx = (webView.height / density).toInt()
            val script = if (widthCssPx >= 120 && heightCssPx >= 120) {
                ReaderJsBridge.setViewportSizeCommand(widthCssPx, heightCssPx).removePrefix("javascript:")
            } else {
                "readerJS.getSubPageState()"
            }
            webView.evaluateJavascript(script) { raw ->
                ReaderJsBridge.parseSubPageState(raw)?.let { (sub, total) ->
                    session.onSubPageReported(sub, total)
                }
            }
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        if (url.scheme != scheme || url.host != host) {
            return super.shouldInterceptRequest(view, request)
        }

        val session = sessionProvider() ?: return notFoundResponse()
        val href = url.path
            ?.removePrefix("/")
            ?.substringBefore('#')
            ?.substringBefore('?')
            ?: return notFoundResponse()
        if (href.isEmpty()) return notFoundResponse()

        return try {
            val totalSize = session.resourceSize(href) ?: return notFoundResponse()
            val mimeType = session.resourceMediaType(href) ?: guessReaderMimeType(href)
            val rangeHeader = request.requestHeaders?.entries
                ?.firstOrNull { it.key.equals("Range", ignoreCase = true) }
                ?.value

            when (val rangeResult = parseByteRangeHeader(rangeHeader, totalSize)) {
                is ByteRangeParseResult.Valid -> {
                    val slice = rangeResult.slice
                    val chunk = session.readResourceRange(href, slice.start, slice.length)
                    val actualEnd = slice.start + chunk.size - 1L
                    val responseHeaders = mutableMapOf(
                        "Content-Range" to "bytes ${slice.start}-$actualEnd/${slice.totalSize}",
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to chunk.size.toString(),
                        "Content-Type" to mimeType,
                        "Access-Control-Allow-Origin" to "*",
                    )
                    WebResourceResponse(
                        mimeType,
                        "UTF-8",
                        206,
                        "Partial Content",
                        responseHeaders,
                        ByteArrayInputStream(chunk),
                    )
                }

                is ByteRangeParseResult.Unsatisfiable -> {
                    val responseHeaders = mutableMapOf(
                        "Content-Range" to "bytes */${rangeResult.totalSize}",
                        "Accept-Ranges" to "bytes",
                    )
                    WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        416,
                        "Range Not Satisfiable",
                        responseHeaders,
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }

                ByteRangeParseResult.Invalid -> {
                    val bytes = session.readResource(href)
                    val responseHeaders = mutableMapOf(
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to bytes.size.toString(),
                        "Content-Type" to mimeType,
                        "Access-Control-Allow-Origin" to "*",
                    )
                    WebResourceResponse(
                        mimeType,
                        "UTF-8",
                        200,
                        "OK",
                        responseHeaders,
                        ByteArrayInputStream(bytes),
                    )
                }
            }
        } catch (_: Exception) {
            notFoundResponse()
        }
    }

    private fun notFoundResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream("Resource not found".toByteArray()),
    )
}

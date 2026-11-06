package com.readerengine.core.bridge

import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.GzipSink
import okio.GzipSource
import okio.buffer

/**
 * Utilitário multiplataforma (`commonMain`) para compressão/descompressão transparente
 * no formato `"gz:<base64>"` para payloads grandes (como grifos ou formulários extensos),
 * compatível com bancos de documentos com limite de payload (ex: Firestore 1 MiB, REST, SQLite).
 */
object PayloadCodec {
    const val GZIP_PREFIX = "gz:"
    const val DEFAULT_COMPRESS_THRESHOLD_BYTES = 200_000

    fun isCompressed(payload: String): Boolean = payload.startsWith(GZIP_PREFIX)

    fun encodeIfNeeded(
        raw: String,
        thresholdBytes: Int = DEFAULT_COMPRESS_THRESHOLD_BYTES,
    ): String {
        if (raw.startsWith(GZIP_PREFIX)) return raw
        val utf8Bytes = raw.encodeToByteArray()
        if (utf8Bytes.size < thresholdBytes) return raw

        return try {
            val outBuffer = Buffer()
            GzipSink(outBuffer).buffer().use { gzipSink ->
                gzipSink.write(utf8Bytes)
            }
            val compressedBytes = outBuffer.readByteArray()
            GZIP_PREFIX + compressedBytes.toByteString().base64()
        } catch (_: Exception) {
            raw
        }
    }

    fun decodeIfNeeded(stored: String): String {
        if (!stored.startsWith(GZIP_PREFIX)) return stored
        val base64Part = stored.removePrefix(GZIP_PREFIX).trim()
        val decodedBytes = base64Part.decodeBase64() ?: return stored

        return try {
            val sourceBuffer = Buffer().write(decodedBytes)
            GzipSource(sourceBuffer).buffer().use { gzipSource ->
                gzipSource.readUtf8()
            }
        } catch (_: Exception) {
            stored
        }
    }
}

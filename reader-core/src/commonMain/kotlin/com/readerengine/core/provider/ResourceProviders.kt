package com.readerengine.core.provider

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.ResourceProvider
import okio.Buffer
import okio.Inflater
import okio.InflaterSource
import okio.buffer

public class MemoryResourceProvider(
    private val files: Map<String, ByteArray>,
) : ResourceProvider {
    override fun read(href: String): ByteArray {
        val normalized = normalizeHref(href)
        return files[normalized]
            ?: files[href]
            ?: throw ReaderException.ResourceNotFound(href)
    }
}

/** Limites de segurança contra pacotes ZIP malformados ou Zip Bombs. */
public data class ZipLimits(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxEntryUncompressedBytes: Int = DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES,
    val maxTotalUncompressedBytes: Long = DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES,
) {
    public companion object {
        public const val DEFAULT_MAX_ENTRIES: Int = 10_000
        public const val DEFAULT_MAX_ENTRY_UNCOMPRESSED_BYTES: Int = 50 * 1024 * 1024
        public const val DEFAULT_MAX_TOTAL_UNCOMPRESSED_BYTES: Long = 256L * 1024L * 1024L
    }
}

/**
 * Provider ZIP multiplataforma (`commonMain`) que indexa o Central Directory em memória
 * e descomprime entradas `STORED` (0) e `DEFLATED` (8) sob demanda (lazy) com proteção contra Zip Bomb.
 */
public class ZipResourceProvider private constructor(
    private val archiveBytes: ByteArray,
    private val directory: Map<String, ZipEntryMeta>,
    private val limits: ZipLimits,
) : ResourceProvider {

    private val decodedCache = LinkedHashMap<String, ByteArray>()
    private var totalDecodedBytes: Long = 0L

    private data class ZipEntryMeta(
        val name: String,
        val compressionMethod: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
    )

    public fun hasEntry(href: String): Boolean = directory.containsKey(normalizeHref(href))

    public fun entryNames(): Set<String> = directory.keys

    override fun read(href: String): ByteArray {
        val clean = normalizeHref(href)
        decodedCache[clean]?.let { return it }

        val meta = directory[clean]
            ?: throw ReaderException.ResourceNotFound(href)

        val decoded = extractEntryBytes(archiveBytes, meta, limits)
        if (totalDecodedBytes + decoded.size <= limits.maxTotalUncompressedBytes) {
            decodedCache[clean] = decoded
            totalDecodedBytes += decoded.size
        } else {
            throw ReaderException.InvalidContent(
                "Limite total de descompressão do pacote ZIP excedido (${limits.maxTotalUncompressedBytes} bytes)",
            )
        }
        return decoded
    }

    public fun readString(href: String): String = read(href).decodeToString()

    public companion object {
        private const val EOCD_SIGNATURE = 0x06054b50
        private const val CEN_SIGNATURE = 0x02014b50
        private const val LOC_SIGNATURE = 0x04034b50
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8
        private const val ZIP64_MAGIC_16 = 0xFFFF
        private const val ZIP64_MAGIC_32 = 0xFFFFFFFFL

        public fun fromBytes(
            bytes: ByteArray,
            limits: ZipLimits = ZipLimits(),
        ): ZipResourceProvider {
            val directory = indexCentralDirectory(bytes, limits)
            return ZipResourceProvider(
                archiveBytes = bytes,
                directory = directory,
                limits = limits,
            )
        }

        /**
         * Verifica rapidamente no Central Directory se o pacote ZIP contém `entryName`,
         * sem descomprimir nenhuma entrada.
         */
        public fun hasZipEntry(
            bytes: ByteArray,
            entryName: String,
            limits: ZipLimits = ZipLimits(),
        ): Boolean {
            return try {
                val target = normalizeHref(entryName)
                indexCentralDirectory(bytes, limits).containsKey(target)
            } catch (_: Exception) {
                false
            }
        }

        private fun indexCentralDirectory(
            bytes: ByteArray,
            limits: ZipLimits,
        ): Map<String, ZipEntryMeta> {
            if (bytes.size < 22) {
                throw ReaderException.InvalidContent("Arquivo ZIP muito pequeno (${bytes.size} bytes)")
            }

            val eocdOffset = findEocdOffset(bytes)
                ?: throw ReaderException.InvalidContent("Assinatura EOCD do ZIP não encontrada")

            val totalEntries = readUInt16LE(bytes, eocdOffset + 10)
            val centralDirSize = readUInt32LE(bytes, eocdOffset + 12)
            val centralDirOffset = readUInt32LE(bytes, eocdOffset + 16)

            if (totalEntries == ZIP64_MAGIC_16 || centralDirSize == ZIP64_MAGIC_32 || centralDirOffset == ZIP64_MAGIC_32) {
                throw ReaderException.InvalidContent("Arquivos ZIP64 não são suportados")
            }
            if (totalEntries > limits.maxEntries) {
                throw ReaderException.InvalidContent(
                    "Pacote ZIP excede o número máximo de entradas (${totalEntries} > ${limits.maxEntries})",
                )
            }
            if (centralDirOffset < 0L || centralDirSize < 0L || centralDirOffset + centralDirSize > bytes.size.toLong()) {
                throw ReaderException.InvalidContent("Central Directory do ZIP fora dos limites")
            }

            val result = LinkedHashMap<String, ZipEntryMeta>(totalEntries)
            var pos = centralDirOffset.toInt()
            var declaredTotalUncompressed = 0L

            for (i in 0 until totalEntries) {
                if (pos + 46 > bytes.size || readInt32LE(bytes, pos) != CEN_SIGNATURE) {
                    throw ReaderException.InvalidContent("Entrada inválida no Central Directory do ZIP")
                }

                val compressionMethod = readUInt16LE(bytes, pos + 10)
                val compressedSizeLong = readUInt32LE(bytes, pos + 20)
                val uncompressedSizeLong = readUInt32LE(bytes, pos + 24)
                val fileNameLen = readUInt16LE(bytes, pos + 28)
                val extraLen = readUInt16LE(bytes, pos + 30)
                val commentLen = readUInt16LE(bytes, pos + 32)
                val localHeaderOffsetLong = readUInt32LE(bytes, pos + 42)

                val headerEnd = pos.toLong() + 46L + fileNameLen.toLong() + extraLen.toLong() + commentLen.toLong()
                if (headerEnd > bytes.size.toLong() || pos + 46 + fileNameLen > bytes.size) {
                    throw ReaderException.InvalidContent("Cabeçalho de entrada truncado no Central Directory")
                }

                val fileName = bytes.decodeToString(pos + 46, pos + 46 + fileNameLen)
                pos = headerEnd.toInt()

                if (fileName.endsWith("/")) continue

                if (compressedSizeLong == ZIP64_MAGIC_32 || uncompressedSizeLong == ZIP64_MAGIC_32 || localHeaderOffsetLong == ZIP64_MAGIC_32) {
                    throw ReaderException.InvalidContent("Entrada ZIP64 não suportada: '$fileName'")
                }
                if (uncompressedSizeLong > limits.maxEntryUncompressedBytes.toLong()) {
                    throw ReaderException.InvalidContent(
                        "Entrada '$fileName' excede o limite de ${limits.maxEntryUncompressedBytes} bytes",
                    )
                }
                declaredTotalUncompressed += uncompressedSizeLong
                if (declaredTotalUncompressed > limits.maxTotalUncompressedBytes) {
                    throw ReaderException.InvalidContent(
                        "Tamanho total declarado no ZIP excede o limite de ${limits.maxTotalUncompressedBytes} bytes",
                    )
                }
                if (compressedSizeLong > bytes.size.toLong() || localHeaderOffsetLong + 30L > bytes.size.toLong()) {
                    throw ReaderException.InvalidContent("Local File Header fora dos limites para '$fileName'")
                }

                val normalizedName = normalizeHref(fileName)
                if (normalizedName.isEmpty()) continue

                result[normalizedName] = ZipEntryMeta(
                    name = normalizedName,
                    compressionMethod = compressionMethod,
                    compressedSize = compressedSizeLong.toInt(),
                    uncompressedSize = uncompressedSizeLong.toInt(),
                    localHeaderOffset = localHeaderOffsetLong.toInt(),
                )
            }

            return result
        }

        private fun extractEntryBytes(
            bytes: ByteArray,
            meta: ZipEntryMeta,
            limits: ZipLimits,
        ): ByteArray {
            val localHeaderOffset = meta.localHeaderOffset
            if (localHeaderOffset < 0 || localHeaderOffset.toLong() + 30L > bytes.size.toLong() ||
                readInt32LE(bytes, localHeaderOffset) != LOC_SIGNATURE
            ) {
                throw ReaderException.InvalidContent("Local File Header inválido para '${meta.name}'")
            }

            val localNameLen = readUInt16LE(bytes, localHeaderOffset + 26)
            val localExtraLen = readUInt16LE(bytes, localHeaderOffset + 28)
            val dataStartLong = localHeaderOffset.toLong() + 30L + localNameLen.toLong() + localExtraLen.toLong()
            val dataEndLong = dataStartLong + meta.compressedSize.toLong()

            if (dataStartLong < 0L || dataEndLong > bytes.size.toLong()) {
                throw ReaderException.InvalidContent("Dados da entrada '${meta.name}' fora dos limites do ZIP")
            }

            val dataStart = dataStartLong.toInt()
            val dataEnd = dataEndLong.toInt()

            return when (meta.compressionMethod) {
                METHOD_STORED -> {
                    val len = dataEnd - dataStart
                    if (len > limits.maxEntryUncompressedBytes) {
                        throw ReaderException.InvalidContent("Entrada '${meta.name}' excede o limite de bytes")
                    }
                    bytes.copyOfRange(dataStart, dataEnd)
                }
                METHOD_DEFLATED -> inflateBounded(
                    sourceBytes = bytes,
                    offset = dataStart,
                    compressedLength = meta.compressedSize,
                    uncompressedSize = meta.uncompressedSize,
                    maxBytes = limits.maxEntryUncompressedBytes,
                )
                else -> throw ReaderException.InvalidContent(
                    "Método de compressão ZIP não suportado: ${meta.compressionMethod}",
                )
            }
        }

        private fun inflateBounded(
            sourceBytes: ByteArray,
            offset: Int,
            compressedLength: Int,
            uncompressedSize: Int,
            maxBytes: Int,
        ): ByteArray {
            val compressedBuffer = Buffer().write(sourceBytes, offset, compressedLength)
            val inflaterSource = InflaterSource(compressedBuffer, Inflater(true))
            val outBuffer = Buffer()
            return try {
                inflaterSource.buffer().use { buffered ->
                    var totalRead = 0L
                    while (true) {
                        val read = buffered.read(outBuffer, 8192L)
                        if (read == -1L) break
                        totalRead += read
                        if (totalRead > maxBytes.toLong()) {
                            throw ReaderException.InvalidContent(
                                "Entrada DEFLATE excedeu o limite máximo permitido ($maxBytes bytes)",
                            )
                        }
                    }
                    outBuffer.readByteArray()
                }
            } catch (e: ReaderException) {
                throw e
            } catch (e: Exception) {
                if (uncompressedSize == 0) ByteArray(0)
                else throw ReaderException.InvalidContent("Falha ao descomprimir entrada DEFLATE: ${e.message}", e)
            }
        }

        private fun findEocdOffset(bytes: ByteArray): Int? {
            val minOffset = (bytes.size - 65557).coerceAtLeast(0)
            for (i in bytes.size - 22 downTo minOffset) {
                if (readInt32LE(bytes, i) == EOCD_SIGNATURE) {
                    return i
                }
            }
            return null
        }

        private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun readInt32LE(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        }

        private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
            return readInt32LE(bytes, offset).toLong() and 0xFFFFFFFFL
        }
    }
}

internal fun normalizeHref(href: String): String {
    val withoutAnchor = href.substringBefore('#').trim()
    val segments = mutableListOf<String>()
    for (part in withoutAnchor.replace('\\', '/').split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments.add(part)
        }
    }
    return segments.joinToString("/")
}

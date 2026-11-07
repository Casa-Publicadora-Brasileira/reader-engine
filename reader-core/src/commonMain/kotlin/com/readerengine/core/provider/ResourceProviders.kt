package com.readerengine.core.provider

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.ResourceProvider
import okio.Buffer
import okio.FileSystem
import okio.Inflater
import okio.InflaterSource
import okio.Path
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

internal interface RandomAccessSource {
    val size: Long
    fun readSlice(offset: Long, byteCount: Int): ByteArray
}

internal class ByteArrayRandomAccess(
    private val bytes: ByteArray,
) : RandomAccessSource {
    override val size: Long
        get() = bytes.size.toLong()

    override fun readSlice(offset: Long, byteCount: Int): ByteArray {
        if (offset < 0L || byteCount < 0 || offset + byteCount.toLong() > bytes.size.toLong()) {
            throw ReaderException.InvalidContent("Leitura fora dos limites do buffer ZIP ($offset..${offset + byteCount})")
        }
        val start = offset.toInt()
        return bytes.copyOfRange(start, start + byteCount)
    }
}

internal class FileHandleRandomAccess(
    private val fileSystem: FileSystem,
    private val path: Path,
) : RandomAccessSource {
    override val size: Long by lazy {
        val meta = fileSystem.metadataOrNull(path)
            ?: throw ReaderException.InvalidContent("Arquivo não encontrado: $path")
        meta.size ?: fileSystem.openReadOnly(path).use { it.size() }
    }

    override fun readSlice(offset: Long, byteCount: Int): ByteArray {
        if (offset < 0L || byteCount < 0 || offset + byteCount.toLong() > size) {
            throw ReaderException.InvalidContent("Leitura fora dos limites do arquivo ZIP ($offset..${offset + byteCount})")
        }
        if (byteCount == 0) return ByteArray(0)
        return fileSystem.openReadOnly(path).use { handle ->
            val buffer = Buffer()
            var remaining = byteCount.toLong()
            var currentPos = offset
            while (remaining > 0L) {
                val read = handle.read(currentPos, buffer, remaining)
                if (read <= 0L) {
                    throw ReaderException.InvalidContent("Fim prematuro do arquivo ZIP em $path")
                }
                currentPos += read
                remaining -= read
            }
            buffer.readByteArray()
        }
    }
}

/**
 * Provider ZIP multiplataforma (`commonMain`) que indexa apenas o Central Directory
 * (em memória ou via streaming random-access `okio.FileHandle` em disco) e descomprime entradas
 * `STORED` (0) e `DEFLATED` (8) sob demanda (lazy) com proteção contra Zip Bomb.
 */
public class ZipResourceProvider private constructor(
    private val source: RandomAccessSource,
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
        val localHeaderOffset: Long,
    )

    public fun hasEntry(href: String): Boolean = directory.containsKey(normalizeHref(href))

    public fun entryNames(): Set<String> = directory.keys

    override fun read(href: String): ByteArray {
        val clean = normalizeHref(href)
        decodedCache[clean]?.let { return it }

        val meta = directory[clean]
            ?: throw ReaderException.ResourceNotFound(href)

        val decoded = extractEntryBytes(source, meta, limits)
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
        private const val MAX_EOCD_SEARCH_WINDOW = 65557

        public fun fromBytes(
            bytes: ByteArray,
            limits: ZipLimits = ZipLimits(),
        ): ZipResourceProvider {
            val access = ByteArrayRandomAccess(bytes)
            val directory = indexCentralDirectory(access, limits)
            return ZipResourceProvider(
                source = access,
                directory = directory,
                limits = limits,
            )
        }

        /**
         * Cria um [ZipResourceProvider] lendo diretamente de um arquivo em disco via `Okio FileHandle`
         * (random-access), sem carregar o arquivo `.epub` / `.hpub` inteiro na memória RAM.
         */
        public fun fromPath(
            path: Path,
            fileSystem: FileSystem = FileSystem.SYSTEM,
            limits: ZipLimits = ZipLimits(),
        ): ZipResourceProvider {
            val access = FileHandleRandomAccess(fileSystem, path)
            val directory = indexCentralDirectory(access, limits)
            return ZipResourceProvider(
                source = access,
                directory = directory,
                limits = limits,
            )
        }

        /**
         * Verifica rapidamente no Central Directory se o pacote ZIP em memória contém `entryName`,
         * sem descomprimir nenhuma entrada.
         */
        public fun hasZipEntry(
            bytes: ByteArray,
            entryName: String,
            limits: ZipLimits = ZipLimits(),
        ): Boolean {
            return try {
                val target = normalizeHref(entryName)
                indexCentralDirectory(ByteArrayRandomAccess(bytes), limits).containsKey(target)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Verifica rapidamente no Central Directory de um arquivo em disco se contém `entryName`,
         * lendo apenas a janela final do arquivo sem carregá-lo inteiro na RAM.
         */
        public fun hasZipEntry(
            path: Path,
            entryName: String,
            fileSystem: FileSystem = FileSystem.SYSTEM,
            limits: ZipLimits = ZipLimits(),
        ): Boolean {
            return try {
                val target = normalizeHref(entryName)
                indexCentralDirectory(FileHandleRandomAccess(fileSystem, path), limits).containsKey(target)
            } catch (_: Exception) {
                false
            }
        }

        private fun indexCentralDirectory(
            access: RandomAccessSource,
            limits: ZipLimits,
        ): Map<String, ZipEntryMeta> {
            val totalSize = access.size
            if (totalSize < 22L) {
                throw ReaderException.InvalidContent("Arquivo ZIP muito pequeno ($totalSize bytes)")
            }

            val tailWindowSize = minOf(totalSize, MAX_EOCD_SEARCH_WINDOW.toLong()).toInt()
            val tailStartOffset = totalSize - tailWindowSize.toLong()
            val tailBytes = access.readSlice(tailStartOffset, tailWindowSize)

            val eocdRelOffset = findEocdOffsetInWindow(tailBytes)
                ?: throw ReaderException.InvalidContent("Assinatura EOCD do ZIP não encontrada")

            val totalEntries = readUInt16LE(tailBytes, eocdRelOffset + 10)
            val centralDirSize = readUInt32LE(tailBytes, eocdRelOffset + 12)
            val centralDirOffset = readUInt32LE(tailBytes, eocdRelOffset + 16)

            if (totalEntries == ZIP64_MAGIC_16 || centralDirSize == ZIP64_MAGIC_32 || centralDirOffset == ZIP64_MAGIC_32) {
                throw ReaderException.InvalidContent("Arquivos ZIP64 não são suportados")
            }
            if (totalEntries > limits.maxEntries) {
                throw ReaderException.InvalidContent(
                    "Pacote ZIP excede o número máximo de entradas ($totalEntries > ${limits.maxEntries})",
                )
            }
            if (centralDirOffset < 0L || centralDirSize < 0L ||
                centralDirSize > Int.MAX_VALUE.toLong() ||
                centralDirOffset + centralDirSize > totalSize
            ) {
                throw ReaderException.InvalidContent("Central Directory do ZIP fora dos limites")
            }

            val centralDirBytes = access.readSlice(centralDirOffset, centralDirSize.toInt())
            val result = LinkedHashMap<String, ZipEntryMeta>(totalEntries)
            var pos = 0
            var declaredTotalUncompressed = 0L

            for (i in 0 until totalEntries) {
                if (pos + 46 > centralDirBytes.size || readInt32LE(centralDirBytes, pos) != CEN_SIGNATURE) {
                    throw ReaderException.InvalidContent("Entrada inválida no Central Directory do ZIP")
                }

                val compressionMethod = readUInt16LE(centralDirBytes, pos + 10)
                val compressedSizeLong = readUInt32LE(centralDirBytes, pos + 20)
                val uncompressedSizeLong = readUInt32LE(centralDirBytes, pos + 24)
                val fileNameLen = readUInt16LE(centralDirBytes, pos + 28)
                val extraLen = readUInt16LE(centralDirBytes, pos + 30)
                val commentLen = readUInt16LE(centralDirBytes, pos + 32)
                val localHeaderOffsetLong = readUInt32LE(centralDirBytes, pos + 42)

                val headerEnd = pos.toLong() + 46L + fileNameLen.toLong() + extraLen.toLong() + commentLen.toLong()
                if (headerEnd > centralDirBytes.size.toLong() || pos + 46 + fileNameLen > centralDirBytes.size) {
                    throw ReaderException.InvalidContent("Cabeçalho de entrada truncado no Central Directory")
                }

                val fileName = centralDirBytes.decodeToString(pos + 46, pos + 46 + fileNameLen)
                pos = headerEnd.toInt()

                if (fileName.endsWith("/")) continue

                if (compressedSizeLong == ZIP64_MAGIC_32 || uncompressedSizeLong == ZIP64_MAGIC_32 || localHeaderOffsetLong == ZIP64_MAGIC_32) {
                    throw ReaderException.InvalidContent("Entrada ZIP64 não suportada: '$fileName'")
                }
                if (uncompressedSizeLong > limits.maxEntryUncompressedBytes.toLong() ||
                    compressedSizeLong > limits.maxEntryUncompressedBytes.toLong() * 2L
                ) {
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
                if (localHeaderOffsetLong + 30L > totalSize) {
                    throw ReaderException.InvalidContent("Local File Header fora dos limites para '$fileName'")
                }

                val normalizedName = normalizeHref(fileName)
                if (normalizedName.isEmpty()) continue

                result[normalizedName] = ZipEntryMeta(
                    name = normalizedName,
                    compressionMethod = compressionMethod,
                    compressedSize = compressedSizeLong.toInt(),
                    uncompressedSize = uncompressedSizeLong.toInt(),
                    localHeaderOffset = localHeaderOffsetLong,
                )
            }

            return result
        }

        private fun extractEntryBytes(
            access: RandomAccessSource,
            meta: ZipEntryMeta,
            limits: ZipLimits,
        ): ByteArray {
            val localHeaderOffset = meta.localHeaderOffset
            if (localHeaderOffset < 0L || localHeaderOffset + 30L > access.size) {
                throw ReaderException.InvalidContent("Local File Header inválido para '${meta.name}'")
            }

            val localHeader = access.readSlice(localHeaderOffset, 30)
            if (readInt32LE(localHeader, 0) != LOC_SIGNATURE) {
                throw ReaderException.InvalidContent("Assinatura Local File Header inválida para '${meta.name}'")
            }

            val localNameLen = readUInt16LE(localHeader, 26)
            val localExtraLen = readUInt16LE(localHeader, 28)
            val dataStartLong = localHeaderOffset + 30L + localNameLen.toLong() + localExtraLen.toLong()
            val dataEndLong = dataStartLong + meta.compressedSize.toLong()

            if (dataStartLong < 0L || dataEndLong > access.size) {
                throw ReaderException.InvalidContent("Dados da entrada '${meta.name}' fora dos limites do ZIP")
            }

            val compressedSlice = access.readSlice(dataStartLong, meta.compressedSize)

            return when (meta.compressionMethod) {
                METHOD_STORED -> {
                    if (compressedSlice.size > limits.maxEntryUncompressedBytes) {
                        throw ReaderException.InvalidContent("Entrada '${meta.name}' excede o limite de bytes")
                    }
                    compressedSlice
                }
                METHOD_DEFLATED -> inflateBounded(
                    compressedBytes = compressedSlice,
                    uncompressedSize = meta.uncompressedSize,
                    maxBytes = limits.maxEntryUncompressedBytes,
                )
                else -> throw ReaderException.InvalidContent(
                    "Método de compressão ZIP não suportado: ${meta.compressionMethod}",
                )
            }
        }

        private fun inflateBounded(
            compressedBytes: ByteArray,
            uncompressedSize: Int,
            maxBytes: Int,
        ): ByteArray {
            val compressedBuffer = Buffer().write(compressedBytes)
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

        private fun findEocdOffsetInWindow(windowBytes: ByteArray): Int? {
            for (i in windowBytes.size - 22 downTo 0) {
                if (readInt32LE(windowBytes, i) == EOCD_SIGNATURE) {
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

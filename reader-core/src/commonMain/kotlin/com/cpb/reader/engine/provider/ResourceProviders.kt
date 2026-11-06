package com.cpb.reader.engine.provider

import com.cpb.reader.engine.error.ReaderException
import com.cpb.reader.engine.model.ResourceProvider
import okio.Buffer
import okio.Inflater
import okio.InflaterSource
import okio.buffer

class MemoryResourceProvider(
    private val files: Map<String, ByteArray>,
) : ResourceProvider {
    override fun read(href: String): ByteArray {
        val normalized = normalizeHref(href)
        return files[normalized]
            ?: files[href]
            ?: throw ReaderException.ResourceNotFound(href)
    }
}

/**
 * Provider ZIP multiplataforma (`commonMain`) que lê arquivos `.epub` e `.hpub` diretamente em memória
 * suportando entradas `STORED` (0) e `DEFLATED` (8) via `okio.InflaterSource`.
 */
class ZipResourceProvider private constructor(
    private val entries: Map<String, ByteArray>,
) : ResourceProvider {

    fun hasEntry(href: String): Boolean = entries.containsKey(normalizeHref(href))

    fun entryNames(): Set<String> = entries.keys

    override fun read(href: String): ByteArray {
        val clean = normalizeHref(href)
        return entries[clean]
            ?: throw ReaderException.ResourceNotFound(href)
    }

    fun readString(href: String): String = read(href).decodeToString()

    companion object {
        private const val EOCD_SIGNATURE = 0x06054b50
        private const val CEN_SIGNATURE = 0x02014b50
        private const val LOC_SIGNATURE = 0x04034b50
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        fun fromBytes(bytes: ByteArray): ZipResourceProvider {
            val map = parseZipEntries(bytes)
            return ZipResourceProvider(map)
        }

        fun hasZipEntry(bytes: ByteArray, entryName: String): Boolean {
            return try {
                val target = normalizeHref(entryName)
                parseZipEntries(bytes).containsKey(target)
            } catch (_: Exception) {
                false
            }
        }

        private fun parseZipEntries(bytes: ByteArray): Map<String, ByteArray> {
            if (bytes.size < 22) {
                throw ReaderException.InvalidContent("Arquivo ZIP muito pequeno (${bytes.size} bytes)")
            }

            val eocdOffset = findEocdOffset(bytes)
                ?: throw ReaderException.InvalidContent("Assinatura EOCD do ZIP não encontrada")

            val totalEntries = readUInt16LE(bytes, eocdOffset + 10)
            val centralDirSize = readInt32LE(bytes, eocdOffset + 12)
            val centralDirOffset = readInt32LE(bytes, eocdOffset + 16)

            if (centralDirOffset < 0 || centralDirOffset + centralDirSize > bytes.size) {
                throw ReaderException.InvalidContent("Central Directory do ZIP fora dos limites")
            }

            val result = LinkedHashMap<String, ByteArray>(totalEntries)
            var pos = centralDirOffset

            for (i in 0 until totalEntries) {
                if (pos + 46 > bytes.size || readInt32LE(bytes, pos) != CEN_SIGNATURE) {
                    throw ReaderException.InvalidContent("Entrada inválida no Central Directory do ZIP")
                }

                val compressionMethod = readUInt16LE(bytes, pos + 10)
                val compressedSize = readInt32LE(bytes, pos + 20)
                val uncompressedSize = readInt32LE(bytes, pos + 24)
                val fileNameLen = readUInt16LE(bytes, pos + 28)
                val extraLen = readUInt16LE(bytes, pos + 30)
                val commentLen = readUInt16LE(bytes, pos + 32)
                val localHeaderOffset = readInt32LE(bytes, pos + 42)

                val fileName = bytes.decodeToString(pos + 46, pos + 46 + fileNameLen)
                pos += 46 + fileNameLen + extraLen + commentLen

                if (fileName.endsWith("/")) continue

                if (localHeaderOffset + 30 > bytes.size || readInt32LE(bytes, localHeaderOffset) != LOC_SIGNATURE) {
                    throw ReaderException.InvalidContent("Local File Header inválido para '$fileName'")
                }

                val localNameLen = readUInt16LE(bytes, localHeaderOffset + 26)
                val localExtraLen = readUInt16LE(bytes, localHeaderOffset + 28)
                val dataStart = localHeaderOffset + 30 + localNameLen + localExtraLen
                val dataEnd = dataStart + compressedSize

                if (dataStart < 0 || dataEnd > bytes.size) {
                    throw ReaderException.InvalidContent("Dados da entrada '$fileName' fora dos limites do ZIP")
                }

                val entryBytes = when (compressionMethod) {
                    METHOD_STORED -> bytes.copyOfRange(dataStart, dataEnd)
                    METHOD_DEFLATED -> inflateRaw(bytes, dataStart, compressedSize, uncompressedSize)
                    else -> throw ReaderException.InvalidContent("Método de compressão ZIP não suportado: $compressionMethod")
                }

                result[normalizeHref(fileName)] = entryBytes
            }

            return result
        }

        private fun inflateRaw(
            sourceBytes: ByteArray,
            offset: Int,
            compressedLength: Int,
            uncompressedSize: Int,
        ): ByteArray {
            val compressedBuffer = Buffer().write(sourceBytes, offset, compressedLength)
            val inflaterSource = InflaterSource(compressedBuffer, Inflater(true))
            return try {
                inflaterSource.buffer().use { buffered ->
                    buffered.readByteArray()
                }
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

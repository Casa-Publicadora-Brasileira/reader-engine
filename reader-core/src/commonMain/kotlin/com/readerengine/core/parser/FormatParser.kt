package com.readerengine.core.parser

import com.readerengine.core.drm.ContentDecryptor
import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Publication
import com.readerengine.core.platform.defaultFileSystem
import com.readerengine.core.provider.FileHandleRandomAccess
import com.readerengine.core.provider.ZipLimits
import com.readerengine.core.provider.ZipResourceProvider
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path

/**
 * Representa a fonte de entrada de um livro, podendo estar em memória (`bytes`)
 * ou em disco via acesso aleatório (`path` + `fileSystem`) para arquivos grandes (> 100 MB).
 */
public class Source private constructor(
    public val name: String,
    private val inMemoryBytes: ByteArray?,
    public val path: Path?,
    public val fileSystem: FileSystem,
    public val zipLimits: ZipLimits,
    public val customDecryptor: ContentDecryptor?,
) {
    public constructor(
        name: String,
        bytes: ByteArray,
        zipLimits: ZipLimits = ZipLimits(),
        customDecryptor: ContentDecryptor? = null,
    ) : this(
        name = name,
        inMemoryBytes = bytes,
        path = null,
        fileSystem = defaultFileSystem,
        zipLimits = zipLimits,
        customDecryptor = customDecryptor,
    )

    public val bytes: ByteArray
        get() = inMemoryBytes
            ?: path?.let { fileSystem.read(it) { readByteArray() } }
            ?: ByteArray(0)

    public fun extension(): String? =
        name.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotEmpty() }

    public fun hasZipEntry(entryName: String): Boolean {
        val p = path
        return if (p != null && inMemoryBytes == null) {
            ZipResourceProvider.hasZipEntry(p, entryName, fileSystem, zipLimits)
        } else {
            ZipResourceProvider.hasZipEntry(bytes, entryName, zipLimits)
        }
    }

    public fun openZip(): ZipResourceProvider {
        val p = path
        return if (p != null && inMemoryBytes == null) {
            ZipResourceProvider.fromPath(p, fileSystem, zipLimits)
        } else {
            ZipResourceProvider.fromBytes(bytes, zipLimits)
        }
    }

    /**
     * Calcula um identificador determinístico baseado no hash SHA-256 (`doc-<16 hex chars>`).
     * Para arquivos em disco, amostra até 64 KB do início, 64 KB do final e o tamanho total,
     * evitando carregar centenas de MB na memória RAM.
     */
    public fun sha256DocumentId(): String {
        val mem = inMemoryBytes
        if (mem != null) {
            val digestHex = mem.toByteString().sha256().hex().take(16)
            return "doc-$digestHex"
        }
        val p = path ?: return "doc-unknown"
        val access = FileHandleRandomAccess(fileSystem, p)
        return try {
            val total = access.size
            val window = 64 * 1024
            val buf = Buffer()
            buf.writeLong(total)
            if (total <= window * 2L) {
                buf.write(access.readSlice(0L, total.toInt()))
            } else {
                buf.write(access.readSlice(0L, window))
                buf.write(access.readSlice(total - window.toLong(), window))
            }
            val digestHex = buf.sha256().hex().take(16)
            "doc-$digestHex"
        } finally {
            access.close()
        }
    }

    public companion object {
        public fun fromBytes(
            name: String,
            bytes: ByteArray,
            zipLimits: ZipLimits = ZipLimits(),
            customDecryptor: ContentDecryptor? = null,
        ): Source = Source(
            name = name,
            inMemoryBytes = bytes,
            path = null,
            fileSystem = defaultFileSystem,
            zipLimits = zipLimits,
            customDecryptor = customDecryptor,
        )

        public fun fromPath(
            path: Path,
            fileSystem: FileSystem = defaultFileSystem,
            zipLimits: ZipLimits = ZipLimits(),
            customDecryptor: ContentDecryptor? = null,
        ): Source = Source(
            name = path.name,
            inMemoryBytes = null,
            path = path,
            fileSystem = fileSystem,
            zipLimits = zipLimits,
            customDecryptor = customDecryptor,
        )
    }
}

public interface FormatParser {
    public val id: String
    public fun canParse(source: Source): Boolean
    public fun parse(source: Source): Publication
}

public class ParserRegistry {
    private val parsers = mutableListOf<FormatParser>()

    public fun register(parser: FormatParser): ParserRegistry {
        parsers.add(parser)
        return this
    }

    public fun open(source: Source): Publication {
        val parser = parsers.firstOrNull { it.canParse(source) }
            ?: throw ReaderException.UnsupportedFormat(source.name)
        val parsed = parser.parse(source)
        if (!parsed.metadata.identifier.isNullOrBlank()) {
            return parsed
        }
        return Publication(
            metadata = parsed.metadata.copy(identifier = source.sha256DocumentId()),
            spine = parsed.spine,
            resources = parsed.resources,
            toc = parsed.toc,
            provider = parsed.provider,
        )
    }

    public companion object {
        public fun default(): ParserRegistry = ParserRegistry()
            .register(JsonParser())
            .register(EpubParser())
            .register(HpubParser())
    }
}

package com.readerengine.core.session

import com.readerengine.core.drm.ContentDecryptor
import com.readerengine.core.model.InitialBookState
import com.readerengine.core.parser.FormatParser
import com.readerengine.core.parser.ParserRegistry
import com.readerengine.core.parser.Source
import com.readerengine.core.provider.ZipLimits
import okio.FileSystem
import okio.Path

/**
 * Ponto de entrada principal e universal da biblioteca KMP `:reader-core`.
 *
 * Suporta abertura tanto a partir de `ByteArray` em memória quanto por streaming direto do disco
 * (`okio.Path` + `okio.FileSystem`) para arquivos grandes (> 100 MB), além de gancho extensível
 * de decriptografia/DRM ([ContentDecryptor]).
 */
public class ReaderEngine(
    private val registry: ParserRegistry = ParserRegistry.default(),
    private val zipLimits: ZipLimits = ZipLimits(),
    private val customDecryptor: ContentDecryptor? = null,
) {
    public fun registerParser(parser: FormatParser): ReaderEngine {
        registry.register(parser)
        return this
    }

    /** Abre um documento a partir de bytes em memória (sobrecarga concisa para iOS/Swift). */
    public fun open(
        name: String,
        bytes: ByteArray,
    ): ReaderSession = open(
        name = name,
        bytes = bytes,
        initialState = InitialBookState(),
    )

    /** Abre um documento a partir de bytes em memória com estado inicial completo. */
    public fun open(
        name: String,
        bytes: ByteArray,
        initialState: InitialBookState,
    ): ReaderSession {
        val source = Source.fromBytes(
            name = name,
            bytes = bytes,
            zipLimits = zipLimits,
            customDecryptor = customDecryptor,
        )
        val publication = registry.open(source)
        return ReaderSession(publication = publication, initialBookState = initialState)
    }

    /**
     * Abre um documento diretamente do disco via `Okio FileHandle` (sobrecarga concisa para iOS/Swift),
     * sem carregar o arquivo inteiro na memória RAM.
     */
    public fun open(
        path: Path,
    ): ReaderSession = open(
        path = path,
        fileSystem = FileSystem.SYSTEM,
        initialState = InitialBookState(),
    )

    /**
     * Abre um documento diretamente do disco via `Okio FileHandle` (acesso aleatório),
     * ideal para pacotes `.epub` / `.hpub` grandes (> 100 MB).
     */
    public fun open(
        path: Path,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        initialState: InitialBookState = InitialBookState(),
    ): ReaderSession {
        val source = Source.fromPath(
            path = path,
            fileSystem = fileSystem,
            zipLimits = zipLimits,
            customDecryptor = customDecryptor,
        )
        val publication = registry.open(source)
        return ReaderSession(publication = publication, initialBookState = initialState)
    }
}

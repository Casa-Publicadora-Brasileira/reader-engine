package com.readerengine.core.session

import com.readerengine.core.audiobook.AudiobookParser
import com.readerengine.core.audiobook.AudiobookSession
import com.readerengine.core.audiobook.InitialAudiobookState
import com.readerengine.core.drm.ContentDecryptor
import com.readerengine.core.model.InitialBookState
import com.readerengine.core.observability.ReaderLogLevel
import com.readerengine.core.observability.ReaderLogRecord
import com.readerengine.core.observability.ReaderMetric
import com.readerengine.core.observability.ReaderObservability
import com.readerengine.core.observability.ReaderObserver
import com.readerengine.core.parser.FormatParser
import com.readerengine.core.parser.ParserRegistry
import com.readerengine.core.parser.Source
import com.readerengine.core.platform.defaultFileSystem
import com.readerengine.core.provider.ZipLimits
import okio.FileSystem
import okio.Path

/**
 * Ponto de entrada principal e universal da biblioteca KMP `:reader-core`.
 *
 * Suporta abertura tanto a partir de `ByteArray` em memória quanto por streaming direto do disco
 * (`okio.Path` + `okio.FileSystem`) para arquivos grandes (> 100 MB), além de gancho extensível
 * de decriptografia/DRM ([ContentDecryptor]) e observabilidade plugável ([ReaderObserver]).
 */
public class ReaderEngine(
    private val registry: ParserRegistry = ParserRegistry.default(),
    private val zipLimits: ZipLimits = ZipLimits(),
    private val customDecryptor: ContentDecryptor? = null,
    private val observer: ReaderObserver = ReaderObserver.NoOp,
) {
    public constructor(
        registry: ParserRegistry,
        zipLimits: ZipLimits,
        customDecryptor: ContentDecryptor?,
    ) : this(
        registry = registry,
        zipLimits = zipLimits,
        customDecryptor = customDecryptor,
        observer = ReaderObserver.NoOp,
    )

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
        return openSource(source = source, initialState = initialState, mode = "memory")
    }

    /**
     * Abre um documento diretamente do disco via `Okio FileHandle` (sobrecarga concisa para iOS/Swift),
     * sem carregar o arquivo inteiro na memória RAM.
     */
    public fun open(
        path: Path,
    ): ReaderSession = open(
        path = path,
        fileSystem = defaultFileSystem,
        initialState = InitialBookState(),
    )

    /**
     * Abre um documento diretamente do disco via `Okio FileHandle` (acesso aleatório),
     * ideal para pacotes `.epub` / `.hpub` grandes (> 100 MB).
     */
    public fun open(
        path: Path,
        fileSystem: FileSystem = defaultFileSystem,
        initialState: InitialBookState = InitialBookState(),
    ): ReaderSession {
        val source = Source.fromPath(
            path = path,
            fileSystem = fileSystem,
            zipLimits = zipLimits,
            customDecryptor = customDecryptor,
        )
        return openSource(source = source, initialState = initialState, mode = "disk")
    }

    /** Abre um audiobook (`.audiobook`, `.abz`, `.zip` ou `.audiobook.json`) a partir de bytes em memória. */
    public fun openAudiobook(
        name: String,
        bytes: ByteArray,
    ): AudiobookSession = openAudiobook(
        name = name,
        bytes = bytes,
        initialState = InitialAudiobookState(),
    )

    /** Abre um audiobook a partir de bytes em memória com estado inicial completo. */
    public fun openAudiobook(
        name: String,
        bytes: ByteArray,
        initialState: InitialAudiobookState,
    ): AudiobookSession {
        val source = Source.fromBytes(
            name = name,
            bytes = bytes,
            zipLimits = zipLimits,
            customDecryptor = customDecryptor,
        )
        return openAudiobookSource(source = source, initialState = initialState, mode = "memory")
    }

    /** Abre um audiobook diretamente do disco via `Okio FileHandle` (streaming zero-copy). */
    public fun openAudiobook(
        path: Path,
    ): AudiobookSession = openAudiobook(
        path = path,
        fileSystem = defaultFileSystem,
        initialState = InitialAudiobookState(),
    )

    /** Abre um audiobook diretamente do disco via `Okio FileHandle` com estado inicial completo. */
    public fun openAudiobook(
        path: Path,
        fileSystem: FileSystem = defaultFileSystem,
        initialState: InitialAudiobookState = InitialAudiobookState(),
    ): AudiobookSession {
        val source = Source.fromPath(
            path = path,
            fileSystem = fileSystem,
            zipLimits = zipLimits,
            customDecryptor = customDecryptor,
        )
        return openAudiobookSource(source = source, initialState = initialState, mode = "disk")
    }

    private fun openAudiobookSource(
        source: Source,
        initialState: InitialAudiobookState,
        mode: String,
    ): AudiobookSession {
        val ext = source.extension().orEmpty()
        val attrs = mapOf("name" to source.name, "extension" to ext, "mode" to mode)
        val publication = ReaderObservability.measureSpan(
            observer = observer,
            name = "audiobook.open",
            attributes = attrs,
        ) {
            AudiobookParser().parse(source)
        }
        observer.onMetric(
            ReaderMetric(
                name = "audiobook.tracks_count",
                value = publication.tracks.size.toDouble(),
                unit = "tracks",
                attributes = mapOf("extension" to ext, "mode" to mode),
            ),
        )
        observer.onLog(
            ReaderLogRecord(
                level = ReaderLogLevel.INFO,
                tag = "ReaderEngine",
                message = "Audiobook aberto: '${publication.metadata.title.ifBlank { source.name }}' (${publication.tracks.size} faixas)",
                attributes = attrs + ("tracks" to publication.tracks.size.toString()),
            ),
        )
        return AudiobookSession(
            publication = publication,
            initialState = initialState,
            observer = observer,
        )
    }

    private fun openSource(
        source: Source,
        initialState: InitialBookState,
        mode: String,
    ): ReaderSession {
        val ext = source.extension().orEmpty()
        val attrs = mapOf("name" to source.name, "extension" to ext, "mode" to mode)
        val publication = ReaderObservability.measureSpan(
            observer = observer,
            name = "book.open",
            attributes = attrs,
        ) {
            registry.open(source)
        }
        observer.onMetric(
            ReaderMetric(
                name = "book.spine_pages",
                value = publication.spine.size.toDouble(),
                unit = "pages",
                attributes = mapOf("extension" to ext, "mode" to mode),
            ),
        )
        observer.onLog(
            ReaderLogRecord(
                level = ReaderLogLevel.INFO,
                tag = "ReaderEngine",
                message = "Publicação aberta: '${publication.metadata.title.ifBlank { source.name }}' (${publication.spine.size} páginas)",
                attributes = attrs + ("spineLen" to publication.spine.size.toString()),
            ),
        )
        return ReaderSession(
            publication = publication,
            initialBookState = initialState,
            observer = observer,
        )
    }
}

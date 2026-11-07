package com.readerengine.core.session

import com.readerengine.core.model.InitialBookState
import com.readerengine.core.parser.FormatParser
import com.readerengine.core.parser.ParserRegistry
import com.readerengine.core.parser.Source

/**
 * Ponto de entrada principal e universal da biblioteca KMP `:reader-core`.
 */
public class ReaderEngine(
    private val registry: ParserRegistry = ParserRegistry.default(),
) {
    public fun registerParser(parser: FormatParser): ReaderEngine {
        registry.register(parser)
        return this
    }

    public fun open(
        name: String,
        bytes: ByteArray,
        initialState: InitialBookState = InitialBookState(),
    ): ReaderSession {
        val publication = registry.open(Source(name = name, bytes = bytes))
        return ReaderSession(publication = publication, initialBookState = initialState)
    }
}

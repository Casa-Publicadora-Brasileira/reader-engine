package com.cpb.reader.engine.session

import com.cpb.reader.engine.model.InitialBookState
import com.cpb.reader.engine.parser.FormatParser
import com.cpb.reader.engine.parser.ParserRegistry
import com.cpb.reader.engine.parser.Source

/**
 * Ponto de entrada principal da biblioteca KMP para os 4 apps (2 Android e 2 iOS).
 */
class ReaderEngine(
    private val registry: ParserRegistry = ParserRegistry.default(),
) {
    fun registerParser(parser: FormatParser): ReaderEngine {
        registry.register(parser)
        return this
    }

    fun open(
        name: String,
        bytes: ByteArray,
        initialState: InitialBookState = InitialBookState(),
    ): ReaderSession {
        val publication = registry.open(Source(name = name, bytes = bytes))
        return ReaderSession(publication = publication, initialBookState = initialState)
    }
}

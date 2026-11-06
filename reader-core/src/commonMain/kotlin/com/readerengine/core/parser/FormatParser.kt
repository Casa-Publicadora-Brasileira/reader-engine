package com.readerengine.core.parser

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Publication

class Source(
    val name: String,
    val bytes: ByteArray,
) {
    fun extension(): String? =
        name.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotEmpty() }
}

interface FormatParser {
    val id: String
    fun canParse(source: Source): Boolean
    fun parse(source: Source): Publication
}

class ParserRegistry {
    private val parsers = mutableListOf<FormatParser>()

    fun register(parser: FormatParser): ParserRegistry {
        parsers.add(parser)
        return this
    }

    fun open(source: Source): Publication {
        val parser = parsers.firstOrNull { it.canParse(source) }
            ?: throw ReaderException.UnsupportedFormat(source.name)
        return parser.parse(source)
    }

    companion object {
        fun default(): ParserRegistry = ParserRegistry()
            .register(JsonParser())
            .register(EpubParser())
            .register(HpubParser())
    }
}

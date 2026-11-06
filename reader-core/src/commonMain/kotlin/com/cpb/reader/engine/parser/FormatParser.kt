package com.cpb.reader.engine.parser

import com.cpb.reader.engine.error.ReaderException
import com.cpb.reader.engine.model.Publication

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

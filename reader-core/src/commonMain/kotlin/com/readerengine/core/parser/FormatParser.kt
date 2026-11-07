package com.readerengine.core.parser

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Publication
import okio.ByteString.Companion.toByteString

public class Source(
    public val name: String,
    public val bytes: ByteArray,
) {
    public fun extension(): String? =
        name.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotEmpty() }

    /**
     * Calcula um identificador determinístico baseado no hash SHA-256 do conteúdo
     * (`doc-<16 hex chars>`), garantindo unicidade mesmo quando o livro não declara `identifier`.
     */
    public fun sha256DocumentId(): String {
        val digestHex = bytes.toByteString().sha256().hex().take(16)
        return "doc-$digestHex"
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

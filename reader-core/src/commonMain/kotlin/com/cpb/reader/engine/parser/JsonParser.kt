package com.cpb.reader.engine.parser

import com.cpb.reader.engine.error.ReaderException
import com.cpb.reader.engine.model.Metadata
import com.cpb.reader.engine.model.Publication
import com.cpb.reader.engine.model.Resource
import com.cpb.reader.engine.model.TocEntry
import com.cpb.reader.engine.provider.MemoryResourceProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsonParser : FormatParser {

    @Serializable
    private data class Doc(
        val format: String,
        val title: String,
        val authors: List<String> = emptyList(),
        val language: String? = null,
        val identifier: String? = null,
        val pages: List<Page> = emptyList(),
    )

    @Serializable
    private data class Page(
        val id: String,
        val title: String? = null,
        val subtitle: String? = null,
        val html: String,
    )

    override val id: String = "reader-json"

    override fun canParse(source: Source): Boolean {
        if (source.extension() != "json") return false
        return try {
            val obj = json.parseToJsonElement(source.bytes.decodeToString()).jsonObject
            obj["format"]?.jsonPrimitive?.content == "reader-json"
        } catch (_: Exception) {
            false
        }
    }

    override fun parse(source: Source): Publication {
        val doc = try {
            json.decodeFromString(Doc.serializer(), source.bytes.decodeToString())
        } catch (e: Exception) {
            throw ReaderException.InvalidContent("Erro ao decodificar reader-json: ${e.message}", e)
        }

        if (doc.format != "reader-json") {
            throw ReaderException.UnsupportedFormat(source.name)
        }

        val files = LinkedHashMap<String, ByteArray>()
        val spine = mutableListOf<String>()
        val resources = mutableListOf<Resource>()
        val toc = mutableListOf<TocEntry>()

        for (page in doc.pages) {
            val href = "${page.id}.html"
            toc.add(
                TocEntry(
                    title = page.title ?: page.id,
                    subtitle = page.subtitle,
                    href = href,
                ),
            )
            resources.add(Resource(href = href, mediaType = "text/html"))
            files[href] = page.html.encodeToByteArray()
            spine.add(href)
        }

        return Publication(
            metadata = Metadata(
                title = doc.title,
                authors = doc.authors,
                language = doc.language,
                identifier = doc.identifier,
            ),
            spine = spine,
            resources = resources,
            toc = toc,
            provider = MemoryResourceProvider(files),
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

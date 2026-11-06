package com.readerengine.core.parser

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.Publication
import com.readerengine.core.model.Resource
import com.readerengine.core.model.TocEntry
import com.readerengine.core.provider.ZipResourceProvider
import com.readerengine.core.provider.normalizeHref
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Parser HPUB (Baker Framework): pacote ZIP com `book.json` listando as páginas HTML em `contents`.
 */
class HpubParser : FormatParser {
    override val id: String = "hpub"

    override fun canParse(source: Source): Boolean {
        return source.extension() == "hpub" || ZipResourceProvider.hasZipEntry(source.bytes, "book.json")
    }

    override fun parse(source: Source): Publication {
        val zip = ZipResourceProvider.fromBytes(source.bytes)
        val bookJsonRaw = try {
            zip.readString("book.json")
        } catch (e: Exception) {
            throw ReaderException.InvalidContent("HPUB sem book.json válido", e)
        }

        val root = try {
            json.parseToJsonElement(bookJsonRaw).jsonObject
        } catch (e: Exception) {
            throw ReaderException.InvalidContent("book.json malformado: ${e.message}", e)
        }

        val title = root["title"]?.jsonPrimitiveOrNull()?.content ?: ""
        val language = root["language"]?.jsonPrimitiveOrNull()?.content
        val authors = parseAuthors(root["author"] ?: root["authors"] ?: root["creator"])

        val spine = mutableListOf<String>()
        val toc = mutableListOf<TocEntry>()

        val contents = root["contents"] as? JsonArray ?: JsonArray(emptyList())
        for (item in contents) {
            when (item) {
                is JsonPrimitive -> {
                    val url = normalizeHref(item.content)
                    spine.add(url)
                    toc.add(TocEntry(title = url, href = url))
                }
                is JsonObject -> {
                    val url = normalizeHref(item["url"]?.jsonPrimitiveOrNull()?.content ?: continue)
                    val entryTitle = item["title"]?.jsonPrimitiveOrNull()?.content ?: url
                    val subtitle = item["subtitle"]?.jsonPrimitiveOrNull()?.content
                    spine.add(url)
                    toc.add(TocEntry(title = entryTitle, subtitle = subtitle, href = url))
                }
                else -> Unit
            }
        }

        val indexEntries = parseIndexHtmlIfPresent(zip)
        val finalToc = if (indexEntries.isNotEmpty() && toc.all { it.title == it.href }) {
            indexEntries
        } else {
            toc
        }

        val resources = spine.map { href ->
            Resource(href = href, mediaType = "text/html")
        }

        return Publication(
            metadata = Metadata(
                title = title,
                authors = authors,
                language = language,
                identifier = null,
            ),
            spine = spine,
            resources = resources,
            toc = finalToc,
            provider = zip,
        )
    }

    private fun parseAuthors(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> listOf(element.content).filter { it.isNotBlank() }
        is JsonArray -> element.mapNotNull { it.jsonPrimitiveOrNull()?.content?.takeIf(String::isNotBlank) }
        else -> emptyList()
    }

    private fun parseIndexHtmlIfPresent(zip: ZipResourceProvider): List<TocEntry> {
        val indexCandidates = listOf("index.html", "Index.html", "toc.html")
        val indexHref = indexCandidates.firstOrNull { zip.hasEntry(it) } ?: return emptyList()
        val html = runCatching { zip.readString(indexHref) }.getOrNull() ?: return emptyList()

        val anchorRegex = Regex("""<a\s+[^>]*href\s*=\s*["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val stripTags = Regex("""<[^>]+>""")
        return anchorRegex.findAll(html).mapNotNull { match ->
            val rawHref = normalizeHref(match.groupValues[1])
            val innerText = stripTags.replace(match.groupValues[2], " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (rawHref.isBlank() || innerText.isBlank()) null
            else TocEntry(title = innerText, href = rawHref)
        }.toList()
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

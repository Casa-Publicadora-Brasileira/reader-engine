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
public class HpubParser : FormatParser {
    override val id: String = "hpub"

    override fun canParse(source: Source): Boolean {
        return source.extension() == "hpub" || source.hasZipEntry("book.json")
    }

    override fun parse(source: Source): Publication {
        val zip = source.openZip()
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
        val identifier = root["identifier"]?.jsonPrimitiveOrNull()?.content
            ?: root["id"]?.jsonPrimitiveOrNull()?.content
        val authors = parseAuthors(root["author"] ?: root["authors"] ?: root["creator"])

        val spine = mutableListOf<String>()
        val toc = mutableListOf<TocEntry>()

        val contents = root["contents"] as? JsonArray ?: JsonArray(emptyList())
        for (item in contents) {
            when (item) {
                is JsonPrimitive -> {
                    val url = normalizeContentHref(item.content)
                    spine.add(url)
                    toc.add(TocEntry(title = url, href = url))
                }
                is JsonObject -> {
                    val rawUrl = item["url"]?.jsonPrimitiveOrNull()?.content ?: continue
                    val url = normalizeContentHref(rawUrl)
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

        val allResources = LinkedHashMap<String, Resource>()
        for (href in spine) {
            allResources[href] = Resource(href = href, mediaType = inferMediaType(href))
        }
        for (entryName in zip.entryNames()) {
            if (!allResources.containsKey(entryName)) {
                allResources[entryName] = Resource(href = entryName, mediaType = inferMediaType(entryName))
            }
        }

        return Publication(
            metadata = Metadata(
                title = title,
                authors = authors,
                language = language,
                identifier = identifier,
            ),
            spine = spine,
            resources = allResources.values.toList(),
            toc = finalToc,
            provider = zip,
        )
    }

    private fun normalizeContentHref(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            normalizeHref(trimmed)
        }
    }

    private fun inferMediaType(href: String): String {
        val lower = href.substringBefore('#').lowercase()
        return when {
            lower.endsWith(".xhtml") -> "application/xhtml+xml"
            lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
            lower.endsWith(".css") -> "text/css"
            lower.endsWith(".js") -> "application/javascript"
            lower.endsWith(".json") -> "application/json"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".svg") -> "image/svg+xml"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".woff2") -> "font/woff2"
            lower.endsWith(".woff") -> "font/woff"
            lower.endsWith(".ttf") -> "font/ttf"
            lower.endsWith(".otf") -> "font/otf"
            else -> "application/octet-stream"
        }
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

        val anchorRegex = Regex("""<a\s+[^>]*href\s*=\s*(?:"([^"]+)"|'([^']+)')[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        val stripTags = Regex("""<[^>]+>""")
        return anchorRegex.findAll(html).mapNotNull { match ->
            val rawAttr = if (match.groups[1] != null) match.groupValues[1] else match.groupValues[2]
            val rawHref = normalizeContentHref(rawAttr)
            val innerText = XmlEntities.decode(stripTags.replace(match.groupValues[3], " "))
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (rawHref.isBlank() || innerText.isBlank()) null
            else TocEntry(title = innerText, href = rawHref)
        }.toList()
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

    public companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }
}

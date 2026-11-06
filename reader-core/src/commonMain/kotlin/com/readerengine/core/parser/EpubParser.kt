package com.readerengine.core.parser

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.Publication
import com.readerengine.core.model.Resource
import com.readerengine.core.model.TocEntry
import com.readerengine.core.provider.ZipResourceProvider
import com.readerengine.core.provider.normalizeHref

/**
 * Parser EPUB 2 / EPUB 3 em Kotlin Multiplatform puro (`commonMain`),
 * lendo `META-INF/container.xml`, o manifesto/spine `.opf` e o sumário (`<nav epub:type="toc">` ou `toc.ncx`).
 */
class EpubParser : FormatParser {
    override val id: String = "epub"

    override fun canParse(source: Source): Boolean {
        return source.extension() == "epub" ||
            ZipResourceProvider.hasZipEntry(source.bytes, "META-INF/container.xml")
    }

    override fun parse(source: Source): Publication {
        val zip = ZipResourceProvider.fromBytes(source.bytes)
        val containerXml = try {
            zip.readString("META-INF/container.xml")
        } catch (e: Exception) {
            throw ReaderException.InvalidContent("EPUB sem META-INF/container.xml", e)
        }

        val rootfileTag = findOpeningTags(containerXml, "rootfile").firstOrNull()
            ?: throw ReaderException.InvalidContent("container.xml sem rootfile")
        val opfPath = normalizeHref(
            extractAttribute(rootfileTag, "full-path")
                ?: throw ReaderException.InvalidContent("rootfile sem full-path"),
        )
        val baseDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }

        val opfXml = zip.readString(opfPath)

        val metadata = Metadata(
            title = extractFirstTagText(opfXml, "title").orEmpty(),
            authors = extractAllTagTexts(opfXml, "creator"),
            language = extractFirstTagText(opfXml, "language"),
            identifier = extractFirstTagText(opfXml, "identifier"),
        )

        val resources = mutableListOf<Resource>()
        val idToHref = LinkedHashMap<String, String>()
        var navHref: String? = null
        var ncxHref: String? = null

        for (itemTag in findOpeningTags(opfXml, "item")) {
            val id = extractAttribute(itemTag, "id") ?: continue
            val href = extractAttribute(itemTag, "href") ?: continue
            val mediaType = extractAttribute(itemTag, "media-type").orEmpty()
            val properties = extractAttribute(itemTag, "properties").orEmpty()

            val fullHref = normalizeHref("$baseDir$href")
            if (properties.split(' ').any { it == "nav" }) {
                navHref = fullHref
            }
            if (mediaType == "application/x-dtbncx+xml" || fullHref.endsWith(".ncx", ignoreCase = true)) {
                ncxHref = fullHref
            }

            idToHref[id] = fullHref
            resources.add(Resource(href = fullHref, mediaType = mediaType))
        }

        val spine = findOpeningTags(opfXml, "itemref")
            .mapNotNull { extractAttribute(it, "idref") }
            .mapNotNull { idToHref[it] }

        val toc = when {
            navHref != null -> runCatching { parseNavXhtml(zip.readString(navHref!!), navHref!!) }.getOrDefault(emptyList())
            ncxHref != null -> runCatching { parseNcx(zip.readString(ncxHref!!), ncxHref!!) }.getOrDefault(emptyList())
            else -> emptyList()
        }

        return Publication(
            metadata = metadata,
            spine = spine,
            resources = resources,
            toc = toc,
            provider = zip,
        )
    }

    private fun parseNavXhtml(xhtml: String, navPath: String): List<TocEntry> {
        val navDir = navPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }

        val navBlockRegex = Regex(
            """<nav\b[^>]*?(?:epub:type|type)\s*=\s*["'][^"']*toc[^"']*["'][^>]*>([\s\S]*?)</nav>""",
            RegexOption.IGNORE_CASE,
        )
        val navContent = navBlockRegex.find(xhtml)?.groupValues?.get(1) ?: xhtml
        val anchorRegex = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)

        return anchorRegex.findAll(navContent).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val href = extractAttribute(attrs, "href") ?: return@mapNotNull null
            val title = decodeXmlEntities(stripHtmlTags(match.groupValues[2])).trim()
            TocEntry(
                title = title,
                href = normalizeHref("$navDir$href"),
            )
        }.toList()
    }

    private fun parseNcx(ncxXml: String, ncxPath: String): List<TocEntry> {
        val ncxDir = ncxPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }
        val navPointRegex = Regex(
            """<navPoint\b[^>]*>[\s\S]*?<text>([\s\S]*?)</text>[\s\S]*?<content\b([^>]*)/?>""",
            RegexOption.IGNORE_CASE,
        )
        return navPointRegex.findAll(ncxXml).mapNotNull { match ->
            val title = decodeXmlEntities(stripHtmlTags(match.groupValues[1])).trim()
            val contentAttrs = match.groupValues[2]
            val src = extractAttribute(contentAttrs, "src") ?: return@mapNotNull null
            TocEntry(
                title = title,
                href = normalizeHref("$ncxDir$src"),
            )
        }.toList()
    }

    private fun findOpeningTags(xml: String, localName: String): List<String> {
        val regex = Regex("""<(?:[a-zA-Z0-9_-]+:)?$localName\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
        return regex.findAll(xml).map { it.groupValues[1] }.toList()
    }

    private fun extractFirstTagText(xml: String, localName: String): String? {
        return extractAllTagTexts(xml, localName).firstOrNull()
    }

    private fun extractAllTagTexts(xml: String, localName: String): List<String> {
        val regex = Regex(
            """<(?:[a-zA-Z0-9_-]+:)?$localName\b[^>]*>([\s\S]*?)</(?:[a-zA-Z0-9_-]+:)?$localName>""",
            RegexOption.IGNORE_CASE,
        )
        return regex.findAll(xml)
            .map { decodeXmlEntities(stripHtmlTags(it.groupValues[1])).trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun extractAttribute(tagAttributes: String, attrName: String): String? {
        val regex = Regex("""(?:^|\s|[a-zA-Z0-9_-]+:)$attrName\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return regex.find(tagAttributes)?.groupValues?.get(1)?.let(::decodeXmlEntities)
    }

    private fun stripHtmlTags(input: String): String =
        input.replace(Regex("""<[^>]+>"""), "")

    private fun decodeXmlEntities(input: String): String =
        input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
}

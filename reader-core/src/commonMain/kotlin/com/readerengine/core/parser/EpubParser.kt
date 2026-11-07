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
 * lendo `META-INF/container.xml`, o manifesto/spine `.opf` (respeitando `linear="no"`)
 * e o sumário hierárquico (`<nav epub:type="toc">` ou `toc.ncx`).
 */
public class EpubParser : FormatParser {
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
            if (properties.split(Regex("\\s+")).any { it == "nav" }) {
                navHref = fullHref
            }
            if (mediaType == "application/x-dtbncx+xml" || fullHref.endsWith(".ncx", ignoreCase = true)) {
                ncxHref = fullHref
            }

            idToHref[id] = fullHref
            resources.add(Resource(href = fullHref, mediaType = mediaType))
        }

        val itemrefTags = findOpeningTags(opfXml, "itemref")
        val linearHrefs = itemrefTags
            .filter { !extractAttribute(it, "linear").equals("no", ignoreCase = true) }
            .mapNotNull { extractAttribute(it, "idref") }
            .mapNotNull { idToHref[it] }
        val allHrefs = itemrefTags
            .mapNotNull { extractAttribute(it, "idref") }
            .mapNotNull { idToHref[it] }
        val spine = linearHrefs.ifEmpty { allHrefs }

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

    internal fun parseNavXhtml(xhtml: String, navPath: String): List<TocEntry> {
        val navDir = navPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }

        val navBlockRegex = Regex(
            """<nav\b[^>]*?(?:epub:type|type)\s*=\s*(?:"[^"]*toc[^"]*"|'[^']*toc[^']*')[^>]*>([\s\S]*?)</nav>""",
            RegexOption.IGNORE_CASE,
        )
        val navContent = navBlockRegex.find(xhtml)?.groupValues?.get(1) ?: xhtml
        val nested = parseNestedOlToc(navContent, navDir)
        if (nested.isNotEmpty()) return nested

        val anchorRegex = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        return anchorRegex.findAll(navContent).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val href = extractAttribute(attrs, "href") ?: return@mapNotNull null
            val title = XmlEntities.decode(stripHtmlTags(match.groupValues[2])).trim()
            TocEntry(
                title = title,
                href = resolveHrefWithAnchor(navDir, href),
            )
        }.toList()
    }

    private fun parseNestedOlToc(html: String, navDir: String): List<TocEntry> {
        val tokenRegex = Regex("""<(/?)(ol|li|a)\b([^>]*)>""", RegexOption.IGNORE_CASE)
        val anchorFullRegex = Regex("""<a\b([^>]*)>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)

        class LiBuilder(
            var title: String? = null,
            var href: String? = null,
            val children: MutableList<TocEntry> = mutableListOf(),
        )

        val rootEntries = mutableListOf<TocEntry>()
        val liStack = ArrayDeque<LiBuilder>()
        val matches = tokenRegex.findAll(html).toList()
        var idx = 0

        while (idx < matches.size) {
            val m = matches[idx]
            val isClosing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()

            when {
                !isClosing && tag == "li" -> {
                    liStack.addLast(LiBuilder())
                }
                !isClosing && tag == "a" && liStack.isNotEmpty() -> {
                    val currentLi = liStack.last()
                    if (currentLi.href == null) {
                        val aMatch = anchorFullRegex.find(html, m.range.first)
                        if (aMatch != null && aMatch.range.first == m.range.first) {
                            val rawHref = extractAttribute(aMatch.groupValues[1], "href")
                            val rawTitle = XmlEntities.decode(stripHtmlTags(aMatch.groupValues[2])).trim()
                            if (rawHref != null) {
                                currentLi.href = resolveHrefWithAnchor(navDir, rawHref)
                                currentLi.title = rawTitle
                            }
                        }
                    }
                }
                isClosing && tag == "li" && liStack.isNotEmpty() -> {
                    val finished = liStack.removeLast()
                    val href = finished.href
                    val title = finished.title
                    if (!href.isNullOrBlank() && !title.isNullOrBlank()) {
                        val entry = TocEntry(
                            title = title,
                            href = href,
                            children = finished.children.toList(),
                        )
                        if (liStack.isEmpty()) {
                            rootEntries.add(entry)
                        } else {
                            liStack.last().children.add(entry)
                        }
                    } else if (finished.children.isNotEmpty()) {
                        if (liStack.isEmpty()) {
                            rootEntries.addAll(finished.children)
                        } else {
                            liStack.last().children.addAll(finished.children)
                        }
                    }
                }
            }
            idx++
        }

        return rootEntries
    }

    internal fun parseNcx(ncxXml: String, ncxPath: String): List<TocEntry> {
        val ncxDir = ncxPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }
        val tagRegex = Regex(
            """<(/?)(?:[a-zA-Z0-9_-]+:)?(navPoint|text|content)\b([^>]*)/?>""",
            RegexOption.IGNORE_CASE,
        )

        class NavPointBuilder(
            var title: String? = null,
            var src: String? = null,
            val children: MutableList<TocEntry> = mutableListOf(),
        )

        val roots = mutableListOf<TocEntry>()
        val stack = ArrayDeque<NavPointBuilder>()
        val matches = tagRegex.findAll(ncxXml).toList()

        for (i in matches.indices) {
            val m = matches[i]
            val isClosing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            val isSelfClosing = m.value.endsWith("/>")

            when {
                !isClosing && name == "navpoint" && !isSelfClosing -> {
                    stack.addLast(NavPointBuilder())
                }
                !isClosing && name == "text" && stack.isNotEmpty() -> {
                    val current = stack.last()
                    if (current.title == null) {
                        val closeIdx = ncxXml.indexOf('<', m.range.last + 1)
                        if (closeIdx > m.range.last) {
                            val rawText = ncxXml.substring(m.range.last + 1, closeIdx)
                            current.title = XmlEntities.decode(rawText).trim()
                        }
                    }
                }
                !isClosing && name == "content" && stack.isNotEmpty() -> {
                    val current = stack.last()
                    if (current.src == null) {
                        current.src = extractAttribute(attrs, "src")
                    }
                }
                isClosing && name == "navpoint" && stack.isNotEmpty() -> {
                    val finished = stack.removeLast()
                    val src = finished.src
                    val title = finished.title
                    if (!src.isNullOrBlank() && !title.isNullOrBlank()) {
                        val entry = TocEntry(
                            title = title,
                            href = resolveHrefWithAnchor(ncxDir, src),
                            children = finished.children.toList(),
                        )
                        if (stack.isEmpty()) {
                            roots.add(entry)
                        } else {
                            stack.last().children.add(entry)
                        }
                    }
                }
            }
        }

        return roots
    }

    private fun resolveHrefWithAnchor(baseDir: String, rawHref: String): String {
        val anchor = rawHref.substringAfter('#', missingDelimiterValue = "")
        val pathPart = normalizeHref("$baseDir${rawHref.substringBefore('#')}")
        return if (anchor.isEmpty()) pathPart else "$pathPart#$anchor"
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
            .map { XmlEntities.decode(stripHtmlTags(it.groupValues[1])).trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    internal fun extractAttribute(tagAttributes: String, attrName: String): String? {
        val regex = Regex(
            """(?:^|\s|[a-zA-Z0-9_-]+:)$attrName\s*=\s*(?:"([^"]*)"|'([^']*)')""",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(tagAttributes) ?: return null
        val raw = if (match.groups[1] != null) match.groupValues[1] else match.groupValues[2]
        return XmlEntities.decode(raw)
    }

    private fun stripHtmlTags(input: String): String =
        input.replace(Regex("""<[^>]+>"""), "")
}

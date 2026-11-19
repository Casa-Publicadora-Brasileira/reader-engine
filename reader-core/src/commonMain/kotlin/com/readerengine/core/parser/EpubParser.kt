package com.readerengine.core.parser

import com.readerengine.core.drm.DecryptingResourceProvider
import com.readerengine.core.drm.EpubEncryptionParser
import com.readerengine.core.drm.StandardEpubFontDeobfuscator
import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.MediaOverlay
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.PageProgressionDirection
import com.readerengine.core.model.Publication
import com.readerengine.core.model.RenditionLayout
import com.readerengine.core.model.RenditionOrientation
import com.readerengine.core.model.RenditionSpread
import com.readerengine.core.model.Resource
import com.readerengine.core.model.TocEntry
import com.readerengine.core.provider.normalizeHref

/**
 * Parser EPUB 2 / EPUB 3 em Kotlin Multiplatform puro (`commonMain`),
 * lendo `META-INF/container.xml`, `META-INF/encryption.xml` (DRM/ofuscação de fontes),
 * o manifesto/spine `.opf` (incluindo `rendition:layout`/`orientation`/`spread` e `linear="no"`)
 * e o sumário hierárquico (`<nav epub:type="toc">` ou `toc.ncx`).
 */
public class EpubParser : FormatParser {
    override val id: String = "epub"

    override fun canParse(source: Source): Boolean {
        return source.extension() == "epub" ||
            source.hasZipEntry("META-INF/container.xml")
    }

    override fun parse(source: Source): Publication {
        val zip = source.openZip()
        val containerXml = try {
            zip.readString("META-INF/container.xml")
        } catch (e: Exception) {
            throw ReaderException.InvalidContent("EPUB sem META-INF/container.xml", e)
        }

        val containerDoc = XmlTokenizer.parseDocument(containerXml)
        val rootfileTag = containerDoc.findFirst("rootfile")
            ?: throw ReaderException.InvalidContent("container.xml sem rootfile")
        val opfPath = normalizeHref(
            rootfileTag.attr("full-path")
                ?: throw ReaderException.InvalidContent("rootfile sem full-path"),
        )
        val baseDir = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }

        val opfXml = zip.readString(opfPath)
        val opfDoc = XmlTokenizer.parseDocument(opfXml)
        val metadata = parseMetadata(opfDoc)

        val resources = mutableListOf<Resource>()
        val idToHref = LinkedHashMap<String, String>()
        val mediaOverlayItemIds = LinkedHashSet<String>()
        val smilHrefs = LinkedHashSet<String>()
        var navHref: String? = null
        var ncxHref: String? = null
        var coverHref: String? = null

        val manifestEl = opfDoc.findFirst("manifest") ?: opfDoc
        for (itemEl in manifestEl.findAll("item")) {
            val id = itemEl.attr("id") ?: continue
            val href = itemEl.attr("href") ?: continue
            val mediaType = itemEl.attr("media-type").orEmpty()
            val properties = itemEl.attr("properties").orEmpty()
            val mediaOverlayAttr = itemEl.attr("media-overlay")?.trim().orEmpty()

            val fullHref = normalizeHref("$baseDir$href")
            val propsList = properties.trim().split(whitespaceRegex)
            if (propsList.any { it == "nav" }) {
                navHref = fullHref
            }
            if (propsList.any { it == "cover-image" }) {
                coverHref = fullHref
            }
            if (mediaType == "application/x-dtbncx+xml" || fullHref.endsWith(".ncx", ignoreCase = true)) {
                ncxHref = fullHref
            }
            if (mediaType.equals("application/smil+xml", ignoreCase = true) ||
                fullHref.endsWith(".smil", ignoreCase = true)
            ) {
                smilHrefs.add(fullHref)
            }
            if (mediaOverlayAttr.isNotEmpty()) {
                mediaOverlayItemIds.add(mediaOverlayAttr)
            }

            idToHref[id] = fullHref
            resources.add(Resource(href = fullHref, mediaType = mediaType))
        }
        for (overlayId in mediaOverlayItemIds) {
            idToHref[overlayId]?.let { smilHrefs.add(it) }
        }

        if (coverHref == null) {
            val metaCoverId = extractMetaNameContent(opfDoc, "cover")
            if (!metaCoverId.isNullOrBlank()) {
                coverHref = idToHref[metaCoverId]
            }
        }
        if (coverHref == null) {
            coverHref = resources.firstOrNull { res ->
                val isImage = res.mediaType.startsWith("image/", ignoreCase = true) ||
                    res.href.endsWith(".jpg", ignoreCase = true) ||
                    res.href.endsWith(".jpeg", ignoreCase = true) ||
                    res.href.endsWith(".png", ignoreCase = true) ||
                    res.href.endsWith(".webp", ignoreCase = true)
                isImage && res.href.contains("cover", ignoreCase = true)
            }?.href
        }

        val spineEl = opfDoc.findFirst("spine")
        val itemrefTags = (spineEl ?: opfDoc).findAll("itemref")
        val linearHrefs = itemrefTags
            .filter { !it.attr("linear").equals("no", ignoreCase = true) }
            .mapNotNull { it.attr("idref") }
            .mapNotNull { idToHref[it] }
        val allHrefs = itemrefTags
            .mapNotNull { it.attr("idref") }
            .mapNotNull { idToHref[it] }
        val spine = linearHrefs.ifEmpty { allHrefs }

        val navXhtml = navHref?.let { runCatching { zip.readString(it) }.getOrNull() }
        val ncxXml = ncxHref?.let { runCatching { zip.readString(it) }.getOrNull() }
        val navDoc = navXhtml?.let { runCatching { XmlTokenizer.parseDocument(it) }.getOrNull() }
        val ncxDoc = ncxXml?.let { runCatching { XmlTokenizer.parseDocument(it) }.getOrNull() }

        val toc = when {
            navDoc != null && navHref != null -> runCatching {
                parseNavByType(navDoc, navHref, "toc", fallbackToWholeDoc = true)
            }.getOrDefault(emptyList())
            ncxDoc != null && ncxHref != null -> runCatching {
                parseNcxDoc(ncxDoc, ncxHref)
            }.getOrDefault(emptyList())
            else -> emptyList()
        }

        val navPageList = if (navDoc != null && navHref != null) {
            runCatching { parseNavByType(navDoc, navHref, "page-list") }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val pageList = if (navPageList.isNotEmpty()) {
            navPageList
        } else if (ncxDoc != null && ncxHref != null) {
            runCatching { parseNcxPageListDoc(ncxDoc, ncxHref) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val landmarks = if (navDoc != null && navHref != null) {
            runCatching { parseNavByType(navDoc, navHref, "landmarks") }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val encryptedResources = if (zip.hasEntry("META-INF/encryption.xml")) {
            runCatching { EpubEncryptionParser.parse(zip.readString("META-INF/encryption.xml")) }
                .getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val effectiveProvider = if (encryptedResources.isNotEmpty() || source.customDecryptor != null) {
            DecryptingResourceProvider(
                delegate = zip,
                encryptedResources = encryptedResources,
                publicationIdentifier = metadata.identifier,
                decryptor = StandardEpubFontDeobfuscator(fallbackDecryptor = source.customDecryptor),
            )
        } else {
            zip
        }

        val mediaOverlays: List<MediaOverlay> = smilHrefs.mapNotNull { smilHref ->
            runCatching {
                val smilXml = effectiveProvider.read(smilHref).decodeToString()
                SmilParser.parse(smilXml, smilHref)
            }.getOrNull()?.takeIf { it.clips.isNotEmpty() }
        }

        val finalMetadata = metadata.copy(
            coverHref = coverHref,
        )

        return Publication(
            metadata = finalMetadata,
            spine = spine,
            resources = resources,
            toc = toc,
            provider = effectiveProvider,
            encryptedResources = encryptedResources,
            pageList = pageList,
            landmarks = landmarks,
            mediaOverlays = mediaOverlays,
        )
    }

    internal fun parseMetadata(opfXml: String): Metadata =
        parseMetadata(XmlTokenizer.parseDocument(opfXml))

    private fun parseMetadata(opfDoc: XmlElement): Metadata {
        val metaEl = opfDoc.findFirst("metadata") ?: opfDoc
        val layoutRaw = extractMetaProperty(metaEl, "rendition:layout")?.lowercase()
        val epub2Fixed = extractMetaNameContent(metaEl, "fixed-layout")?.lowercase()
        val layout = if (layoutRaw == "pre-paginated" || epub2Fixed == "true" || epub2Fixed == "yes") {
            RenditionLayout.PRE_PAGINATED
        } else {
            RenditionLayout.REFLOWABLE
        }

        val orientation = when (extractMetaProperty(metaEl, "rendition:orientation")?.lowercase()) {
            "landscape" -> RenditionOrientation.LANDSCAPE
            "portrait" -> RenditionOrientation.PORTRAIT
            else -> RenditionOrientation.AUTO
        }

        val spread = when (extractMetaProperty(metaEl, "rendition:spread")?.lowercase()) {
            "none" -> RenditionSpread.NONE
            "landscape" -> RenditionSpread.LANDSCAPE
            "both", "portrait" -> RenditionSpread.BOTH
            else -> RenditionSpread.AUTO
        }

        val spineTag = opfDoc.findFirst("spine")
        val spineDirection = spineTag?.attr("page-progression-direction")
        val pageProgressionDirection = PageProgressionDirection.fromRaw(spineDirection)

        val title = metaEl.findFirst("title")?.collectText()?.trim().orEmpty()
        val authors = metaEl.findAll("creator")
            .map { it.collectText().trim() }
            .filter { it.isNotEmpty() }
        val language = metaEl.findFirst("language")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
        val identifier = metaEl.findFirst("identifier")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }

        return Metadata(
            title = title,
            authors = authors,
            language = language,
            identifier = identifier,
            layout = layout,
            orientation = orientation,
            spread = spread,
            pageProgressionDirection = pageProgressionDirection,
        )
    }

    private fun extractMetaProperty(scope: XmlElement, propertyName: String): String? {
        val el = scope.findAll("meta").firstOrNull {
            it.attr("property")?.trim().equals(propertyName, ignoreCase = true)
        } ?: return null
        return el.collectText().trim().takeIf { it.isNotEmpty() }
    }

    private fun extractMetaNameContent(scope: XmlElement, metaName: String): String? {
        val el = scope.findAll("meta").firstOrNull {
            it.attr("name")?.trim().equals(metaName, ignoreCase = true)
        } ?: return null
        return el.attr("content")?.trim()
    }

    internal fun parseNavXhtml(xhtml: String, navPath: String): List<TocEntry> =
        parseNavByType(xhtml = xhtml, navPath = navPath, navType = "toc", fallbackToWholeDoc = true)

    internal fun parseNavByType(
        xhtml: String,
        navPath: String,
        navType: String,
        fallbackToWholeDoc: Boolean = false,
    ): List<TocEntry> = parseNavByType(
        doc = XmlTokenizer.parseDocument(xhtml),
        navPath = navPath,
        navType = navType,
        fallbackToWholeDoc = fallbackToWholeDoc,
    )

    private fun parseNavByType(
        doc: XmlElement,
        navPath: String,
        navType: String,
        fallbackToWholeDoc: Boolean = false,
    ): List<TocEntry> {
        val navDir = navPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }

        val targetNav = doc.findAll("nav").firstOrNull { nav ->
            val typeAttr = (nav.attr("epub:type") ?: nav.attr("type")).orEmpty()
            typeAttr.trim().split(whitespaceRegex).any { it.equals(navType, ignoreCase = true) }
        }
        val scope = targetNav ?: if (fallbackToWholeDoc) doc else return emptyList()

        val rootLists = findTopLevelLists(scope)
        val nested = rootLists.flatMap { parseOlEntries(it, navDir) }
        if (nested.isNotEmpty()) return nested

        return scope.findAll("a").mapNotNull { anchor ->
            val href = anchor.attr("href")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = anchor.collectText().trim()
            if (title.isEmpty()) return@mapNotNull null
            TocEntry(
                title = title,
                href = resolveHrefWithAnchor(navDir, href),
            )
        }
    }

    private fun findTopLevelLists(element: XmlElement): List<XmlElement> {
        val out = mutableListOf<XmlElement>()
        for (child in element.children) {
            if (child.localName.equals("ol", ignoreCase = true)) {
                out.add(child)
            } else {
                out.addAll(findTopLevelLists(child))
            }
        }
        return out
    }

    private fun parseOlEntries(olElement: XmlElement, navDir: String): List<TocEntry> {
        val out = mutableListOf<TocEntry>()
        for (li in olElement.childElements("li")) {
            val anchor = findDirectAnchor(li)
            val childEntries = li.childElements("ol").flatMap { parseOlEntries(it, navDir) }
            val href = anchor?.attr("href")?.trim()
            val title = anchor?.collectText()?.trim()
            if (!href.isNullOrBlank() && !title.isNullOrBlank()) {
                out.add(
                    TocEntry(
                        title = title,
                        href = resolveHrefWithAnchor(navDir, href),
                        children = childEntries,
                    ),
                )
            } else if (childEntries.isNotEmpty()) {
                out.addAll(childEntries)
            }
        }
        return out
    }

    private fun findDirectAnchor(element: XmlElement): XmlElement? {
        for (child in element.children) {
            if (child.localName.equals("a", ignoreCase = true)) return child
            if (!child.localName.equals("ol", ignoreCase = true) && !child.localName.equals("li", ignoreCase = true)) {
                val nested = findDirectAnchor(child)
                if (nested != null) return nested
            }
        }
        return null
    }

    internal fun parseNcx(ncxXml: String, ncxPath: String): List<TocEntry> =
        parseNcxDoc(XmlTokenizer.parseDocument(ncxXml), ncxPath)

    private fun parseNcxDoc(ncxDoc: XmlElement, ncxPath: String): List<TocEntry> {
        val ncxDir = ncxPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }
        val navMap = ncxDoc.findFirst("navMap") ?: ncxDoc
        return navMap.childElements("navPoint").mapNotNull { parseNavPointElement(it, ncxDir) }
    }

    private fun parseNavPointElement(navPoint: XmlElement, ncxDir: String): TocEntry? {
        val title = navPoint.childElements("navLabel").firstOrNull()
            ?.childElements("text")?.firstOrNull()
            ?.collectText()?.trim()
            ?: navPoint.findFirst("text")?.collectText()?.trim()
        val src = navPoint.childElements("content").firstOrNull()?.attr("src")?.trim()
        val children = navPoint.childElements("navPoint").mapNotNull { parseNavPointElement(it, ncxDir) }
        if (title.isNullOrBlank() || src.isNullOrBlank()) return null
        return TocEntry(
            title = title,
            href = resolveHrefWithAnchor(ncxDir, src),
            children = children,
        )
    }

    internal fun parseNcxPageList(ncxXml: String, ncxPath: String): List<TocEntry> =
        parseNcxPageListDoc(XmlTokenizer.parseDocument(ncxXml), ncxPath)

    private fun parseNcxPageListDoc(ncxDoc: XmlElement, ncxPath: String): List<TocEntry> {
        val ncxDir = ncxPath.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }
        val pageList = ncxDoc.findFirst("pageList") ?: return emptyList()
        return pageList.findAll("pageTarget").mapNotNull { target ->
            val title = target.childElements("navLabel").firstOrNull()
                ?.childElements("text")?.firstOrNull()
                ?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: target.findFirst("text")?.collectText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: target.attr("value")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val src = target.childElements("content").firstOrNull()?.attr("src")?.trim()
                ?: target.findFirst("content")?.attr("src")?.trim()
                ?: return@mapNotNull null
            TocEntry(
                title = title,
                href = resolveHrefWithAnchor(ncxDir, src),
            )
        }
    }

    private fun resolveHrefWithAnchor(baseDir: String, rawHref: String): String {
        val anchor = rawHref.substringAfter('#', missingDelimiterValue = "")
        val pathPart = normalizeHref("$baseDir${rawHref.substringBefore('#')}")
        return if (anchor.isEmpty()) pathPart else "$pathPart#$anchor"
    }

    internal fun extractAttribute(tagAttributes: String, attrName: String): String? {
        val attrs = XmlTokenizer.parseAttributes(tagAttributes)
        attrs[attrName]?.let { return it }
        for ((k, v) in attrs) {
            if (k.equals(attrName, ignoreCase = true)) return v
        }
        if (!attrName.contains(':')) {
            for ((k, v) in attrs) {
                val local = if (k.contains(':')) k.substringAfter(':') else k
                if (local.equals(attrName, ignoreCase = true)) return v
            }
        }
        return null
    }

    private companion object {
        private val whitespaceRegex = Regex("""\s+""")
    }
}

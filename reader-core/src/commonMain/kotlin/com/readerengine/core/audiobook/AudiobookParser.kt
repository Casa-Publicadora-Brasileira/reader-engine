package com.readerengine.core.audiobook

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.Resource
import com.readerengine.core.model.ResourceProvider
import com.readerengine.core.parser.CbzParser
import com.readerengine.core.parser.Source
import com.readerengine.core.provider.MemoryResourceProvider
import com.readerengine.core.provider.normalizeHref
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser multiplataforma (`commonMain`) para **Audiobooks** em três formatos abertos:
 * 1. **Readium Audiobook (`.audiobook` / ZIP com `manifest.json`)** — padrão W3C/Readium (`readingOrder`, `toc` com `#t=`, `resources` e capa);
 * 2. **Pacote ZIP de faixas de áudio (`.abz` / `.zip`)** — descoberta automática e ordenação natural de `.mp3`, `.m4a`, `.m4b`, `.aac`, `.ogg`, `.opus`, `.wav`, `.flac`;
 * 3. **Manifesto Readium JSON isolado (`.audiobook.json` / `.json`)** — para streaming remoto ou arquivos externos.
 */
public class AudiobookParser {

    private val json = Json { ignoreUnknownKeys = true }

    public fun canParse(source: Source): Boolean {
        val lower = source.name.lowercase()
        if (lower.endsWith(".audiobook") || lower.endsWith(".abz") || lower.endsWith(".audiobook.json")) {
            return true
        }
        if (lower.endsWith(".json")) {
            val text = runCatching { source.bytes.decodeToString() }.getOrDefault("")
            return text.contains("\"readingOrder\"")
        }
        if (lower.endsWith(".zip")) {
            return source.hasZipEntry("manifest.json")
        }
        return false
    }

    public fun parse(source: Source): AudiobookPublication {
        val lower = source.name.lowercase()
        if (lower.endsWith(".json")) {
            val rawJson = source.bytes.decodeToString()
            return parseManifestJson(
                manifestJson = rawJson,
                sourceName = source.name,
                provider = MemoryResourceProvider(mapOf("manifest.json" to source.bytes)),
                fallbackId = source.sha256DocumentId(),
            )
        }

        val zip = source.openZip()
        return try {
            val manifestEntry = zip.entryNames().firstOrNull {
                val n = normalizeHref(it).lowercase()
                n == "manifest.json" || n.endsWith("/manifest.json")
            }
            if (manifestEntry != null) {
                val manifestText = zip.readString(manifestEntry)
                val baseDir = manifestEntry.substringBeforeLast('/', missingDelimiterValue = "")
                    .let { if (it.isEmpty()) "" else "$it/" }
                parseManifestJson(
                    manifestJson = manifestText,
                    sourceName = source.name,
                    provider = zip,
                    baseDir = baseDir,
                    fallbackId = source.sha256DocumentId(),
                )
            } else {
                parseAudioArchiveZip(
                    zipEntries = zip.entryNames(),
                    sourceName = source.name,
                    provider = zip,
                    fallbackId = source.sha256DocumentId(),
                )
            }
        } catch (e: ReaderException) {
            zip.close()
            throw e
        } catch (e: Exception) {
            zip.close()
            throw ReaderException.InvalidContent("Falha ao abrir audiobook '${source.name}': ${e.message}", e)
        }
    }

    private fun parseManifestJson(
        manifestJson: String,
        sourceName: String,
        provider: ResourceProvider,
        baseDir: String = "",
        fallbackId: String,
    ): AudiobookPublication {
        val root = try {
            json.parseToJsonElement(manifestJson).jsonObject
        } catch (e: Exception) {
            throw ReaderException.InvalidContent("Manifesto de audiobook JSON inválido em '$sourceName'", e)
        }

        val metaObj = root["metadata"] as? JsonObject
        val title = extractLocalizedString(metaObj?.get("title"))
            ?: sourceName.substringBeforeLast('.')
        val authors = extractAuthors(metaObj?.get("author"))
        val language = extractLocalizedString(metaObj?.get("language"))
        val identifier = metaObj?.get("identifier")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: fallbackId

        val resourcesList = mutableListOf<Resource>()
        var coverHref: String? = null

        val rawResources = root["resources"] as? JsonArray
        rawResources?.forEach { elem ->
            val obj = elem as? JsonObject ?: return@forEach
            val href = obj["href"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@forEach
            val resolved = resolvePath(baseDir, href)
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.trim() ?: guessAudioMediaType(resolved)
            resourcesList.add(Resource(href = resolved, mediaType = type))
            if (hasCoverRel(obj["rel"])) {
                coverHref = resolved
            }
        }

        val readingOrder = root["readingOrder"] as? JsonArray
            ?: throw ReaderException.InvalidContent("Manifesto de audiobook sem 'readingOrder': '$sourceName'")

        val tracks = mutableListOf<AudioTrack>()
        var cumulativeOffset = 0.0
        for ((idx, elem) in readingOrder.withIndex()) {
            val obj = elem as? JsonObject ?: continue
            val rawHref = obj["href"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
            val resolvedHref = resolvePath(baseDir, rawHref)
            val trackTitle = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Faixa ${idx + 1}"
            val mediaType = obj["type"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: guessAudioMediaType(resolvedHref)
            val duration = obj["duration"]?.jsonPrimitive?.doubleOrNull?.coerceAtLeast(0.0) ?: 0.0
            tracks.add(
                AudioTrack(
                    index = tracks.size,
                    title = trackTitle,
                    href = resolvedHref,
                    mediaType = mediaType,
                    durationSeconds = duration,
                    startOffsetSeconds = cumulativeOffset,
                ),
            )
            resourcesList.add(Resource(href = resolvedHref, mediaType = mediaType))
            cumulativeOffset += duration
        }

        if (tracks.isEmpty()) {
            throw ReaderException.InvalidContent("Audiobook sem faixas válidas em 'readingOrder': '$sourceName'")
        }

        val chapters = mutableListOf<AudioChapter>()
        val rawToc = root["toc"] as? JsonArray
        if (rawToc != null) {
            for (elem in rawToc) {
                val obj = elem as? JsonObject ?: continue
                val chapTitle = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: continue
                val rawHref = obj["href"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
                val hrefPart = resolvePath(baseDir, rawHref.substringBefore('#'))
                val fragment = rawHref.substringAfter('#', missingDelimiterValue = "")
                val startSec = parseTimeFragment(fragment)
                val trackIdx = tracks.indexOfFirst {
                    it.href == hrefPart || it.href.endsWith("/$hrefPart") || hrefPart.endsWith("/${it.href}")
                }.coerceAtLeast(0)
                chapters.add(
                    AudioChapter(
                        title = chapTitle,
                        trackIndex = trackIdx,
                        startSeconds = startSec,
                    ),
                )
            }
        }
        if (chapters.isEmpty()) {
            tracks.forEach { t ->
                chapters.add(AudioChapter(title = t.title, trackIndex = t.index, startSeconds = 0.0))
            }
        }

        return AudiobookPublication(
            metadata = Metadata(
                title = title,
                authors = authors,
                language = language,
                identifier = identifier,
                coverHref = coverHref,
            ),
            tracks = tracks,
            chapters = chapters,
            resources = resourcesList,
            provider = provider,
        )
    }

    private fun parseAudioArchiveZip(
        zipEntries: Set<String>,
        sourceName: String,
        provider: ResourceProvider,
        fallbackId: String,
    ): AudiobookPublication {
        val cleanEntries = zipEntries
            .filterNot { isIgnoredEntry(it) }
            .sortedWith(CbzParser::naturalCompare)

        val audioEntries = cleanEntries.filter { isAudioFile(it) }
        if (audioEntries.isEmpty()) {
            throw ReaderException.InvalidContent("Arquivo de audiobook sem faixas de áudio suportadas: '$sourceName'")
        }

        val coverHref = cleanEntries.firstOrNull { entry ->
            val lower = entry.lowercase()
            isImageFile(lower) && (lower.contains("cover") || lower.contains("capa") || lower.contains("folder"))
        } ?: cleanEntries.firstOrNull { isImageFile(it.lowercase()) }

        val resources = mutableListOf<Resource>()
        val tracks = audioEntries.mapIndexed { idx, href ->
            val mediaType = guessAudioMediaType(href)
            resources.add(Resource(href = href, mediaType = mediaType))
            val baseName = href.substringAfterLast('/').substringBeforeLast('.')
                .replace('_', ' ')
                .replace('-', ' ')
                .trim()
                .ifEmpty { "Faixa ${idx + 1}" }
            AudioTrack(
                index = idx,
                title = baseName,
                href = href,
                mediaType = mediaType,
            )
        }
        if (coverHref != null) {
            resources.add(Resource(href = coverHref, mediaType = guessImageMediaType(coverHref)))
        }

        val chapters = tracks.map { AudioChapter(title = it.title, trackIndex = it.index, startSeconds = 0.0) }
        val title = sourceName.substringBeforeLast('.')

        return AudiobookPublication(
            metadata = Metadata(
                title = title,
                identifier = fallbackId,
                coverHref = coverHref,
            ),
            tracks = tracks,
            chapters = chapters,
            resources = resources,
            provider = provider,
        )
    }

    private fun resolvePath(baseDir: String, rawHref: String): String {
        if (rawHref.startsWith("http://", ignoreCase = true) ||
            rawHref.startsWith("https://", ignoreCase = true)
        ) {
            return rawHref
        }
        return normalizeHref("$baseDir$rawHref")
    }

    private fun parseTimeFragment(fragment: String): Double {
        if (fragment.isBlank()) return 0.0
        val clean = fragment.trim()
        if (clean.startsWith("t=", ignoreCase = true)) {
            val rawVal = clean.substring(2).substringBefore(',').trim()
            rawVal.toDoubleOrNull()?.let { return it.coerceAtLeast(0.0) }
        }
        return 0.0
    }

    private fun extractLocalizedString(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        is JsonObject -> element.values.firstOrNull()?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        is JsonArray -> element.firstOrNull()?.let(::extractLocalizedString)
        else -> null
    }

    private fun extractAuthors(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        is JsonObject -> listOfNotNull(extractLocalizedString(element["name"]))
        is JsonArray -> element.flatMap { extractAuthors(it) }
        else -> emptyList()
    }

    private fun hasCoverRel(element: JsonElement?): Boolean = when (element) {
        is JsonPrimitive -> element.contentOrNull.equals("cover", ignoreCase = true)
        is JsonArray -> element.any { it.jsonPrimitive.contentOrNull.equals("cover", ignoreCase = true) }
        else -> false
    }

    private fun isIgnoredEntry(path: String): Boolean {
        val norm = normalizeHref(path)
        if (norm.isEmpty() || norm.endsWith("/")) return true
        val segments = norm.split('/')
        return segments.any { it.equals("__MACOSX", ignoreCase = true) || it.startsWith(".") }
    }

    private fun isAudioFile(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in supportedAudioExtensions
    }

    private fun isImageFile(lowerPath: String): Boolean {
        val ext = lowerPath.substringAfterLast('.', "")
        return ext in supportedImageExtensions
    }

    private fun guessAudioMediaType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> "audio/mpeg"
    }

    private fun guessImageMediaType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    public companion object {
        private val supportedAudioExtensions = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "wav", "flac")
        private val supportedImageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    }
}

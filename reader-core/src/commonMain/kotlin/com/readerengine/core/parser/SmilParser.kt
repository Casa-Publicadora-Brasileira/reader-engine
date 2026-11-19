package com.readerengine.core.parser

import com.readerengine.core.model.MediaOverlay
import com.readerengine.core.model.MediaOverlayClip
import com.readerengine.core.provider.normalizeHref

/**
 * Parser multiplataforma para EPUB 3 Media Overlays (`.smil` — SMIL 3.0).
 * Extrai pares sincronizados `<par>` contendo `<text src="...#id"/>` e
 * `<audio src="..." clipBegin="..." clipEnd="..."/>`, normalizando os caminhos relativos
 * em relação ao diretório do arquivo `.smil`.
 */
public object SmilParser {

    private val parBlockRegex = Regex(
        """<(?:[a-zA-Z0-9_-]+:)?par\b([^>]*)>([\s\S]*?)</(?:[a-zA-Z0-9_-]+:)?par>""",
        RegexOption.IGNORE_CASE,
    )
    private val textTagRegex = Regex(
        """<(?:[a-zA-Z0-9_-]+:)?text\b([^>]*)/?>""",
        RegexOption.IGNORE_CASE,
    )
    private val audioTagRegex = Regex(
        """<(?:[a-zA-Z0-9_-]+:)?audio\b([^>]*)/?>""",
        RegexOption.IGNORE_CASE,
    )

    public fun parse(smilXml: String, smilHref: String): MediaOverlay {
        val normalizedSmilHref = normalizeHref(smilHref)
        val baseDir = normalizedSmilHref.substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isEmpty()) "" else "$it/" }

        val clips = mutableListOf<MediaOverlayClip>()
        for (match in parBlockRegex.findAll(smilXml)) {
            val parAttrs = match.groupValues[1]
            val inner = match.groupValues[2]

            val textAttrs = textTagRegex.find(inner)?.groupValues?.get(1) ?: continue
            val audioAttrs = audioTagRegex.find(inner)?.groupValues?.get(1) ?: continue

            val rawTextSrc = extractAttr(textAttrs, "src")?.trim().orEmpty()
            val rawAudioSrc = extractAttr(audioAttrs, "src")?.trim().orEmpty()
            if (rawTextSrc.isEmpty() || rawAudioSrc.isEmpty()) continue

            val rawTextPath = rawTextSrc.substringBefore('#')
            val fragmentId = rawTextSrc.substringAfter('#', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }
            val resolvedTextHref = normalizeHref("$baseDir$rawTextPath")
            val resolvedAudioHref = normalizeHref("$baseDir$rawAudioSrc")

            val beginSec = extractAttr(audioAttrs, "clipBegin")?.let(::parseClockValue) ?: 0.0
            val endSec = extractAttr(audioAttrs, "clipEnd")?.let(::parseClockValue)
            val parId = extractAttr(parAttrs, "id")?.takeIf { it.isNotBlank() }

            clips.add(
                MediaOverlayClip(
                    id = parId,
                    textHref = resolvedTextHref,
                    textFragmentId = fragmentId,
                    audioHref = resolvedAudioHref,
                    clipBeginSeconds = beginSec.coerceAtLeast(0.0),
                    clipEndSeconds = endSec?.coerceAtLeast(beginSec),
                ),
            )
        }

        return MediaOverlay(
            href = normalizedSmilHref,
            clips = clips,
        )
    }

    /**
     * Faz o parse de valores de tempo SMIL 3.0 em segundos:
     * - Full clock: `hh:mm:ss(.fff)` (ex.: `"0:01:23.450"`)
     * - Partial clock: `mm:ss(.fff)` (ex.: `"01:23.5"`)
     * - Timecount: `"12.5s"`, `"1250ms"`, `"2min"`, `"1h"` ou número puro `"12.5"`
     */
    public fun parseClockValue(raw: String): Double? {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return null

        if (s.contains(':')) {
            val parts = s.split(':')
            return when (parts.size) {
                2 -> {
                    val minutes = parts[0].toDoubleOrNull() ?: return null
                    val seconds = parts[1].toDoubleOrNull() ?: return null
                    (minutes * 60.0) + seconds
                }
                3 -> {
                    val hours = parts[0].toDoubleOrNull() ?: return null
                    val minutes = parts[1].toDoubleOrNull() ?: return null
                    val seconds = parts[2].toDoubleOrNull() ?: return null
                    (hours * 3600.0) + (minutes * 60.0) + seconds
                }
                else -> null
            }
        }

        return when {
            s.endsWith("ms") -> s.removeSuffix("ms").trim().toDoubleOrNull()?.div(1000.0)
            s.endsWith("min") -> s.removeSuffix("min").trim().toDoubleOrNull()?.times(60.0)
            s.endsWith("h") -> s.removeSuffix("h").trim().toDoubleOrNull()?.times(3600.0)
            s.endsWith("s") -> s.removeSuffix("s").trim().toDoubleOrNull()
            else -> s.toDoubleOrNull()
        }
    }

    private fun extractAttr(attrs: String, name: String): String? {
        val regex = Regex("""(?:^|\s)$name\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        val m = regex.find(attrs) ?: return null
        val raw = if (m.groups[1] != null) m.groupValues[1] else m.groupValues[2]
        return XmlEntities.decode(raw)
    }
}

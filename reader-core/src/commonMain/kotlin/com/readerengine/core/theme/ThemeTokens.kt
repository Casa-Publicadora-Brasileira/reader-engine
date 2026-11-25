package com.readerengine.core.theme

import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.ReadingMode
import com.readerengine.core.model.RenditionLayout
import com.readerengine.core.model.RenditionSpread
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public enum class DesignSystem {
    @SerialName("material3")
    MATERIAL3,

    @SerialName("hig")
    HIG,

    @SerialName("fluent")
    FLUENT,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
public data class Colors(
    val background: String,
    val surface: String,
    @SerialName("on_surface")
    val onSurface: String,
    val primary: String,
    @SerialName("on_primary")
    val onPrimary: String,
    @SerialName("night_background")
    val nightBackground: String = "#131313",
    @SerialName("night_on_surface")
    val nightOnSurface: String = "#EAEAEA",
    @SerialName("night_primary")
    val nightPrimary: String = "#6AB7FF",
)

@Serializable
public data class Typography(
    @SerialName("font_family")
    val fontFamily: String,
    @SerialName("base_size_px")
    val baseSizePx: Float,
    @SerialName("line_height")
    val lineHeight: Float,
    @SerialName("text_align")
    val textAlign: String = "justify",
    val hyphens: Boolean = true,
    val orphans: Int = 2,
    val widows: Int = 2,
    @SerialName("letter_spacing_em")
    val letterSpacingEm: Float = 0f,
    @SerialName("word_spacing_em")
    val wordSpacingEm: Float = 0f,
    @SerialName("paragraph_spacing_em")
    val paragraphSpacingEm: Float = 0.75f,
) {
    public constructor(
        fontFamily: String,
        baseSizePx: Float,
        lineHeight: Float,
    ) : this(
        fontFamily = fontFamily,
        baseSizePx = baseSizePx,
        lineHeight = lineHeight,
        textAlign = "justify",
        hyphens = true,
        orphans = 2,
        widows = 2,
        letterSpacingEm = 0f,
        wordSpacingEm = 0f,
        paragraphSpacingEm = 0.75f,
    )

    public constructor(
        fontFamily: String,
        baseSizePx: Float,
        lineHeight: Float,
        textAlign: String,
    ) : this(
        fontFamily = fontFamily,
        baseSizePx = baseSizePx,
        lineHeight = lineHeight,
        textAlign = textAlign,
        hyphens = true,
        orphans = 2,
        widows = 2,
        letterSpacingEm = 0f,
        wordSpacingEm = 0f,
        paragraphSpacingEm = 0.75f,
    )

    public constructor(
        fontFamily: String,
        baseSizePx: Float,
        lineHeight: Float,
        textAlign: String,
        hyphens: Boolean,
        orphans: Int,
        widows: Int,
    ) : this(
        fontFamily = fontFamily,
        baseSizePx = baseSizePx,
        lineHeight = lineHeight,
        textAlign = textAlign,
        hyphens = hyphens,
        orphans = orphans,
        widows = widows,
        letterSpacingEm = 0f,
        wordSpacingEm = 0f,
        paragraphSpacingEm = 0.75f,
    )
}

@Serializable
public data class Shape(
    @SerialName("corner_radius_px")
    val cornerRadiusPx: Float,
    @SerialName("spacing_unit_px")
    val spacingUnitPx: Float,
    @SerialName("horizontal_padding_px")
    val horizontalPaddingPx: Int = 24,
    @SerialName("max_line_width_px")
    val maxLineWidthPx: Int = 0,
) {
    public constructor(
        cornerRadiusPx: Float,
        spacingUnitPx: Float,
    ) : this(
        cornerRadiusPx = cornerRadiusPx,
        spacingUnitPx = spacingUnitPx,
        horizontalPaddingPx = 24,
        maxLineWidthPx = 0,
    )

    public constructor(
        cornerRadiusPx: Float,
        spacingUnitPx: Float,
        horizontalPaddingPx: Int,
    ) : this(
        cornerRadiusPx = cornerRadiusPx,
        spacingUnitPx = spacingUnitPx,
        horizontalPaddingPx = horizontalPaddingPx,
        maxLineWidthPx = 0,
    )
}

/** Definição segura de fonte customizada injetada via regra CSS `@font-face`. */
@Serializable
public data class CustomFontFace(
    val family: String,
    @SerialName("src_url")
    val srcUrl: String,
    val weight: String = "normal",
    val style: String = "normal",
    val format: String? = null,
)

@Serializable
public data class ThemeTokens(
    @SerialName("design_system")
    val designSystem: DesignSystem = DesignSystem.MATERIAL3,
    val colors: Colors,
    val typography: Typography,
    val shape: Shape,
    @SerialName("publisher_styles")
    val publisherStyles: Boolean = false,
    @SerialName("custom_font_faces")
    val customFontFaces: List<CustomFontFace> = emptyList(),
) {
    public constructor(
        designSystem: DesignSystem = DesignSystem.MATERIAL3,
        colors: Colors,
        typography: Typography,
        shape: Shape,
    ) : this(
        designSystem = designSystem,
        colors = colors,
        typography = typography,
        shape = shape,
        publisherStyles = false,
        customFontFaces = emptyList(),
    )
    /**
     * Gera o CSS injetado no HTML da WebView respeitando escala de fonte, família de fonte customizada,
     * modo noturno, alinhamento de texto, margens horizontais, paginação horizontal por colunas CSS
     * (`ReadingMode.PAGINATED`), layout fixo (`RenditionLayout.PRE_PAGINATED`), spreads (`RenditionSpread`)
     * e blocos matemáticos (`<math>` / MathML), sanitizando todos os valores contra injeção de CSS/HTML.
     */
    public fun toContentCss(
        fontScale: Float,
        customFontFamily: String?,
        isNightMode: Boolean,
        readingMode: ReadingMode,
        layout: RenditionLayout,
    ): String = toContentCss(
        fontScale = fontScale,
        customFontFamily = customFontFamily,
        isNightMode = isNightMode,
        readingMode = readingMode,
        layout = layout,
        spread = RenditionSpread.AUTO,
    )

    public fun toContentCss(
        fontScale: Float = 1.0f,
        customFontFamily: String? = null,
        isNightMode: Boolean = false,
        readingMode: ReadingMode = ReadingMode.SCROLL,
        layout: RenditionLayout = RenditionLayout.REFLOWABLE,
        spread: RenditionSpread = RenditionSpread.AUTO,
    ): String {
        val bg = sanitizeCssColor(
            raw = if (isNightMode) colors.nightBackground else colors.background,
            fallback = if (isNightMode) "#131313" else "#FFFBFE",
        )
        val fg = sanitizeCssColor(
            raw = if (isNightMode) colors.nightOnSurface else colors.onSurface,
            fallback = if (isNightMode) "#EAEAEA" else "#1C1B1F",
        )
        val linkColor = sanitizeCssColor(
            raw = if (isNightMode) colors.nightPrimary else colors.primary,
            fallback = if (isNightMode) "#6AB7FF" else "#6750A4",
        )
        val rawFamily = if (!customFontFamily.isNullOrBlank() && customFontFamily != "Default" && customFontFamily != "defaultFont") {
            customFontFamily
        } else {
            typography.fontFamily
        }
        val family = sanitizeCssFontFamily(rawFamily, fallback = "system-ui, sans-serif")
        val safeScale = fontScale.coerceIn(0.5f, 3.0f)
        val fontSizePx = (typography.baseSizePx.coerceIn(8f, 48f)) * safeScale
        val lineHeight = typography.lineHeight.coerceIn(1.0f, 3.0f)

        return buildString {
            append(":root{color-scheme:")
            append(if (isNightMode) "dark" else "light")
            append(";}")
            for (face in customFontFaces) {
                val safeFaceFamily = sanitizeCssFontFamily(face.family, fallback = "")
                val safeUrl = sanitizeCssFontUrl(face.srcUrl)
                if (safeFaceFamily.isNotEmpty() && safeUrl != null) {
                    val safeWeight = sanitizeCssFontWeight(face.weight)
                    val safeStyle = sanitizeCssFontStyle(face.style)
                    val safeFmt = sanitizeCssFontFormat(face.format)
                    append("@font-face{font-family:'")
                    append(safeFaceFamily.trim('\'', '"'))
                    append("';src:url('")
                    append(safeUrl)
                    append("')")
                    if (safeFmt != null) {
                        append(" format('")
                        append(safeFmt)
                        append("')")
                    }
                    append(";font-weight:")
                    append(safeWeight)
                    append(";font-style:")
                    append(safeStyle)
                    append(";font-display:swap;}")
                }
            }
            if (layout == RenditionLayout.PRE_PAGINATED) {
                append("html,body{background:$bg;background-color:$bg !important;color:$fg !important;margin:0;padding:0;overflow:hidden;}")
                if (spread == RenditionSpread.LANDSCAPE || spread == RenditionSpread.BOTH) {
                    append("@media (orientation: landscape){html.reader-fixed-layout body{display:flex;justify-content:center;align-items:center;}}")
                }
            } else {
                val padH = shape.horizontalPaddingPx.coerceIn(0, 240)
                val safeMaxWidth = shape.maxLineWidthPx.coerceIn(0, 2400)
                append(":root{--reader-base-pad-h:${padH}px;--reader-max-line-width:${safeMaxWidth}px;}")
                if (publisherStyles) {
                    val scalePct = (safeScale * 100f).toInt().coerceIn(50, 300)
                    append("html,body{background-color:$bg !important;color:$fg !important;font-size:$scalePct%;}")
                    append("body{padding-left:${padH}px !important;padding-right:${padH}px !important;box-sizing:border-box;}")
                    append("body *:not([class*=\"highlight_\"]){color:$fg !important;background-color:transparent !important;}")
                } else {
                    val safeTextAlign = sanitizeCssTextAlign(typography.textAlign) ?: "justify"
                    val hyphensRule = if (typography.hyphens) "auto" else "none"
                    val safeOrphans = typography.orphans.coerceIn(1, 5)
                    val safeWidows = typography.widows.coerceIn(1, 5)
                    append("html,body{background-color:$bg !important;color:$fg !important;font-family:$family !important;font-size:${fontSizePx}px !important;line-height:$lineHeight !important;}")
                    append("body{padding-left:${padH}px !important;padding-right:${padH}px !important;text-align:$safeTextAlign !important;box-sizing:border-box;}")
                    append("body *:not([class*=\"highlight_\"]){color:$fg !important;background-color:transparent !important;line-height:inherit;}")
                    append("body p,body li,body blockquote,body dd,body dt{font-size:1em !important;}")
                    append("body,body p,body div,body li,body blockquote{text-align:$safeTextAlign !important;}")
                    append("body,body p,body div,body li,body blockquote{hyphens:$hyphensRule;-webkit-hyphens:$hyphensRule;orphans:$safeOrphans;widows:$safeWidows;}")
                    val safeLetterSpacing = typography.letterSpacingEm.coerceIn(-0.05f, 0.5f)
                    val safeWordSpacing = typography.wordSpacingEm.coerceIn(0f, 1.0f)
                    val safeParagraphSpacing = typography.paragraphSpacingEm.coerceIn(0f, 3.0f)
                    if (safeLetterSpacing != 0f || safeWordSpacing > 0f) {
                        append("body,body p,body li,body blockquote{")
                        if (safeLetterSpacing != 0f) append("letter-spacing:${safeLetterSpacing}em !important;")
                        if (safeWordSpacing > 0f) append("word-spacing:${safeWordSpacing}em !important;")
                        append("}")
                    }
                    append("body p{margin-bottom:${safeParagraphSpacing}em;}")
                }
                if (safeMaxWidth > 0) {
                    append("html:not(.reader-paginated) body{max-width:${safeMaxWidth}px;margin-left:auto !important;margin-right:auto !important;}")
                }
                append("html.reader-paginated{height:var(--reader-page-height,100vh) !important;min-height:var(--reader-page-height,100vh) !important;max-height:var(--reader-page-height,100vh) !important;width:var(--reader-page-width,100%) !important;overflow:hidden !important;margin:0 !important;padding:0 !important;box-sizing:border-box !important;}")
                append("html.reader-paginated body{height:var(--reader-page-height,100vh) !important;min-height:var(--reader-page-height,100vh) !important;max-height:var(--reader-page-height,100vh) !important;width:var(--reader-page-width,100%) !important;overflow:visible !important;margin:0 !important;box-sizing:border-box !important;}")
                append("html.reader-paginated body{column-width:calc(var(--reader-page-width,100vw) - (var(--reader-pad-h,${padH}px) * 2));column-gap:calc(var(--reader-pad-h,${padH}px) * 2);column-fill:auto;padding:16px var(--reader-pad-h,${padH}px) !important;}")
                append("html.reader-paginated body,html.reader-paginated body *{overflow-wrap:break-word;word-wrap:break-word;}")
                append("html.reader-paginated body > div,html.reader-paginated body > section,html.reader-paginated body > article,html.reader-paginated body > main{height:auto !important;min-height:0 !important;max-height:none !important;overflow:visible !important;}")
                append("html.reader-paginated img,html.reader-paginated svg,html.reader-paginated video{max-height:calc(var(--reader-page-height,100vh) - 48px) !important;max-width:100% !important;break-inside:avoid;}")
                append("html.reader-paginated figure,html.reader-paginated table,html.reader-paginated pre,html.reader-paginated blockquote{break-inside:avoid;-webkit-column-break-inside:avoid;}table{max-width:100%;overflow-x:auto;}")
                when (spread) {
                    RenditionSpread.LANDSCAPE -> {
                        append("@media (min-width: 840px) and (orientation: landscape){html.reader-paginated body{column-count:2;column-width:auto;column-gap:calc(var(--reader-pad-h,${padH}px) * 2);}}")
                    }
                    RenditionSpread.AUTO, RenditionSpread.BOTH -> {
                        append("@media (min-width: 840px){html.reader-paginated body{column-count:2;column-width:auto;column-gap:calc(var(--reader-pad-h,${padH}px) * 2);}}")
                    }
                    RenditionSpread.NONE -> Unit
                }
            }
            append("a,a *:not([class*=\"highlight_\"]){color:$linkColor !important;}")
            append("img,svg,video{max-width:100%;height:auto;}")
            append("math{font-family:'Latin Modern Math','STIX Two Math','Cambria Math','Noto Sans Math',serif;display:inline-block;max-width:100%;overflow-x:auto;vertical-align:middle;}")
            append("math[display=\"block\"]{display:block;text-align:center;margin:0.75em 0;}")
            append(".highlight_yellow,.highlight_yellow *{background-color:#FFF59D !important;color:#111 !important;}")
            append(".highlight_green,.highlight_green *{background-color:#A5D6A7 !important;color:#111 !important;}")
            append(".highlight_blue,.highlight_blue *{background-color:#90CAF9 !important;color:#111 !important;}")
            append(".highlight_pink,.highlight_pink *{background-color:#F48FB1 !important;color:#111 !important;}")
            append(".highlight_red,.highlight_red *{background-color:#EF9A9A !important;color:#111 !important;}")
            append(".highlight_orange,.highlight_orange *{background-color:#FFCC80 !important;color:#111 !important;}")
            append(".highlight_purple,.highlight_purple *{background-color:#CE93D8 !important;color:#111 !important;}")
            append(".highlight_dashed{text-decoration:line-through !important;}")
            append(".highlight_under{text-decoration:underline !important;}")
            append(".highlight_annotation,.highlight_annotation *{background-color:#FFE082 !important;border-bottom:2px dotted #F57C00;color:#111 !important;}")
            val ttsBg = if (isNightMode) "rgba(106,183,255,0.32)" else "rgba(255,213,79,0.45)"
            append(".reader-tts-active,.reader-tts-active *{background-color:$ttsBg !important;border-radius:3px;transition:background-color 0.15s ease;}")
            val searchBg = if (isNightMode) "#FFB300" else "#FFCA28"
            append(".reader-search-match,.reader-search-match *{background-color:$searchBg !important;color:#111 !important;border-radius:3px;padding:0 2px;box-shadow:0 0 0 2px $searchBg;}")
        }
    }

    public companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
        private val hexColorRegex = Regex("""^#[0-9a-fA-F]{3,8}$""")
        private val safeFontCharsRegex = Regex("""^[a-zA-Z0-9\s,'"_.-]+$""")
        private val allowedTextAligns = setOf("justify", "left", "right", "center", "start", "end")
        private val allowedFontWeights = setOf("normal", "bold", "lighter", "bolder", "100", "200", "300", "400", "500", "600", "700", "800", "900")
        private val allowedFontStyles = setOf("normal", "italic", "oblique")
        private val allowedFontFormats = setOf("truetype", "opentype", "woff", "woff2", "embedded-opentype", "svg")

        internal fun sanitizeCssFontUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val lower = trimmed.lowercase()
            if (lower.startsWith("javascript:") || lower.startsWith("vbscript:") || lower.startsWith("data:text/html")) {
                return null
            }
            if (trimmed.contains("<") || trimmed.contains(">") || trimmed.contains("\"") ||
                trimmed.contains("'") || trimmed.contains("(") || trimmed.contains(")") ||
                trimmed.contains(";") || trimmed.contains("{") || trimmed.contains("}") ||
                trimmed.contains("\\") || trimmed.contains("\n") || trimmed.contains("\r")
            ) {
                return null
            }
            return trimmed
        }

        internal fun sanitizeCssFontWeight(raw: String): String {
            val normalized = raw.trim().lowercase()
            return if (normalized in allowedFontWeights) normalized else "normal"
        }

        internal fun sanitizeCssFontStyle(raw: String): String {
            val normalized = raw.trim().lowercase()
            return if (normalized in allowedFontStyles) normalized else "normal"
        }

        internal fun sanitizeCssFontFormat(raw: String?): String? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return normalized.takeIf { it in allowedFontFormats }
        }

        internal fun sanitizeCssTextAlign(raw: String?): String? {
            val normalized = raw?.trim()?.lowercase() ?: return null
            return normalized.takeIf { it in allowedTextAligns }
        }

        internal fun sanitizeCssColor(raw: String, fallback: String): String {
            val trimmed = raw.trim()
            return if (hexColorRegex.matches(trimmed)) trimmed else fallback
        }

        internal fun sanitizeCssFontFamily(raw: String, fallback: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return fallback
            if (trimmed.contains("</") || trimmed.contains("<") || trimmed.contains(">") ||
                trimmed.contains(";") || trimmed.contains("{") || trimmed.contains("}") ||
                trimmed.contains("\\")
            ) {
                return fallback
            }
            return if (safeFontCharsRegex.matches(trimmed)) trimmed else fallback
        }

        public fun fromJson(json: String): ThemeTokens = try {
            jsonParser.decodeFromString(serializer(), json)
        } catch (e: Exception) {
            throw ReaderException.InvalidContent("JSON de ThemeTokens inválido: ${e.message}", e)
        }

        public fun material3(): ThemeTokens = ThemeTokens(
            designSystem = DesignSystem.MATERIAL3,
            colors = Colors(
                background = "#FFFBFE",
                surface = "#F3EDF7",
                onSurface = "#1C1B1F",
                primary = "#6750A4",
                onPrimary = "#FFFFFF",
            ),
            typography = Typography(
                fontFamily = "Roboto, system-ui, sans-serif",
                baseSizePx = 18f,
                lineHeight = 1.6f,
            ),
            shape = Shape(cornerRadiusPx = 12f, spacingUnitPx = 8f),
        )

        public fun hig(): ThemeTokens = ThemeTokens(
            designSystem = DesignSystem.HIG,
            colors = Colors(
                background = "#FFFFFF",
                surface = "#F2F2F7",
                onSurface = "#000000",
                primary = "#007AFF",
                onPrimary = "#FFFFFF",
            ),
            typography = Typography(
                fontFamily = "-apple-system, SF Pro Text, Georgia, serif",
                baseSizePx = 17f,
                lineHeight = 1.55f,
            ),
            shape = Shape(cornerRadiusPx = 10f, spacingUnitPx = 8f),
        )

        public fun fluent(): ThemeTokens = ThemeTokens(
            designSystem = DesignSystem.FLUENT,
            colors = Colors(
                background = "#FFFFFF",
                surface = "#F3F3F3",
                onSurface = "#1A1A1A",
                primary = "#0F6CBD",
                onPrimary = "#FFFFFF",
            ),
            typography = Typography(
                fontFamily = "'Segoe UI Variable', 'Segoe UI', sans-serif",
                baseSizePx = 14f,
                lineHeight = 1.43f,
            ),
            shape = Shape(cornerRadiusPx = 4f, spacingUnitPx = 4f),
        )

        /** Preset Sépia (papel quente de baixo cansaço visual para leitura prolongada). */
        public fun sepia(): ThemeTokens = ThemeTokens(
            designSystem = DesignSystem.CUSTOM,
            colors = Colors(
                background = "#F4ECD8",
                surface = "#EAE0C8",
                onSurface = "#3B2F20",
                primary = "#8B5A2B",
                onPrimary = "#FFFDF8",
                nightBackground = "#1C1915",
                nightOnSurface = "#E5DAC3",
                nightPrimary = "#D4A373",
            ),
            typography = Typography(
                fontFamily = "Georgia, 'Bookerly', serif",
                baseSizePx = 18f,
                lineHeight = 1.65f,
                textAlign = "justify",
                hyphens = true,
                orphans = 2,
                widows = 2,
                paragraphSpacingEm = 0.85f,
            ),
            shape = Shape(
                cornerRadiusPx = 10f,
                spacingUnitPx = 8f,
                horizontalPaddingPx = 28,
                maxLineWidthPx = 720,
            ),
        )

        /** Preset AMOLED Alto Contraste (preto absoluto `#000000` e texto branco `#FFFFFF`). */
        public fun amoledDark(): ThemeTokens = ThemeTokens(
            designSystem = DesignSystem.CUSTOM,
            colors = Colors(
                background = "#000000",
                surface = "#111111",
                onSurface = "#FFFFFF",
                primary = "#82CFFF",
                onPrimary = "#000000",
                nightBackground = "#000000",
                nightOnSurface = "#FFFFFF",
                nightPrimary = "#82CFFF",
            ),
            typography = Typography(
                fontFamily = "system-ui, -apple-system, sans-serif",
                baseSizePx = 19f,
                lineHeight = 1.7f,
                textAlign = "left",
                hyphens = false,
                orphans = 2,
                widows = 2,
                paragraphSpacingEm = 0.9f,
            ),
            shape = Shape(
                cornerRadiusPx = 8f,
                spacingUnitPx = 8f,
                horizontalPaddingPx = 24,
                maxLineWidthPx = 720,
            ),
        )

        /**
         * Preset otimizado para dislexia e baixa visão (WCAG/DAISY):
         * alinhamento à esquerda, sem hifenização, maior espaçamento entre letras, palavras e parágrafos.
         */
        public fun dyslexiaFriendly(): ThemeTokens = ThemeTokens(
            designSystem = DesignSystem.CUSTOM,
            colors = Colors(
                background = "#FAF8F2",
                surface = "#EFECE2",
                onSurface = "#1F1F1F",
                primary = "#1A56DB",
                onPrimary = "#FFFFFF",
                nightBackground = "#1A1C1E",
                nightOnSurface = "#ECEAE4",
                nightPrimary = "#7BB4FF",
            ),
            typography = Typography(
                fontFamily = "'OpenDyslexic', 'Atkinson Hyperlegible', Verdana, Arial, sans-serif",
                baseSizePx = 19f,
                lineHeight = 1.85f,
                textAlign = "left",
                hyphens = false,
                orphans = 2,
                widows = 2,
                letterSpacingEm = 0.08f,
                wordSpacingEm = 0.16f,
                paragraphSpacingEm = 1.25f,
            ),
            shape = Shape(
                cornerRadiusPx = 12f,
                spacingUnitPx = 10f,
                horizontalPaddingPx = 28,
                maxLineWidthPx = 680,
            ),
        )
    }
}

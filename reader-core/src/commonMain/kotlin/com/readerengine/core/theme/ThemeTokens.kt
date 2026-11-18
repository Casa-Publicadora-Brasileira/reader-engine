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
) {
    public constructor(
        cornerRadiusPx: Float,
        spacingUnitPx: Float,
    ) : this(
        cornerRadiusPx = cornerRadiusPx,
        spacingUnitPx = spacingUnitPx,
        horizontalPaddingPx = 24,
    )
}

@Serializable
public data class ThemeTokens(
    @SerialName("design_system")
    val designSystem: DesignSystem = DesignSystem.MATERIAL3,
    val colors: Colors,
    val typography: Typography,
    val shape: Shape,
) {
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
            if (layout == RenditionLayout.PRE_PAGINATED) {
                append("html,body{background:$bg;background-color:$bg !important;color:$fg !important;margin:0;padding:0;overflow:hidden;}")
                if (spread == RenditionSpread.LANDSCAPE || spread == RenditionSpread.BOTH) {
                    append("@media (orientation: landscape){html.reader-fixed-layout body{display:flex;justify-content:center;align-items:center;}}")
                }
            } else {
                val safeTextAlign = sanitizeCssTextAlign(typography.textAlign) ?: "justify"
                val padH = shape.horizontalPaddingPx.coerceIn(0, 240)
                append("html,body{background-color:$bg !important;color:$fg !important;font-family:$family !important;font-size:${fontSizePx}px;line-height:$lineHeight !important;}")
                append("body{padding-left:${padH}px !important;padding-right:${padH}px !important;text-align:$safeTextAlign !important;box-sizing:border-box;}")
                append("body *:not([class*=\"highlight_\"]){color:$fg !important;background-color:transparent !important;line-height:inherit;}")
                append("body,body p,body div,body li,body blockquote{text-align:$safeTextAlign !important;}")
                if (readingMode == ReadingMode.PAGINATED) {
                    val gap = padH * 2
                    append("html.reader-paginated,html.reader-paginated body{height:100vh;overflow:hidden;margin:0;box-sizing:border-box;}")
                    append("html.reader-paginated body{column-width:calc(100vw - ${gap}px);column-gap:${gap}px;column-fill:auto;padding:16px ${padH}px !important;}")
                    if (spread == RenditionSpread.LANDSCAPE || spread == RenditionSpread.BOTH) {
                        append("@media (orientation: landscape){html.reader-paginated body{column-count:2;column-width:auto;column-gap:${gap}px;}}")
                    }
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
        }
    }

    public companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
        private val hexColorRegex = Regex("""^#[0-9a-fA-F]{3,8}$""")
        private val safeFontCharsRegex = Regex("""^[a-zA-Z0-9\s,'"_.-]+$""")
        private val allowedTextAligns = setOf("justify", "left", "right", "center", "start", "end")

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
    }
}

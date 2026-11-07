package com.readerengine.core.theme

import com.readerengine.core.error.ReaderException
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
)

@Serializable
public data class Shape(
    @SerialName("corner_radius_px")
    val cornerRadiusPx: Float,
    @SerialName("spacing_unit_px")
    val spacingUnitPx: Float,
)

@Serializable
public data class ThemeTokens(
    @SerialName("design_system")
    val designSystem: DesignSystem = DesignSystem.MATERIAL3,
    val colors: Colors,
    val typography: Typography,
    val shape: Shape,
) {
    /**
     * Gera o CSS injetado no HTML da WebView respeitando escala de fonte, família de fonte customizada
     * e modo noturno, sanitizando todos os valores contra injeção de CSS/HTML.
     */
    public fun toContentCss(
        fontScale: Float = 1.0f,
        customFontFamily: String? = null,
        isNightMode: Boolean = false,
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
            append("html{background:$bg;color:$fg;font-family:$family;font-size:${fontSizePx}px;line-height:$lineHeight;}")
            append("body{background:$bg;color:$fg;font-family:$family;}")
            append("a{color:$linkColor;}")
            append(".highlight_yellow{background-color:#FFF59D;color:#111;}")
            append(".highlight_green{background-color:#A5D6A7;color:#111;}")
            append(".highlight_blue{background-color:#90CAF9;color:#111;}")
            append(".highlight_pink{background-color:#F48FB1;color:#111;}")
            append(".highlight_red{background-color:#EF9A9A;color:#111;}")
            append(".highlight_orange{background-color:#FFCC80;color:#111;}")
            append(".highlight_purple{background-color:#CE93D8;color:#111;}")
            append(".highlight_dashed{text-decoration:line-through;}")
            append(".highlight_under{text-decoration:underline;}")
            append(".highlight_annotation{background-color:#FFE082;border-bottom:2px dotted #F57C00;color:#111;}")
        }
    }

    public companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
        private val hexColorRegex = Regex("""^#[0-9a-fA-F]{3,8}$""")
        private val safeFontCharsRegex = Regex("""^[a-zA-Z0-9\s,'"_.-]+$""")

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

package com.cpb.reader.engine.theme

import com.cpb.reader.engine.error.ReaderException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class DesignSystem {
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
data class Colors(
    val background: String,
    val surface: String,
    @SerialName("on_surface")
    val onSurface: String,
    val primary: String,
    @SerialName("on_primary")
    val onPrimary: String,
)

@Serializable
data class Typography(
    @SerialName("font_family")
    val fontFamily: String,
    @SerialName("base_size_px")
    val baseSizePx: Float,
    @SerialName("line_height")
    val lineHeight: Float,
)

@Serializable
data class Shape(
    @SerialName("corner_radius_px")
    val cornerRadiusPx: Float,
    @SerialName("spacing_unit_px")
    val spacingUnitPx: Float,
)

@Serializable
data class ThemeTokens(
    @SerialName("design_system")
    val designSystem: DesignSystem = DesignSystem.MATERIAL3,
    val colors: Colors,
    val typography: Typography,
    val shape: Shape,
) {
    /**
     * Gera o CSS injetado no HTML da WebView respeitando escala de fonte, família de fonte customizada
     * e modo noturno (`#131313` usado no CPBBooks e EscolaSabatina).
     */
    fun toContentCss(
        fontScale: Float = 1.0f,
        customFontFamily: String? = null,
        isNightMode: Boolean = false,
    ): String {
        val bg = if (isNightMode) "#131313" else colors.background
        val fg = if (isNightMode) "#EAEAEA" else colors.onSurface
        val linkColor = if (isNightMode) "#6AB7FF" else colors.primary
        val family = if (!customFontFamily.isNullOrBlank() && customFontFamily != "Default" && customFontFamily != "defaultFont") {
            customFontFamily
        } else {
            typography.fontFamily
        }
        val fontSizePx = typography.baseSizePx * fontScale
        return buildString {
            append("html{background:$bg;color:$fg;font-family:$family;font-size:${fontSizePx}px;line-height:${typography.lineHeight};}")
            append("body{background:$bg;color:$fg;font-family:$family;margin:0;padding:16px;}")
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
        }
    }

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): ThemeTokens = try {
            jsonParser.decodeFromString(serializer(), json)
        } catch (e: Exception) {
            throw ReaderException.InvalidContent("JSON de ThemeTokens inválido: ${e.message}", e)
        }

        fun material3(): ThemeTokens = ThemeTokens(
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

        fun hig(): ThemeTokens = ThemeTokens(
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
    }
}

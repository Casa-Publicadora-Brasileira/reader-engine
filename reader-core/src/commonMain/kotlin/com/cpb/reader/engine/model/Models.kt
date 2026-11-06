package com.cpb.reader.engine.model

import com.cpb.reader.engine.theme.ThemeTokens
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
data class Metadata(
    val title: String = "",
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val identifier: String? = null,
)

@Serializable
data class Resource(
    val href: String,
    val mediaType: String,
)

@Serializable
data class TocEntry(
    val title: String,
    val subtitle: String? = null,
    val href: String,
    val children: List<TocEntry> = emptyList(),
)

/** Item do Índice / Sumário resolvido para o spine (substitui IndexReader nos apps). */
@Serializable
data class TocItem(
    val title: String,
    val subtitle: String? = null,
    val href: String,
    val spineIndex: Int,
)

/** Acesso sob demanda aos bytes dos recursos da publicação (ZIP, memória, disco). */
fun interface ResourceProvider {
    fun read(href: String): ByteArray
}

class Publication(
    val metadata: Metadata,
    val spine: List<String>,
    val resources: List<Resource>,
    val toc: List<TocEntry>,
    val provider: ResourceProvider,
) {
    fun read(href: String): ByteArray = provider.read(href)

    fun readString(href: String): String = read(href).decodeToString()

    fun findResource(href: String): Resource? {
        val clean = href.substringBefore('#').removePrefix("/")
        return resources.firstOrNull { it.href == clean || it.href.endsWith("/$clean") }
    }
}

/**
 * Posição exata e progresso de leitura unificados:
 * - Atende `ReaderReadingPositionStore` (pageIndex + scrollY) do EscolaSabatina
 * - Atende `Progress2` (totalPercentage 0..100, pageNumber, totalPages, chapterTitle, lastUpdate) do CPBBooks
 */
@Serializable
data class ReadingProgress(
    val documentId: String,
    val pageIndex: Int = 0,
    val pageNumber: Int = 1,
    val totalPages: Int = 1,
    val scrollY: Int = 0,
    val pageProgression: Float = 0f,
    val totalPercentage: Float = 0f,
    val chapterTitle: String = "",
    val lastUpdateEpochMs: Long = 0L,
) {
    companion object {
        /**
         * Calcula o percentual global (0..100%) combinando o índice da página no spine e o scroll atual,
         * mantendo paridade com `BakerEpubView.calculateProgress()`.
         */
        fun calculatePercentage(
            pageIndex: Int,
            totalPages: Int,
            scrollY: Int = 0,
            contentHeightPx: Int = 0,
            viewportHeightPx: Int = 0,
        ): Float {
            if (totalPages <= 0) return 0f
            if (totalPages == 1 || pageIndex >= totalPages - 1) {
                return 100f
            }
            val safePage = pageIndex.coerceIn(0, totalPages - 1)
            val basePageProgress = (safePage.toDouble() / (totalPages - 1).toDouble()) * 100.0
            val scrollFraction = if (contentHeightPx > viewportHeightPx && viewportHeightPx > 0) {
                val maxScroll = (contentHeightPx - viewportHeightPx).coerceAtLeast(1)
                (scrollY.coerceAtLeast(0).toDouble() / maxScroll.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }
            val scrollContribution = scrollFraction * (100.0 / totalPages.toDouble())
            val combined = basePageProgress + scrollContribution
            return min(max(combined, 0.0), 100.0).toFloat()
        }
    }
}

/** Estilos e cores de marcação suportados pelos 4 apps (compatível com HighlightNew / HighLight). */
@Serializable
enum class HighlightStyle(val jsColorArg: String, val legacyTypeValue: String) {
    @SerialName("highlight_yellow")
    YELLOW("yellow", "highlight_yellow"),

    @SerialName("highlight_green")
    GREEN("green", "highlight_green"),

    @SerialName("highlight_blue")
    BLUE("blue", "highlight_blue"),

    @SerialName("highlight_pink")
    PINK("pink", "highlight_pink"),

    @SerialName("highlight_red")
    RED("red", "highlight_red"),

    @SerialName("highlight_orange")
    ORANGE("orange", "highlight_orange"),

    @SerialName("highlight_purple")
    PURPLE("purple", "highlight_purple"),

    @SerialName("highlight_dashed")
    DASHED("dashed", "highlight_dashed"),

    @SerialName("highlight_under")
    UNDERLINE("under", "highlight_under"),

    @SerialName("highlight_annotation")
    ANNOTATION("annotation", "highlight_annotation");

    companion object {
        fun fromRaw(raw: String): HighlightStyle {
            val normalized = raw.trim().lowercase()
            return entries.firstOrNull {
                normalized == it.jsColorArg ||
                    normalized == it.legacyTypeValue ||
                    normalized.contains(it.jsColorArg)
            } ?: YELLOW
        }
    }
}

/**
 * Representa um item de destaque extraído via `readerJS.summaryHighlights()`
 * (substitui `HighlightNew` em CPBBooks e EscolaSabatina).
 */
@Serializable
data class HighlightItem(
    val id: Int,
    val pageIndex: Int,
    val text: String,
    val style: HighlightStyle,
    val scrollPositionY: Double,
) {
    /** Offset recomendado para `scrollTo(0, targetScrollY)` ao clicar no grifo na lista. */
    val targetScrollY: Int
        get() = (scrollPositionY.toInt() - 300).coerceAtLeast(0)
}

/** Item individual de resposta ou anotação nas caixas de texto (substitui AnswerFirestore / AnnotationFirestore). */
@Serializable
data class FormEntry(
    val content: String,
    val index: Int,
)

/**
 * Estado completo de anotações e respostas de uma página da lição/livro,
 * mantendo 100% de compatibilidade com o JSON salvo na coleção `"annotations"` do Firestore:
 * `{"annotations": [{"content": "...", "index": 0}], "answers": [{"content": "...", "index": 0}]}`
 */
@Serializable
data class PageFormState(
    val annotations: List<FormEntry> = emptyList(),
    val answers: List<FormEntry> = emptyList(),
) {
    val hasNonBlankContent: Boolean
        get() = annotations.any { it.content.isNotBlank() } || answers.any { it.content.isNotBlank() }
}

/** Estado inicial fornecido pelo App ao abrir um documento. */
data class InitialBookState(
    val documentId: String = "",
    val initialPageIndex: Int = 0,
    val initialScrollY: Int = 0,
    val isNightMode: Boolean = false,
    val fontFamily: String = "Default",
    val fontSizeClass: String = "ESFontSize2",
    val fontScale: Float = 1.0f,
    val theme: ThemeTokens? = null,
    /** Mapa `pageIndex -> string serializada Rangy` vindo de `highlights/{docId|uid}` no Firestore. */
    val savedPageHighlights: Map<Int, String> = emptyMap(),
    /** Mapa `pageIndex -> JSON annotations/answers` vindo de `annotations/{docId|uid}` no Firestore. */
    val savedPageForms: Map<Int, String> = emptyMap(),
)

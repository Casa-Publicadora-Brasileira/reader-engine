package com.readerengine.core.model

import com.readerengine.core.drm.EncryptedResourceInfo
import com.readerengine.core.theme.ThemeTokens
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Define se a publicação é refluível ou de layout fixo (EPUB 3 `rendition:layout`). */
@Serializable
public enum class RenditionLayout {
    @SerialName("reflowable")
    REFLOWABLE,

    @SerialName("pre-paginated")
    PRE_PAGINATED,
}

/** Orientação preferencial da publicação (EPUB 3 `rendition:orientation`). */
@Serializable
public enum class RenditionOrientation {
    @SerialName("auto")
    AUTO,

    @SerialName("landscape")
    LANDSCAPE,

    @SerialName("portrait")
    PORTRAIT,
}

/** Comportamento de página dupla / spread (EPUB 3 `rendition:spread`). */
@Serializable
public enum class RenditionSpread {
    @SerialName("auto")
    AUTO,

    @SerialName("none")
    NONE,

    @SerialName("landscape")
    LANDSCAPE,

    @SerialName("both")
    BOTH,
}

/** Modo de renderização e navegação: rolagem vertical contínua ou paginação horizontal por colunas CSS. */
@Serializable
public enum class ReadingMode {
    @SerialName("scroll")
    SCROLL,

    @SerialName("paginated")
    PAGINATED,
}

@Serializable
public data class Metadata(
    val title: String = "",
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val identifier: String? = null,
    val layout: RenditionLayout = RenditionLayout.REFLOWABLE,
    val orientation: RenditionOrientation = RenditionOrientation.AUTO,
    val spread: RenditionSpread = RenditionSpread.AUTO,
)

@Serializable
public data class Resource(
    val href: String,
    val mediaType: String,
)

@Serializable
public data class TocEntry(
    val title: String,
    val subtitle: String? = null,
    val href: String,
    val children: List<TocEntry> = emptyList(),
)

/** Item do Índice / Sumário resolvido para o spine. */
@Serializable
public data class TocItem(
    val title: String,
    val subtitle: String? = null,
    val href: String,
    val spineIndex: Int,
    val level: Int = 0,
)

/**
 * Localizador multiplataforma independente de resolução de tela (`Locator`),
 * combinando o `href` canônico no spine, `pageIndex`, fração de progressão (`0f..1f`)
 * e citação opcional (`textQuote`) para sincronização entre dispositivos.
 */
@Serializable
public data class Locator(
    val href: String,
    val pageIndex: Int,
    val progression: Float = 0f,
    val title: String? = null,
    val textQuote: String? = null,
)

/** Acesso sob demanda aos bytes dos recursos da publicação (ZIP, memória, disco). */
public interface ResourceProvider {
    public fun read(href: String): ByteArray

    public fun size(href: String): Long? =
        runCatching { read(href).size.toLong() }.getOrNull()

    public fun readRange(href: String, offset: Long, byteCount: Int): ByteArray {
        val all = read(href)
        if (offset < 0L || offset >= all.size.toLong() || byteCount <= 0) return ByteArray(0)
        val start = offset.toInt()
        val end = (start + byteCount).coerceAtMost(all.size)
        return all.copyOfRange(start, end)
    }

    public fun close(): Unit = Unit
}

public class Publication(
    public val metadata: Metadata,
    public val spine: List<String>,
    public val resources: List<Resource>,
    public val toc: List<TocEntry>,
    public val provider: ResourceProvider,
    public val encryptedResources: Map<String, EncryptedResourceInfo> = emptyMap(),
) {
    @kotlin.concurrent.Volatile
    private var plainTextCache: Map<String, String> = emptyMap()

    public fun read(href: String): ByteArray = provider.read(href)

    public fun size(href: String): Long? = provider.size(href)

    public fun readRange(href: String, offset: Long, byteCount: Int): ByteArray =
        provider.readRange(href, offset, byteCount)

    public fun readString(href: String): String = read(href).decodeToString()

    public fun close(): Unit = provider.close()

    internal fun cachedPlainText(href: String, compute: (String) -> String): String {
        plainTextCache[href]?.let { return it }
        val computed = compute(readString(href))
        val updated = LinkedHashMap<String, String>(plainTextCache.size + 1)
        updated.putAll(plainTextCache)
        updated[href] = computed
        plainTextCache = updated
        return computed
    }

    public fun findResource(href: String): Resource? {
        val clean = href.substringBefore('#').removePrefix("/")
        return resources.firstOrNull { it.href == clean || it.href.endsWith("/$clean") }
    }
}

/**
 * Posição exata e progresso de leitura unificados:
 * - Suporta persistência por página + scroll (`pageIndex` + `scrollY`) e `locator`
 * - Suporta percentual global monotônico `0..100%` (`totalPercentage`, `pageNumber`, `totalPages`, `chapterTitle`)
 */
@Serializable
public data class ReadingProgress(
    val documentId: String,
    val pageIndex: Int = 0,
    val pageNumber: Int = 1,
    val totalPages: Int = 1,
    val scrollY: Int = 0,
    val pageProgression: Float = 0f,
    val totalPercentage: Float = 0f,
    val chapterTitle: String = "",
    val lastUpdateEpochMs: Long = 0L,
    val locator: Locator? = null,
) {
    public companion object {
        /**
         * Calcula o percentual global monotônico (`0..100%`) combinando o índice da página no spine
         * e a fração de scroll dentro da página atual: `((pageIndex + scrollFraction) / totalPages) * 100`.
         */
        public fun calculatePercentage(
            pageIndex: Int,
            totalPages: Int,
            scrollY: Int = 0,
            contentHeightPx: Int = 0,
            viewportHeightPx: Int = 0,
        ): Float {
            if (totalPages <= 0) return 0f
            val safePage = pageIndex.coerceIn(0, totalPages - 1)
            val hasScrollMetrics = viewportHeightPx in 1 downTo contentHeightPx
            val scrollFraction = when {
                hasScrollMetrics -> {
                    val maxScroll = (contentHeightPx - viewportHeightPx).coerceAtLeast(1)
                    (scrollY.coerceAtLeast(0).toDouble() / maxScroll.toDouble()).coerceIn(0.0, 1.0)
                }
                totalPages == 1 -> 1.0
                safePage == totalPages - 1 && scrollY == 0 -> 1.0
                else -> 0.0
            }
            val combined = ((safePage.toDouble() + scrollFraction) / totalPages.toDouble()) * 100.0
            return combined.coerceIn(0.0, 100.0).toFloat()
        }
    }
}

/** Estilos e cores de marcação suportados nativamente e via CSS/Rangy. */
@Serializable
public enum class HighlightStyle(public val jsColorArg: String, public val legacyTypeValue: String) {
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

    public companion object {
        public fun fromRaw(raw: String): HighlightStyle {
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
 * Representa um item de destaque extraído da página (`summaryHighlights`).
 */
@Serializable
public data class HighlightItem(
    val id: Int,
    val pageIndex: Int,
    val text: String,
    val style: HighlightStyle,
    val scrollPositionY: Double,
    val locator: Locator? = null,
) {
    /** Offset recomendado para `scrollTo(0, targetScrollY)` ao clicar no grifo na lista. */
    public val targetScrollY: Int
        get() = (scrollPositionY.toInt() - 300).coerceAtLeast(0)
}

/** Marcador de página universal (Bookmark) para qualquer livro/capítulo. */
@Serializable
public data class Bookmark(
    val id: String,
    val pageIndex: Int,
    val scrollY: Int = 0,
    val label: String? = null,
    val createdAtEpochMs: Long = 0L,
    val locator: Locator? = null,
)

/** Resultado de busca textual no livro (`ReaderSession.search`). */
@Serializable
public data class SearchResult(
    val pageIndex: Int,
    val chapterTitle: String,
    val snippet: String,
    val matchIndex: Int,
    val progression: Float,
    val locator: Locator? = null,
)

/** Item individual de resposta ou anotação em páginas interativas / apostilas / lições. */
@Serializable
public data class FormEntry(
    val content: String,
    val index: Int,
)

/**
 * Estado completo de anotações e respostas de formulários em uma página HTML interativa:
 * `{"annotations": [{"content": "...", "index": 0}], "answers": [{"content": "...", "index": 0}]}`
 */
@Serializable
public data class PageFormState(
    val annotations: List<FormEntry> = emptyList(),
    val answers: List<FormEntry> = emptyList(),
) {
    public val hasNonBlankContent: Boolean
        get() = annotations.any { it.content.isNotBlank() } || answers.any { it.content.isNotBlank() }
}

/** Estado inicial fornecido pelo App ao abrir um documento. */
public data class InitialBookState(
    val documentId: String = "",
    val initialPageIndex: Int = 0,
    val initialScrollY: Int = 0,
    val isNightMode: Boolean = false,
    val readingMode: ReadingMode = ReadingMode.SCROLL,
    val fontFamily: String = "Default",
    /** Classe CSS opcional aplicada ao `<html>` (ex: `"ESFontSize2"` em apps legados ou vazio `""` em apps genéricos). */
    val fontSizeClass: String = "",
    val fontScale: Float = 1.0f,
    val theme: ThemeTokens? = null,
    /** Mapa `pageIndex -> string serializada Rangy` (aceita texto puro ou comprimido `gz:`). */
    val savedPageHighlights: Map<Int, String> = emptyMap(),
    /** Mapa `pageIndex -> JSON annotations/answers` (aceita texto puro ou comprimido `gz:`). */
    val savedPageForms: Map<Int, String> = emptyMap(),
    /** Lista inicial de marcadores de página (Bookmarks) do usuário. */
    val savedBookmarks: List<Bookmark> = emptyList(),
    /** Textos localizados usados para rótulos padrão de página, capítulo e mensagens. */
    val strings: ReaderStrings = ReaderStrings.portuguese(),
)

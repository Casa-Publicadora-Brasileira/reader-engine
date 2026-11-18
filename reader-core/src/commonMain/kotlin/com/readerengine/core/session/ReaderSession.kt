package com.readerengine.core.session

import com.readerengine.core.bridge.InterceptedUrl
import com.readerengine.core.bridge.PayloadCodec
import com.readerengine.core.bridge.ReaderJsBridge
import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Bookmark
import com.readerengine.core.model.BookStateSnapshot
import com.readerengine.core.model.FormEntry
import com.readerengine.core.model.HighlightItem
import com.readerengine.core.model.HighlightStyle
import com.readerengine.core.model.InitialBookState
import com.readerengine.core.model.Locator
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.PageFormState
import com.readerengine.core.model.PagePreview
import com.readerengine.core.model.PageProgressionDirection
import com.readerengine.core.model.Publication
import com.readerengine.core.model.ReaderStrings
import com.readerengine.core.model.ReadingMode
import com.readerengine.core.model.ReadingProgress
import com.readerengine.core.model.ReadingStats
import com.readerengine.core.model.SearchResult
import com.readerengine.core.model.TocEntry
import com.readerengine.core.model.TocItem
import com.readerengine.core.parser.XmlEntities
import com.readerengine.core.provider.normalizeHref
import com.readerengine.core.search.BookSearchEngine
import com.readerengine.core.theme.ThemeTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Documento HTML pronto para renderização na WebView acompanhado de seu `href` no spine
 * e da `baseUrl` do diretório do capítulo (ex.: `"book://content/OEBPS/"`), garantindo
 * que recursos relativos (`styles/main.css`, `images/cover.png`, `../fonts/f.woff`) sejam
 * resolvidos corretamente pela WebView.
 */
public data class RenderedDocument(
    val html: String,
    val href: String,
    val baseUrl: String,
)

/** Estado reativo completo observado pela UI nativa (Compose / SwiftUI / View / UIKit). */
public data class ReaderState(
    val documentId: String,
    val metadata: Metadata,
    val pageIndex: Int,
    val spineLen: Int,
    val scrollY: Int,
    val currentSubPage: Int = 0,
    val totalSubPages: Int = 1,
    val readingMode: ReadingMode = ReadingMode.SCROLL,
    val progress: ReadingProgress,
    val toc: List<TocItem>,
    val theme: ThemeTokens,
    val fontScale: Float,
    val fontFamily: String,
    val fontSizeClass: String,
    val isNightMode: Boolean,
    val currentPageSerializedHighlights: String,
    val currentPageHighlights: List<HighlightItem>,
    val currentPageFormState: PageFormState,
    val bookmarks: List<Bookmark>,
    val hasBookmarkAtCurrentPage: Boolean,
    val pageList: List<TocItem> = emptyList(),
    val landmarks: List<TocItem> = emptyList(),
    val positions: List<Locator> = emptyList(),
    val stats: ReadingStats = ReadingStats(),
)

/**
 * Eventos de domínio observáveis pelo app host para sincronização com qualquer backend
 * (SQLite/Room, CoreData, REST API, Firestore ou SharedPreferences/UserDefaults).
 */
public sealed interface ReaderEvent {
    public data class ProgressUpdated(
        val progress: ReadingProgress,
    ) : ReaderEvent

    public data class PageHighlightsChanged(
        val documentId: String,
        val pageIndex: Int,
        val rawSerializedHighlights: String,
        /** Payload pronto para persistência (comprimido automaticamente com prefixo `gz:` se >= 200KB). */
        val compressedPayload: String,
        val summaryItems: List<HighlightItem>,
    ) : ReaderEvent

    public data class PageFormChanged(
        val documentId: String,
        val pageIndex: Int,
        val rawJsonPayload: String,
        /** Payload pronto para persistência (comprimido automaticamente com prefixo `gz:` se >= 200KB). */
        val compressedPayload: String,
        val formState: PageFormState,
    ) : ReaderEvent

    public data class BookmarksChanged(
        val documentId: String,
        val bookmarks: List<Bookmark>,
        val serializedJson: String,
    ) : ReaderEvent

    public data class PreferencesChanged(
        val fontFamily: String,
        val fontSizeClass: String,
        val fontScale: Float,
        val isNightMode: Boolean,
        val readingMode: ReadingMode = ReadingMode.SCROLL,
        val theme: ThemeTokens,
    ) : ReaderEvent

    public data class ActionTriggered(
        val action: String,
        val payload: String? = null,
    ) : ReaderEvent
}

/** Efeitos pontuais de UI disparados pela sessão para a tela nativa executar. */
public sealed interface ReaderUiEffect {
    /**
     * Disparado quando o usuário clica em um link com esquema customizado no livro
     * (ex: `verse://?q=...`, `footnote://...`, `audio://...`, `glossary://...`).
     */
    public data class OpenCustomUri(
        val scheme: String,
        val rawUrl: String,
        val title: String,
        val content: String?,
    ) : ReaderUiEffect

    /**
     * Disparado quando um script ou elemento do livro dispara uma ação customizada
     * (ex.: via `action://...` ou `window.ReaderEngine.postAction(...)`).
     */
    public data class CustomAction(
        val action: String,
        val payload: String? = null,
    ) : ReaderUiEffect

    public data class OpenExternalUrl(
        val url: String,
    ) : ReaderUiEffect

    public data class ScrollToY(
        val scrollYPx: Int,
        val anchor: String? = null,
    ) : ReaderUiEffect

    public data class EvaluateJs(
        val script: String,
    ) : ReaderUiEffect

    public data class ShowMessage(
        val message: String,
    ) : ReaderUiEffect
}

/** Comandos despachados pela UI nativa para a sessão. */
public sealed interface ReaderCommand {
    public data object NextPage : ReaderCommand
    public data object PrevPage : ReaderCommand
    public data class GoToPage(val pageIndex: Int, val scrollY: Int = 0) : ReaderCommand
    public data class GoToHref(val href: String) : ReaderCommand
    public data class GoToLocator(val locator: Locator) : ReaderCommand
    public data class TriggerAction(val action: String, val payload: String? = null) : ReaderCommand
    public data class UpdateScroll(
        val scrollY: Int,
        val contentHeightPx: Int = 0,
        val viewportHeightPx: Int = 0,
        val timestampEpochMs: Long = 0L,
    ) : ReaderCommand
    public data class SetTheme(val theme: ThemeTokens) : ReaderCommand
    public data class SetFontScale(val scale: Float) : ReaderCommand
    public data class SetFontFamily(val fontFamily: String) : ReaderCommand
    public data class SetFontSizeClass(val fontSizeClass: String) : ReaderCommand
    public data class SetNightMode(val enabled: Boolean) : ReaderCommand
    public data class SetReadingMode(val mode: ReadingMode) : ReaderCommand
    public data class ApplyHighlightStyle(val style: HighlightStyle) : ReaderCommand
    public data object RemoveSelectedHighlight : ReaderCommand
    public data class SelectHighlightItem(val item: HighlightItem) : ReaderCommand
    public data class RemoveHighlightItem(val highlightId: Int) : ReaderCommand
    public data class UpdateHighlightNote(val highlightId: Int, val note: String?) : ReaderCommand
    public data class ToggleBookmark(val label: String? = null, val timestampEpochMs: Long = 0L) : ReaderCommand
    public data class RemoveBookmark(val bookmarkId: String) : ReaderCommand
    public data class GoToBookmark(val bookmarkId: String) : ReaderCommand
}

public class ReaderSession(
    private val publication: Publication,
    initialBookState: InitialBookState = InitialBookState(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val strings: ReaderStrings = initialBookState.strings
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val documentId: String = initialBookState.documentId.ifBlank {
        publication.metadata.identifier?.takeIf { it.isNotBlank() }
            ?: publication.metadata.title.takeIf { it.isNotBlank() }
            ?: "book-unknown"
    }

    private val resolvedToc: List<TocItem> = flattenTocEntries(publication.toc, level = 0)
    private val resolvedPageList: List<TocItem> = flattenTocEntries(publication.pageList, level = 0)
    private val resolvedLandmarks: List<TocItem> = flattenTocEntries(publication.landmarks, level = 0)
    private val resolvedPositions: List<Locator> = publication.positions()
    private val chapterWordCounts: IntArray = IntArray(publication.spine.size) { idx ->
        val href = publication.spine[idx]
        val cleanText = runCatching {
            publication.cachedPlainText(href) { raw ->
                raw.replace(Regex("""<head\b[^>]*>[\s\S]*?</head>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<[^>]+>"""), " ")
                    .let { XmlEntities.decode(it) }
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }
        }.getOrDefault("")
        if (cleanText.isBlank()) 0 else cleanText.split(Regex("""\s+""")).count { it.isNotBlank() }
    }
    private val totalWordCount: Int = chapterWordCounts.sum()

    private val highlightNotes = LinkedHashMap<Int, String>().apply {
        putAll(initialBookState.savedHighlightNotes)
    }

    private val pageSerializedHighlights = LinkedHashMap<Int, String>().apply {
        initialBookState.savedPageHighlights.forEach { (page, raw) ->
            put(page, PayloadCodec.decodeIfNeeded(raw))
        }
    }

    private val pageHighlightSummaries = LinkedHashMap<Int, List<HighlightItem>>().apply {
        pageSerializedHighlights.forEach { (page, decoded) ->
            val parsed = parseHighlightsFromSerializedString(decoded, page)
            if (parsed.isNotEmpty()) {
                put(page, parsed)
            }
        }
    }

    private val pageFormStates = LinkedHashMap<Int, PageFormState>().apply {
        initialBookState.savedPageForms.forEach { (page, raw) ->
            put(page, ReaderJsBridge.parsePageFormState(raw))
        }
    }

    private val bookmarks = initialBookState.savedBookmarks.toMutableList()
    private var nextBookmarkSeq: Int = bookmarks.size + 1
    private var lastEmittedProgressPage: Int = -1
    private var lastEmittedProgressScrollY: Int = -1000

    private val _events = MutableSharedFlow<ReaderEvent>(replay = 1, extraBufferCapacity = 256)
    public val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()

    private val _uiEffects = MutableSharedFlow<ReaderUiEffect>(extraBufferCapacity = 64)
    public val uiEffects: SharedFlow<ReaderUiEffect> = _uiEffects.asSharedFlow()

    private val _state: MutableStateFlow<ReaderState>
    public val state: StateFlow<ReaderState>

    init {
        val lastIndex = (publication.spine.size - 1).coerceAtLeast(0)
        val startPage = initialBookState.initialPageIndex.coerceIn(0, lastIndex)
        val startScroll = initialBookState.initialScrollY.coerceAtLeast(0)
        val startSubPage = initialBookState.initialSubPage.coerceAtLeast(0)
        val startTheme = initialBookState.theme ?: ThemeTokens.material3()
        val startScale = initialBookState.fontScale.coerceIn(0.5f, 3.0f)

        val initialProgress = buildProgress(
            pageIndex = startPage,
            scrollY = startScroll,
            subPage = startSubPage,
            contentHeightPx = 0,
            viewportHeightPx = 0,
            timestampEpochMs = 0L,
        )

        _state = MutableStateFlow(
            ReaderState(
                documentId = documentId,
                metadata = publication.metadata,
                pageIndex = startPage,
                spineLen = publication.spine.size,
                scrollY = startScroll,
                currentSubPage = startSubPage,
                totalSubPages = 1,
                readingMode = initialBookState.readingMode,
                progress = initialProgress,
                toc = resolvedToc,
                theme = startTheme,
                fontScale = startScale,
                fontFamily = initialBookState.fontFamily,
                fontSizeClass = initialBookState.fontSizeClass,
                isNightMode = initialBookState.isNightMode,
                currentPageSerializedHighlights = pageSerializedHighlights[startPage].orEmpty(),
                currentPageHighlights = pageHighlightSummaries[startPage].orEmpty(),
                currentPageFormState = pageFormStates[startPage] ?: PageFormState(),
                bookmarks = bookmarks.toList(),
                hasBookmarkAtCurrentPage = bookmarks.any { it.pageIndex == startPage },
                pageList = resolvedPageList,
                landmarks = resolvedLandmarks,
                positions = resolvedPositions,
                stats = buildStats(startPage, initialProgress.pageProgression),
            ),
        )
        state = _state.asStateFlow()
    }

    public fun publication(): Publication = publication

    /**
     * Observador ergonômico para iOS/Swift (`ReaderCore.xcframework`): emite o `ReaderState` atual
     * imediatamente e a cada atualização subsequente até que `FlowSubscription.cancel()` seja chamado.
     */
    public fun watchState(onEach: (ReaderState) -> Unit): FlowSubscription {
        val job = watchScope.launch {
            state.collect { onEach(it) }
        }
        return FlowSubscription { job.cancel() }
    }

    /**
     * Observador ergonômico para iOS/Swift: recebe todos os `ReaderEvent` de persistência
     * até que `FlowSubscription.cancel()` seja chamado.
     */
    public fun watchEvents(onEach: (ReaderEvent) -> Unit): FlowSubscription {
        val job = watchScope.launch {
            events.collect { onEach(it) }
        }
        return FlowSubscription { job.cancel() }
    }

    /**
     * Observador ergonômico para iOS/Swift: recebe os efeitos pontuais de UI (`ReaderUiEffect`)
     * até que `FlowSubscription.cancel()` seja chamado.
     */
    public fun watchUiEffects(onEach: (ReaderUiEffect) -> Unit): FlowSubscription {
        val job = watchScope.launch {
            uiEffects.collect { onEach(it) }
        }
        return FlowSubscription { job.cancel() }
    }

    /** Encerra todos os observadores ativos e libera descritores de arquivo abertos pela publicação. */
    public fun close() {
        watchScope.cancel()
        publication.close()
    }

    /** Sobrecarga concisa para iOS/Swift sem argumentos default. */
    public fun search(query: String): List<SearchResult> = search(query, maxResults = 50)

    /**
     * Realiza busca textual case-insensitive e insensível a acentos em todos os capítulos do livro,
     * retornando trechos com contexto (`snippet`), índice da página, posição relativa e `Locator`.
     */
    public fun search(query: String, maxResults: Int = 50): List<SearchResult> =
        BookSearchEngine.search(
            publication = publication,
            resolvedToc = resolvedToc,
            query = query,
            maxResults = maxResults,
            strings = strings,
        )

    /** Exporta a lista de marcadores (Bookmarks) como JSON. */
    public fun exportBookmarksJson(): String =
        json.encodeToString(ListSerializer(Bookmark.serializer()), bookmarks)

    /** Importa uma lista de marcadores (Bookmarks) a partir de um JSON salvo pelo app host. */
    public fun importBookmarksJson(rawJson: String): ReaderState {
        val parsed = runCatching {
            json.decodeFromString(ListSerializer(Bookmark.serializer()), rawJson)
        }.getOrDefault(emptyList())
        bookmarks.clear()
        bookmarks.addAll(parsed)
        nextBookmarkSeq = bookmarks.size + 1
        val current = _state.value
        _state.value = current.copy(
            bookmarks = bookmarks.toList(),
            hasBookmarkAtCurrentPage = bookmarks.any { it.pageIndex == current.pageIndex },
        )
        return _state.value
    }

    /** Despacha um comando da UI e retorna o novo `ReaderState`. */
    public fun dispatch(command: ReaderCommand): ReaderState {
        val current = _state.value
        val lastIndex = (current.spineLen - 1).coerceAtLeast(0)

        when (command) {
            ReaderCommand.NextPage -> {
                if (current.readingMode == ReadingMode.PAGINATED &&
                    current.currentSubPage + 1 < current.totalSubPages
                ) {
                    val nextSub = current.currentSubPage + 1
                    val newProgress = current.progress.copy(subPage = nextSub)
                    _state.value = current.copy(
                        currentSubPage = nextSub,
                        progress = newProgress,
                    )
                    _events.tryEmit(ReaderEvent.ProgressUpdated(newProgress))
                    _uiEffects.tryEmit(ReaderUiEffect.EvaluateJs(ReaderJsBridge.SCRIPT_NEXT_SUB_PAGE))
                } else {
                    val nextIdx = (current.pageIndex + 1).coerceAtMost(lastIndex)
                    if (nextIdx != current.pageIndex || current.scrollY != 0) {
                        navigateToPageInternal(nextIdx, scrollY = 0)
                    }
                }
            }

            ReaderCommand.PrevPage -> {
                if (current.readingMode == ReadingMode.PAGINATED && current.currentSubPage > 0) {
                    val prevSub = current.currentSubPage - 1
                    val newProgress = current.progress.copy(subPage = prevSub)
                    _state.value = current.copy(
                        currentSubPage = prevSub,
                        progress = newProgress,
                    )
                    _events.tryEmit(ReaderEvent.ProgressUpdated(newProgress))
                    _uiEffects.tryEmit(ReaderUiEffect.EvaluateJs(ReaderJsBridge.SCRIPT_PREV_SUB_PAGE))
                } else {
                    val prevIdx = (current.pageIndex - 1).coerceAtLeast(0)
                    if (prevIdx != current.pageIndex || current.scrollY != 0) {
                        navigateToPageInternal(prevIdx, scrollY = 0)
                    }
                }
            }

            is ReaderCommand.GoToPage -> {
                val targetIdx = command.pageIndex.coerceIn(0, lastIndex)
                val targetScroll = command.scrollY.coerceAtLeast(0)
                navigateToPageInternal(targetIdx, scrollY = targetScroll)
            }

            is ReaderCommand.GoToHref -> {
                handleUrlNavigation(command.href)
            }

            is ReaderCommand.GoToLocator -> {
                val loc = command.locator
                val idxByHref = resolveSpineIndex(loc.href, baseDir = "")
                val targetIdx = if (idxByHref >= 0) idxByHref else loc.pageIndex.coerceIn(0, lastIndex)
                navigateToPageInternal(targetIdx, scrollY = 0)
            }

            is ReaderCommand.TriggerAction -> {
                handleActionInternal(command.action, command.payload)
            }

            is ReaderCommand.UpdateScroll -> {
                val clampedScroll = command.scrollY.coerceAtLeast(0)
                val newProgress = buildProgress(
                    pageIndex = current.pageIndex,
                    scrollY = clampedScroll,
                    subPage = current.currentSubPage,
                    contentHeightPx = command.contentHeightPx,
                    viewportHeightPx = command.viewportHeightPx,
                    timestampEpochMs = command.timestampEpochMs,
                )
                _state.value = current.copy(
                    scrollY = clampedScroll,
                    progress = newProgress,
                    stats = buildStats(current.pageIndex, newProgress.pageProgression),
                )
                if (current.pageIndex != lastEmittedProgressPage ||
                    abs(clampedScroll - lastEmittedProgressScrollY) >= 2
                ) {
                    lastEmittedProgressPage = current.pageIndex
                    lastEmittedProgressScrollY = clampedScroll
                    _events.tryEmit(ReaderEvent.ProgressUpdated(newProgress))
                }
            }

            is ReaderCommand.SetTheme -> {
                val updated = current.copy(theme = command.theme)
                _state.value = updated
                emitPreferencesChanged(updated)
            }

            is ReaderCommand.SetFontScale -> {
                val clamped = command.scale.coerceIn(0.5f, 3.0f)
                val updated = current.copy(fontScale = clamped)
                _state.value = updated
                emitPreferencesChanged(updated)
            }

            is ReaderCommand.SetFontFamily -> {
                val updated = current.copy(fontFamily = command.fontFamily)
                _state.value = updated
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.setFontFamilyCommand(command.fontFamily)),
                )
                emitPreferencesChanged(updated)
            }

            is ReaderCommand.SetFontSizeClass -> {
                val updated = current.copy(fontSizeClass = command.fontSizeClass)
                _state.value = updated
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.setFontSizeClassCommand(command.fontSizeClass)),
                )
                emitPreferencesChanged(updated)
            }

            is ReaderCommand.SetNightMode -> {
                val updated = current.copy(isNightMode = command.enabled)
                _state.value = updated
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.setNightModeCommand(command.enabled)),
                )
                emitPreferencesChanged(updated)
            }

            is ReaderCommand.SetReadingMode -> {
                val updated = current.copy(
                    readingMode = command.mode,
                    currentSubPage = 0,
                )
                _state.value = updated
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.setReadingModeCommand(command.mode)),
                )
                emitPreferencesChanged(updated)
            }

            is ReaderCommand.ApplyHighlightStyle -> {
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.highlightSelectionCommand(command.style)),
                )
            }

            ReaderCommand.RemoveSelectedHighlight -> {
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.SCRIPT_UNHIGHLIGHT_SELECTION),
                )
            }

            is ReaderCommand.SelectHighlightItem -> {
                val item = command.item
                if (item.pageIndex != current.pageIndex) {
                    navigateToPageInternal(item.pageIndex, scrollY = item.targetScrollY)
                } else {
                    _uiEffects.tryEmit(ReaderUiEffect.ScrollToY(scrollYPx = item.targetScrollY))
                }
            }

            is ReaderCommand.RemoveHighlightItem -> {
                highlightNotes.remove(command.highlightId)
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.removeHighlightByIdCommand(command.highlightId)),
                )
            }

            is ReaderCommand.UpdateHighlightNote -> {
                val cleanNote = command.note?.trim()?.takeIf { it.isNotBlank() }
                if (cleanNote == null) {
                    highlightNotes.remove(command.highlightId)
                } else {
                    highlightNotes[command.highlightId] = cleanNote
                }
                val curPage = current.pageIndex
                val currentItems = pageHighlightSummaries[curPage].orEmpty().map { item ->
                    if (item.id == command.highlightId) item.copy(note = cleanNote) else item
                }
                pageHighlightSummaries[curPage] = currentItems
                _state.value = current.copy(currentPageHighlights = currentItems)
                val rawSerialized = pageSerializedHighlights[curPage].orEmpty()
                val compressedPayload = PayloadCodec.encodeIfNeeded(rawSerialized)
                _events.tryEmit(
                    ReaderEvent.PageHighlightsChanged(
                        documentId = documentId,
                        pageIndex = curPage,
                        rawSerializedHighlights = rawSerialized,
                        compressedPayload = compressedPayload,
                        summaryItems = currentItems,
                    ),
                )
            }

            is ReaderCommand.ToggleBookmark -> {
                val existingIdx = bookmarks.indexOfFirst { it.pageIndex == current.pageIndex }
                if (existingIdx >= 0) {
                    bookmarks.removeAt(existingIdx)
                } else {
                    val defaultLabel = command.label
                        ?: resolvedToc.firstOrNull { it.spineIndex == current.pageIndex }?.title
                        ?: strings.formatPageLabel(current.pageIndex + 1)
                    val href = publication.spine.getOrNull(current.pageIndex).orEmpty()
                    bookmarks.add(
                        Bookmark(
                            id = "bm-${current.pageIndex}-${nextBookmarkSeq++}",
                            pageIndex = current.pageIndex,
                            scrollY = current.scrollY,
                            label = defaultLabel,
                            createdAtEpochMs = command.timestampEpochMs,
                            locator = Locator(
                                href = href,
                                pageIndex = current.pageIndex,
                                progression = current.progress.pageProgression,
                                title = defaultLabel,
                            ),
                        ),
                    )
                }
                syncBookmarksStateAndEmit()
            }

            is ReaderCommand.RemoveBookmark -> {
                if (bookmarks.removeAll { it.id == command.bookmarkId }) {
                    syncBookmarksStateAndEmit()
                }
            }

            is ReaderCommand.GoToBookmark -> {
                val target = bookmarks.firstOrNull { it.id == command.bookmarkId }
                if (target != null) {
                    navigateToPageInternal(target.pageIndex, scrollY = target.scrollY)
                }
            }
        }

        return _state.value
    }

    /**
     * Atualiza a contagem de sub-páginas (colunas horizontais) quando em `ReadingMode.PAGINATED`.
     */
    public fun onSubPageReported(currentSubPage: Int, totalSubPages: Int) {
        val safeTotal = totalSubPages.coerceAtLeast(1)
        val safeCurrent = currentSubPage.coerceIn(0, safeTotal - 1)
        val cur = _state.value
        val progressUpdated = if (cur.progress.subPage != safeCurrent) {
            cur.progress.copy(subPage = safeCurrent)
        } else {
            cur.progress
        }
        _state.value = cur.copy(
            currentSubPage = safeCurrent,
            totalSubPages = safeTotal,
            progress = progressUpdated,
        )
        if (progressUpdated !== cur.progress) {
            _events.tryEmit(ReaderEvent.ProgressUpdated(progressUpdated))
        }
    }

    /**
     * Chamado pela plataforma quando o WebView retorna os dados de `readerJS.getHighlights()`
     * e opcionalmente `readerJS.summaryHighlights()`.
     * Atualiza o estado da sessão e emite `ReaderEvent.PageHighlightsChanged`.
     */
    public fun onPageHighlightsReported(
        rawSerializedHighlights: String,
        rawSummaryJson: String? = null,
        pageIndex: Int = _state.value.pageIndex,
    ) {
        val cleanSerialized = PayloadCodec.decodeIfNeeded(
            ReaderJsBridge.unquoteJsEvaluation(rawSerializedHighlights),
        )
        val href = publication.spine.getOrNull(pageIndex).orEmpty()
        val summaryList = when {
            rawSummaryJson != null -> ReaderJsBridge.parseSummaryHighlights(rawSummaryJson, pageIndex).map { item ->
                item.copy(
                    locator = Locator(
                        href = href,
                        pageIndex = pageIndex,
                        textQuote = item.text,
                    ),
                    note = highlightNotes[item.id],
                )
            }
            pageHighlightSummaries[pageIndex] != null -> pageHighlightSummaries[pageIndex].orEmpty()
            else -> parseHighlightsFromSerializedString(cleanSerialized, pageIndex)
        }

        pageSerializedHighlights[pageIndex] = cleanSerialized
        pageHighlightSummaries[pageIndex] = summaryList

        if (pageIndex == _state.value.pageIndex) {
            _state.value = _state.value.copy(
                currentPageSerializedHighlights = cleanSerialized,
                currentPageHighlights = summaryList,
            )
        }

        val compressedPayload = PayloadCodec.encodeIfNeeded(cleanSerialized)
        _events.tryEmit(
            ReaderEvent.PageHighlightsChanged(
                documentId = documentId,
                pageIndex = pageIndex,
                rawSerializedHighlights = cleanSerialized,
                compressedPayload = compressedPayload,
                summaryItems = summaryList,
            ),
        )
    }

    /**
     * Atualiza a lista de `HighlightItem` da página a partir do retorno de `readerJS.summaryHighlights()`.
     */
    public fun onSummaryHighlightsLoaded(
        rawSummaryJson: String,
        pageIndex: Int = _state.value.pageIndex,
    ): List<HighlightItem> {
        val items = ReaderJsBridge.parseSummaryHighlights(rawSummaryJson, pageIndex).map { item ->
            item.copy(note = highlightNotes[item.id])
        }
        pageHighlightSummaries[pageIndex] = items
        if (pageIndex == _state.value.pageIndex) {
            _state.value = _state.value.copy(currentPageHighlights = items)
        }
        return items
    }

    /**
     * Chamado pela plataforma quando o WebView retorna o JSON de formulários/anotações da página
     * (`{"annotations": [...], "answers": [...]}`).
     * Atualiza o estado e emite `ReaderEvent.PageFormChanged`.
     */
    public fun onPageFormReported(
        rawModificationsJson: String,
        pageIndex: Int = _state.value.pageIndex,
    ) {
        val cleanJson = PayloadCodec.decodeIfNeeded(
            ReaderJsBridge.unquoteJsEvaluation(rawModificationsJson),
        )
        val formState = ReaderJsBridge.parsePageFormState(cleanJson)
        pageFormStates[pageIndex] = formState

        if (pageIndex == _state.value.pageIndex) {
            _state.value = _state.value.copy(currentPageFormState = formState)
        }

        val canonicalJson = if (cleanJson.startsWith("{")) cleanJson else ReaderJsBridge.serializePageFormState(formState)
        val compressedPayload = PayloadCodec.encodeIfNeeded(canonicalJson)

        _events.tryEmit(
            ReaderEvent.PageFormChanged(
                documentId = documentId,
                pageIndex = pageIndex,
                rawJsonPayload = canonicalJson,
                compressedPayload = compressedPayload,
                formState = formState,
            ),
        )
    }

    /**
     * Injeta dados vindos assincronamente do backend para uma página específica,
     * sincronizando estado de resumo e disparando `EvaluateJs` caso seja a página ativa.
     */
    public fun restorePageCloudData(
        pageIndex: Int,
        serializedHighlights: String? = null,
        annotationsJson: String? = null,
    ) {
        if (serializedHighlights != null) {
            val decoded = PayloadCodec.decodeIfNeeded(serializedHighlights)
            val parsedSummary = parseHighlightsFromSerializedString(decoded, pageIndex)
            pageSerializedHighlights[pageIndex] = decoded
            if (parsedSummary.isNotEmpty()) {
                pageHighlightSummaries[pageIndex] = parsedSummary
            }
            if (pageIndex == _state.value.pageIndex) {
                _state.value = _state.value.copy(
                    currentPageSerializedHighlights = decoded,
                    currentPageHighlights = pageHighlightSummaries[pageIndex].orEmpty(),
                )
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.setHighlightsCommand(decoded)),
                )
            }
        }
        if (annotationsJson != null) {
            val formState = ReaderJsBridge.parsePageFormState(annotationsJson)
            pageFormStates[pageIndex] = formState
            if (pageIndex == _state.value.pageIndex) {
                _state.value = _state.value.copy(currentPageFormState = formState)
                if (formState.annotations.isNotEmpty()) {
                    val annotationsRaw = json.encodeToString(
                        ListSerializer(FormEntry.serializer()),
                        formState.annotations,
                    )
                    _uiEffects.tryEmit(
                        ReaderUiEffect.EvaluateJs(ReaderJsBridge.setAnnotationsCommand(annotationsRaw)),
                    )
                }
                if (formState.answers.isNotEmpty()) {
                    val answersRaw = json.encodeToString(
                        ListSerializer(FormEntry.serializer()),
                        formState.answers,
                    )
                    _uiEffects.tryEmit(
                        ReaderUiEffect.EvaluateJs(ReaderJsBridge.setAnswersCommand(answersRaw)),
                    )
                }
            }
        }
    }

    /**
     * Intercepta qualquer URL clicada na WebView (esquemas customizados como `verse://`, `footnote://`,
     * links externos `http://` ou links internos de capítulos com `#anchor`)
     * e emite o `ReaderUiEffect` ou navegação correspondente.
     * Retorna `true` se o clique foi tratado pela engine.
     */
    public fun handleUrlNavigation(url: String): Boolean {
        return when (val intercepted = ReaderJsBridge.parseSpecialUrl(url)) {
            is InterceptedUrl.CustomUri -> {
                _uiEffects.tryEmit(
                    ReaderUiEffect.OpenCustomUri(
                        scheme = intercepted.scheme,
                        rawUrl = intercepted.rawUrl,
                        title = intercepted.title,
                        content = intercepted.content,
                    ),
                )
                true
            }

            is InterceptedUrl.Action -> {
                handleActionInternal(intercepted.action, intercepted.payload)
                true
            }

            is InterceptedUrl.ExternalWebUrl -> {
                _uiEffects.tryEmit(ReaderUiEffect.OpenExternalUrl(intercepted.url))
                true
            }

            InterceptedUrl.InvalidHighlightOnInput -> {
                _uiEffects.tryEmit(
                    ReaderUiEffect.ShowMessage(strings.invalidHighlightOnInputMessage),
                )
                true
            }

            InterceptedUrl.ClipboardAction -> true

            is InterceptedUrl.InternalResource -> {
                if (!intercepted.anchor.isNullOrBlank()) {
                    val targetHref = if (intercepted.href.isBlank()) {
                        publication.spine.getOrNull(_state.value.pageIndex).orEmpty()
                    } else {
                        val idx = resolveSpineIndex(intercepted.href, baseDir = currentChapterDir())
                        if (idx >= 0) publication.spine[idx] else resolveResourceHref(intercepted.href)
                    }
                    val footnoteText = extractFootnoteById(targetHref, intercepted.anchor)
                    if (footnoteText != null) {
                        _uiEffects.tryEmit(
                            ReaderUiEffect.OpenCustomUri(
                                scheme = "footnote",
                                rawUrl = url,
                                title = intercepted.anchor,
                                content = footnoteText,
                            ),
                        )
                        return true
                    }
                }
                if (intercepted.href.isBlank() && intercepted.anchor != null) {
                    _uiEffects.tryEmit(ReaderUiEffect.ScrollToY(scrollYPx = 0, anchor = intercepted.anchor))
                    return true
                }
                val spineIdx = resolveSpineIndex(intercepted.href, baseDir = currentChapterDir())
                if (spineIdx >= 0) {
                    navigateToPageInternal(spineIdx, scrollY = 0)
                    if (intercepted.anchor != null) {
                        _uiEffects.tryEmit(ReaderUiEffect.ScrollToY(scrollYPx = 0, anchor = intercepted.anchor))
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    /** Sobrecarga concisa para iOS/Swift sem argumentos default. */
    public fun currentDocument(): RenderedDocument = currentDocument(schemeBase = DEFAULT_BOOK_BASE_URL)

    /**
     * Retorna o documento HTML da página atual juntamente com seu `href` e `baseUrl` do capítulo
     * (ex.: `book://content/OEBPS/`), garantindo resolução correta de imagens, fontes e CSS relativos.
     */
    public fun currentDocument(schemeBase: String): RenderedDocument {
        val current = _state.value
        val href = publication.spine.getOrNull(current.pageIndex)
            ?: throw ReaderException.InvalidContent("Spine vazio ou índice inválido (${current.pageIndex})")
        val rawHtml = publication.readString(href)
        val html = ReaderJsBridge.injectThemeAndBridge(
            rawHtml = rawHtml,
            theme = current.theme,
            fontScale = current.fontScale,
            fontFamily = current.fontFamily,
            fontSizeClass = current.fontSizeClass,
            isNightMode = current.isNightMode,
            readingMode = current.readingMode,
            layout = publication.metadata.layout,
            direction = publication.metadata.pageProgressionDirection,
            spread = publication.metadata.spread,
            initialSubPage = if (current.readingMode == ReadingMode.PAGINATED) current.currentSubPage else 0,
        )
        return RenderedDocument(
            html = html,
            href = href,
            baseUrl = currentBaseUrl(schemeBase),
        )
    }

    /**
     * Retorna os bytes da capa do livro (`coverHref`) se detectada no manifesto/OPF ou book.json,
     * permitindo que telas de biblioteca ou estante renderizem a miniatura diretamente.
     */
    public fun readCoverBytes(): ByteArray? =
        publication.metadata.coverHref?.let { runCatching { readResource(it) }.getOrNull() }

    /**
     * Retorna o preview rápido (`title` e `excerpt` de texto limpo) de uma página da `spine`
     * utilizando o cache interno `publication.cachedPlainText(href)`, ou `null` caso `pageIndex`
     * esteja fora dos limites `0 until spine.size`.
     */
    public fun getPagePreview(pageIndex: Int): PagePreview? =
        getPagePreview(pageIndex = pageIndex, maxExcerptChars = 180)

    public fun getPagePreview(pageIndex: Int, maxExcerptChars: Int): PagePreview? {
        if (pageIndex < 0 || pageIndex >= publication.spine.size) return null
        val href = publication.spine[pageIndex]
        val title = resolvedToc.firstOrNull { it.spineIndex == pageIndex }?.title
            ?.takeIf { it.isNotBlank() }
            ?: strings.formatPageLabel(pageIndex + 1)
        val plainText = runCatching {
            publication.cachedPlainText(href) { raw ->
                raw.replace(Regex("""<head\b[^>]*>[\s\S]*?</head>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<style\b[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("""<[^>]+>"""), " ")
                    .let { XmlEntities.decode(it) }
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }
        }.getOrDefault("")
        val cleanBody = if (title.isNotBlank() &&
            plainText.startsWith(title, ignoreCase = true) &&
            plainText.length > title.length
        ) {
            plainText.substring(title.length).trim()
        } else {
            plainText
        }
        val limit = maxExcerptChars.coerceAtLeast(16)
        val excerpt = if (cleanBody.length <= limit) {
            cleanBody
        } else {
            cleanBody.substring(0, limit).trimEnd() + "…"
        }
        return PagePreview(
            pageIndex = pageIndex,
            href = href,
            title = title,
            excerpt = excerpt,
        )
    }

    /** Alias conciso para [getPagePreview]. */
    public fun pagePreview(pageIndex: Int): PagePreview? =
        getPagePreview(pageIndex = pageIndex, maxExcerptChars = 180)

    public fun pagePreview(pageIndex: Int, maxExcerptChars: Int): PagePreview? =
        getPagePreview(pageIndex = pageIndex, maxExcerptChars = maxExcerptChars)

    /** Sobrecarga concisa para iOS/Swift sem argumentos default. */
    public fun currentBaseUrl(): String = currentBaseUrl(schemeBase = DEFAULT_BOOK_BASE_URL)

    /** Retorna a URL base (com barra final) correspondente ao diretório do capítulo atual. */
    public fun currentBaseUrl(schemeBase: String): String {
        val cleanBase = if (schemeBase.endsWith('/')) schemeBase else "$schemeBase/"
        val dir = currentChapterDir()
        return if (dir.isEmpty()) cleanBase else "$cleanBase$dir"
    }

    /** Retorna o HTML da página atual com CSS de tema, modo noturno e bridge JS injetados. */
    public fun currentHtml(): String = currentDocument().html

    /**
     * Lê um recurso interno do livro (imagem, CSS, fonte) pelo `href` relativo ou absoluto.
     * Caso o caminho não seja encontrado diretamente na raiz do pacote, tenta resolvê-lo
     * em relação ao diretório do capítulo atual (ex.: `styles/main.css` -> `OEBPS/styles/main.css`).
     */
    public fun readResource(href: String): ByteArray {
        val resolved = resolveResourceHref(href)
        return publication.read(resolved)
    }

    /**
     * Retorna o tamanho em bytes do recurso no livro (se disponível),
     * permitindo que servidores e WebViews respondam cabeçalhos `Content-Length` e `Accept-Ranges`.
     */
    public fun resourceSize(href: String): Long? {
        val resolved = resolveResourceHref(href)
        return publication.size(resolved)
    }

    /**
     * Lê uma fatia (range) de bytes de um recurso interno do livro sem carregar o arquivo inteiro na memória RAM,
     * essencial para streaming de vídeo/áudio HTML5 (`Range: bytes=start-end` / HTTP 206 Partial Content).
     */
    public fun readResourceRange(href: String, offset: Long, byteCount: Int): ByteArray {
        val resolved = resolveResourceHref(href)
        return publication.readRange(resolved, offset, byteCount)
    }

    /** Retorna o MIME type declarado no manifesto da publicação para o `href`, se existir. */
    public fun resourceMediaType(href: String): String? {
        val direct = publication.findResource(href)?.mediaType
        if (direct != null) return direct
        val chapterDir = currentChapterDir()
        if (chapterDir.isNotEmpty()) {
            val relativeCandidate = normalizeHref("$chapterDir$href")
            return publication.findResource(relativeCandidate)?.mediaType
        }
        return null
    }

    private fun flattenTocEntries(entries: List<TocEntry>, level: Int): List<TocItem> {
        val out = mutableListOf<TocItem>()
        for (entry in entries) {
            val index = resolveSpineIndex(entry.href, baseDir = "")
            if (index >= 0) {
                out.add(
                    TocItem(
                        title = entry.title,
                        subtitle = entry.subtitle,
                        href = entry.href,
                        spineIndex = index,
                        level = level,
                    ),
                )
            }
            if (entry.children.isNotEmpty()) {
                out.addAll(flattenTocEntries(entry.children, level + 1))
            }
        }
        return out
    }

    private fun parseHighlightsFromSerializedString(
        serialized: String,
        pageIndex: Int,
    ): List<HighlightItem> {
        if (serialized.isBlank()) return emptyList()
        val href = publication.spine.getOrNull(pageIndex).orEmpty()
        val parts = serialized.split('|')
        val out = mutableListOf<HighlightItem>()
        for (part in parts) {
            if (part.isBlank() || part.startsWith("type:")) continue
            val tokens = part.split('$')
            if (tokens.size >= 3) {
                val id = tokens[0].toIntOrNull() ?: (out.size + 1)
                val style = HighlightStyle.fromRaw(tokens[1])
                val text = tokens.subList(2, tokens.size).joinToString("$").trim()
                if (text.isNotEmpty()) {
                    out.add(
                        HighlightItem(
                            id = id,
                            pageIndex = pageIndex,
                            text = text,
                            style = style,
                            scrollPositionY = 0.0,
                            locator = Locator(
                                href = href,
                                pageIndex = pageIndex,
                                textQuote = text,
                            ),
                            note = highlightNotes[id],
                        ),
                    )
                }
            }
        }
        return out
    }

    private fun currentChapterDir(): String {
        val currentHref = publication.spine.getOrNull(_state.value.pageIndex).orEmpty()
        val normalized = normalizeHref(currentHref)
        val dir = normalized.substringBeforeLast('/', missingDelimiterValue = "")
        return if (dir.isEmpty()) "" else "$dir/"
    }

    private fun resolveResourceHref(href: String): String {
        val normalized = normalizeHref(href)
        if (runCatching { publication.read(normalized) }.isSuccess) {
            return normalized
        }
        val chapterDir = currentChapterDir()
        if (chapterDir.isNotEmpty()) {
            val relativeToChapter = normalizeHref("$chapterDir$normalized")
            if (runCatching { publication.read(relativeToChapter) }.isSuccess) {
                return relativeToChapter
            }
        }
        val manifestMatch = publication.findResource(normalized)?.href
        if (manifestMatch != null && runCatching { publication.read(manifestMatch) }.isSuccess) {
            return manifestMatch
        }
        return normalized
    }

    private fun resolveSpineIndex(rawHref: String, baseDir: String): Int {
        val targetDirect = normalizeHref(rawHref)
        val targetWithBase = if (baseDir.isNotEmpty()) normalizeHref("$baseDir$rawHref") else targetDirect

        val exactWithBase = publication.spine.indexOfFirst { normalizeHref(it) == targetWithBase }
        if (exactWithBase >= 0) return exactWithBase

        val exactDirect = publication.spine.indexOfFirst { normalizeHref(it) == targetDirect }
        if (exactDirect >= 0) return exactDirect

        return publication.spine.indexOfFirst { spineItem ->
            val cleanSpine = normalizeHref(spineItem)
            cleanSpine.endsWith("/$targetDirect") || targetDirect.endsWith("/$cleanSpine")
        }
    }

    /**
     * Exporta um snapshot completo e auto-contido do estado atual do leitor em formato JSON,
     * incluindo posição/progresso, sub-página, preferências, grifos, notas, formulários e bookmarks.
     */
    public fun exportBookStateSnapshot(): String {
        val cur = _state.value
        val snapshot = BookStateSnapshot(
            documentId = documentId,
            progress = cur.progress,
            readingMode = cur.readingMode,
            isNightMode = cur.isNightMode,
            fontFamily = cur.fontFamily,
            fontSizeClass = cur.fontSizeClass,
            fontScale = cur.fontScale,
            pageHighlights = pageSerializedHighlights.toMap(),
            highlightNotes = highlightNotes.toMap(),
            pageForms = pageFormStates.mapValues { (_, form) ->
                ReaderJsBridge.serializePageFormState(form)
            },
            bookmarks = bookmarks.toList(),
            updatedAtEpochMs = cur.progress.lastUpdateEpochMs,
        )
        return json.encodeToString(BookStateSnapshot.serializer(), snapshot)
    }

    /**
     * Importa um snapshot de estado previamente exportado (ou vindo da nuvem/backup),
     * restaurando progressivamente o estado da sessão e emitindo os eventos correspondentes.
     */
    public fun importBookStateSnapshot(payload: String): ReaderState {
        val decodedPayload = PayloadCodec.decodeIfNeeded(payload)
        val snapshot = json.decodeFromString(BookStateSnapshot.serializer(), decodedPayload)

        val nextReadingMode = snapshot.readingMode
        val nextFontFamily = snapshot.fontFamily
        val nextFontSizeClass = snapshot.fontSizeClass
        val nextFontScale = snapshot.fontScale
        val nextNightMode = snapshot.isNightMode

        pageSerializedHighlights.clear()
        snapshot.pageHighlights.forEach { (page, raw) ->
            pageSerializedHighlights[page] = PayloadCodec.decodeIfNeeded(raw)
        }
        highlightNotes.clear()
        highlightNotes.putAll(snapshot.highlightNotes)
        pageHighlightSummaries.clear()
        pageSerializedHighlights.forEach { (page, decoded) ->
            val parsed = parseHighlightsFromSerializedString(decoded, page)
            if (parsed.isNotEmpty()) {
                pageHighlightSummaries[page] = parsed
            }
        }

        pageFormStates.clear()
        snapshot.pageForms.forEach { (page, raw) ->
            pageFormStates[page] = ReaderJsBridge.parsePageFormState(raw)
        }

        bookmarks.clear()
        bookmarks.addAll(snapshot.bookmarks)
        nextBookmarkSeq = bookmarks.size + 1

        val lastIndex = (publication.spine.size - 1).coerceAtLeast(0)
        val targetPage = (snapshot.progress?.pageIndex ?: _state.value.pageIndex).coerceIn(0, lastIndex)
        val targetScroll = (snapshot.progress?.scrollY ?: 0).coerceAtLeast(0)
        val targetSubPage = (snapshot.progress?.subPage ?: 0).coerceAtLeast(0)

        val updatedProgress = snapshot.progress ?: buildProgress(
            pageIndex = targetPage,
            scrollY = targetScroll,
            subPage = targetSubPage,
            contentHeightPx = 0,
            viewportHeightPx = 0,
            timestampEpochMs = snapshot.updatedAtEpochMs,
        )

        _state.value = _state.value.copy(
            pageIndex = targetPage,
            scrollY = targetScroll,
            currentSubPage = targetSubPage,
            readingMode = nextReadingMode,
            fontFamily = nextFontFamily,
            fontSizeClass = nextFontSizeClass,
            fontScale = nextFontScale,
            isNightMode = nextNightMode,
            progress = updatedProgress,
            currentPageSerializedHighlights = pageSerializedHighlights[targetPage].orEmpty(),
            currentPageHighlights = pageHighlightSummaries[targetPage].orEmpty(),
            currentPageFormState = pageFormStates[targetPage] ?: PageFormState(),
            bookmarks = bookmarks.toList(),
            hasBookmarkAtCurrentPage = bookmarks.any { it.pageIndex == targetPage },
            stats = buildStats(targetPage, updatedProgress.pageProgression),
        )

        _events.tryEmit(ReaderEvent.ProgressUpdated(updatedProgress))
        syncBookmarksStateAndEmit()
        emitPreferencesChanged(_state.value)

        return _state.value
    }

    private fun navigateToPageInternal(targetPageIndex: Int, scrollY: Int, targetSubPage: Int = 0) {
        val current = _state.value
        val newProgress = buildProgress(
            pageIndex = targetPageIndex,
            scrollY = scrollY,
            subPage = targetSubPage,
            contentHeightPx = 0,
            viewportHeightPx = 0,
            timestampEpochMs = 0L,
        )
        _state.value = current.copy(
            pageIndex = targetPageIndex,
            scrollY = scrollY,
            currentSubPage = targetSubPage,
            totalSubPages = 1,
            progress = newProgress,
            currentPageSerializedHighlights = pageSerializedHighlights[targetPageIndex].orEmpty(),
            currentPageHighlights = pageHighlightSummaries[targetPageIndex].orEmpty(),
            currentPageFormState = pageFormStates[targetPageIndex] ?: PageFormState(),
            hasBookmarkAtCurrentPage = bookmarks.any { it.pageIndex == targetPageIndex },
            stats = buildStats(targetPageIndex, newProgress.pageProgression),
        )
        lastEmittedProgressPage = targetPageIndex
        lastEmittedProgressScrollY = scrollY
        _events.tryEmit(ReaderEvent.ProgressUpdated(newProgress))
        if (scrollY > 0) {
            _uiEffects.tryEmit(ReaderUiEffect.ScrollToY(scrollYPx = scrollY))
        }
    }

    private fun handleActionInternal(action: String, payload: String?) {
        val current = _state.value
        val lastIndex = (current.spineLen - 1).coerceAtLeast(0)
        when (action) {
            ReaderJsBridge.ACTION_SUB_PAGE_CHANGED -> {
                ReaderJsBridge.parseSubPageState(payload)?.let { (sub, total) ->
                    onSubPageReported(currentSubPage = sub, totalSubPages = total)
                }
            }

            ReaderJsBridge.ACTION_GESTURE_NEXT_PAGE -> {
                val nextIdx = (current.pageIndex + 1).coerceAtMost(lastIndex)
                if (nextIdx != current.pageIndex) {
                    navigateToPageInternal(nextIdx, scrollY = 0, targetSubPage = 0)
                }
                _events.tryEmit(ReaderEvent.ActionTriggered(action, payload))
            }

            ReaderJsBridge.ACTION_GESTURE_PREV_PAGE -> {
                val prevIdx = (current.pageIndex - 1).coerceAtLeast(0)
                if (prevIdx != current.pageIndex) {
                    navigateToPageInternal(prevIdx, scrollY = 0, targetSubPage = 0)
                }
                _events.tryEmit(ReaderEvent.ActionTriggered(action, payload))
            }

            else -> {
                _events.tryEmit(ReaderEvent.ActionTriggered(action, payload))
                _uiEffects.tryEmit(ReaderUiEffect.CustomAction(action, payload))
            }
        }
    }

    private fun syncBookmarksStateAndEmit() {
        val current = _state.value
        val listCopy = bookmarks.toList()
        _state.value = current.copy(
            bookmarks = listCopy,
            hasBookmarkAtCurrentPage = listCopy.any { it.pageIndex == current.pageIndex },
        )
        _events.tryEmit(
            ReaderEvent.BookmarksChanged(
                documentId = documentId,
                bookmarks = listCopy,
                serializedJson = exportBookmarksJson(),
            ),
        )
    }

    private fun emitPreferencesChanged(state: ReaderState) {
        _events.tryEmit(
            ReaderEvent.PreferencesChanged(
                fontFamily = state.fontFamily,
                fontSizeClass = state.fontSizeClass,
                fontScale = state.fontScale,
                isNightMode = state.isNightMode,
                readingMode = state.readingMode,
                theme = state.theme,
            ),
        )
    }

    private fun buildProgress(
        pageIndex: Int,
        scrollY: Int,
        subPage: Int = _state.value.currentSubPage,
        contentHeightPx: Int,
        viewportHeightPx: Int,
        timestampEpochMs: Long,
    ): ReadingProgress {
        val totalPages = publication.spine.size
        val href = publication.spine.getOrNull(pageIndex).orEmpty()
        val chapterTitle = resolvedToc.firstOrNull { it.spineIndex == pageIndex }?.title
            ?: href
        val percentage = ReadingProgress.calculatePercentage(
            pageIndex = pageIndex,
            totalPages = totalPages,
            scrollY = scrollY,
            contentHeightPx = contentHeightPx,
            viewportHeightPx = viewportHeightPx,
        )
        val pageProgression = if (contentHeightPx > viewportHeightPx && viewportHeightPx > 0) {
            (scrollY.toFloat() / (contentHeightPx - viewportHeightPx).toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        return ReadingProgress(
            documentId = documentId,
            pageIndex = pageIndex,
            pageNumber = if (totalPages > 0) pageIndex + 1 else 0,
            totalPages = totalPages,
            scrollY = scrollY,
            subPage = subPage,
            pageProgression = pageProgression,
            totalPercentage = percentage,
            chapterTitle = chapterTitle,
            lastUpdateEpochMs = timestampEpochMs,
            locator = Locator(
                href = href,
                pageIndex = pageIndex,
                progression = pageProgression,
                title = chapterTitle,
            ),
        )
    }

    private fun extractFootnoteById(targetHref: String, anchorId: String): String? {
        if (targetHref.isBlank() || anchorId.isBlank()) return null
        val html = runCatching { publication.readString(targetHref) }.getOrNull() ?: return null
        val escapedId = Regex.escape(anchorId)
        val regex = Regex(
            """<([a-zA-Z0-9:_-]+)\b([^>]*\bid\s*=\s*(?:"$escapedId"|'$escapedId')[^>]*)>([\s\S]*?)</\1>""",
            RegexOption.IGNORE_CASE,
        )
        val match = regex.find(html) ?: return null
        val tagName = match.groupValues[1].lowercase()
        val attrs = match.groupValues[2].lowercase()
        val isFootnote = tagName == "aside" ||
            attrs.contains("footnote") ||
            attrs.contains("endnote") ||
            attrs.contains("rearnote") ||
            attrs.contains("doc-footnote") ||
            attrs.contains("doc-endnote")
        if (!isFootnote) return null
        val body = match.groupValues[3]
            .replace(Regex("""<[^>]+>"""), " ")
            .let { XmlEntities.decode(it) }
            .replace(Regex("""\s+"""), " ")
            .trim()
        return body.takeIf { it.isNotEmpty() }
    }

    private fun buildStats(pageIndex: Int, pageProgression: Float): ReadingStats {
        if (chapterWordCounts.isEmpty()) return ReadingStats()
        val safePage = pageIndex.coerceIn(0, chapterWordCounts.size - 1)
        val safeProg = pageProgression.coerceIn(0f, 1f)
        val chapterWords = chapterWordCounts[safePage]
        val remainingChapterWords = (chapterWords * (1f - safeProg)).coerceAtLeast(0f)
        val subsequentWords = ((safePage + 1) until chapterWordCounts.size).sumOf { chapterWordCounts[it] }
        val remainingBookWords = remainingChapterWords + subsequentWords.toFloat()
        val wpm = 200
        val chapterMins = if (chapterWords == 0 || safeProg >= 1f) {
            0
        } else {
            ceil(remainingChapterWords / wpm.toDouble()).toInt().coerceAtLeast(1)
        }
        val bookMins = if (totalWordCount == 0 || (safePage == chapterWordCounts.size - 1 && safeProg >= 1f)) {
            0
        } else {
            ceil(remainingBookWords / wpm.toDouble()).toInt().coerceAtLeast(1)
        }
        return ReadingStats(
            chapterWordCount = chapterWords,
            totalWordCount = totalWordCount,
            estimatedChapterMinutesRemaining = chapterMins,
            estimatedBookMinutesRemaining = bookMins,
            wordsPerMinute = wpm,
        )
    }

    public companion object {
        public const val DEFAULT_BOOK_BASE_URL: String = "book://content/"
    }
}

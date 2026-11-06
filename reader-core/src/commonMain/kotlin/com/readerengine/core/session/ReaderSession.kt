package com.readerengine.core.session

import com.readerengine.core.bridge.InterceptedUrl
import com.readerengine.core.bridge.PayloadCodec
import com.readerengine.core.bridge.ReaderJsBridge
import com.readerengine.core.error.ReaderException
import com.readerengine.core.model.Bookmark
import com.readerengine.core.model.HighlightItem
import com.readerengine.core.model.HighlightStyle
import com.readerengine.core.model.InitialBookState
import com.readerengine.core.model.Metadata
import com.readerengine.core.model.PageFormState
import com.readerengine.core.model.Publication
import com.readerengine.core.model.ReadingProgress
import com.readerengine.core.model.SearchResult
import com.readerengine.core.model.TocItem
import com.readerengine.core.provider.normalizeHref
import com.readerengine.core.search.BookSearchEngine
import com.readerengine.core.theme.ThemeTokens
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Estado reativo completo observado pela UI nativa (Compose / SwiftUI / View / UIKit). */
data class ReaderState(
    val documentId: String,
    val metadata: Metadata,
    val pageIndex: Int,
    val spineLen: Int,
    val scrollY: Int,
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
)

/**
 * Eventos de domínio observáveis pelo app host para sincronização com qualquer backend
 * (SQLite/Room, CoreData, REST API, Firestore ou SharedPreferences/UserDefaults).
 */
sealed interface ReaderEvent {
    data class ProgressUpdated(
        val progress: ReadingProgress,
    ) : ReaderEvent

    data class PageHighlightsChanged(
        val documentId: String,
        val pageIndex: Int,
        val rawSerializedHighlights: String,
        /** Payload pronto para persistência (comprimido automaticamente com prefixo `gz:` se >= 200KB). */
        val compressedPayload: String,
        val summaryItems: List<HighlightItem>,
    ) : ReaderEvent

    data class PageFormChanged(
        val documentId: String,
        val pageIndex: Int,
        val rawJsonPayload: String,
        /** Payload pronto para persistência (comprimido automaticamente com prefixo `gz:` se >= 200KB). */
        val compressedPayload: String,
        val formState: PageFormState,
    ) : ReaderEvent

    data class BookmarksChanged(
        val documentId: String,
        val bookmarks: List<Bookmark>,
        val serializedJson: String,
    ) : ReaderEvent

    data class PreferencesChanged(
        val fontFamily: String,
        val fontSizeClass: String,
        val fontScale: Float,
        val isNightMode: Boolean,
        val theme: ThemeTokens,
    ) : ReaderEvent
}

/** Efeitos pontuais de UI disparados pela sessão para a tela nativa executar. */
sealed interface ReaderUiEffect {
    /**
     * Disparado quando o usuário clica em um link com esquema customizado no livro
     * (ex: `verse://?q=...`, `footnote://...`, `audio://...`, `glossary://...`).
     */
    data class OpenCustomUri(
        val scheme: String,
        val rawUrl: String,
        val title: String,
        val content: String?,
    ) : ReaderUiEffect

    data class OpenExternalUrl(
        val url: String,
    ) : ReaderUiEffect

    data class ScrollToY(
        val scrollYPx: Int,
        val anchor: String? = null,
    ) : ReaderUiEffect

    data class EvaluateJs(
        val script: String,
    ) : ReaderUiEffect

    data class ShowMessage(
        val message: String,
    ) : ReaderUiEffect
}

/** Comandos despachados pela UI nativa para a sessão. */
sealed interface ReaderCommand {
    data object NextPage : ReaderCommand
    data object PrevPage : ReaderCommand
    data class GoToPage(val pageIndex: Int, val scrollY: Int = 0) : ReaderCommand
    data class GoToHref(val href: String) : ReaderCommand
    data class UpdateScroll(
        val scrollY: Int,
        val contentHeightPx: Int = 0,
        val viewportHeightPx: Int = 0,
        val timestampEpochMs: Long = 0L,
    ) : ReaderCommand
    data class SetTheme(val theme: ThemeTokens) : ReaderCommand
    data class SetFontScale(val scale: Float) : ReaderCommand
    data class SetFontFamily(val fontFamily: String) : ReaderCommand
    data class SetFontSizeClass(val fontSizeClass: String) : ReaderCommand
    data class SetNightMode(val enabled: Boolean) : ReaderCommand
    data class ApplyHighlightStyle(val style: HighlightStyle) : ReaderCommand
    data object RemoveSelectedHighlight : ReaderCommand
    data class SelectHighlightItem(val item: HighlightItem) : ReaderCommand
    data class RemoveHighlightItem(val highlightId: Int) : ReaderCommand
    data class ToggleBookmark(val label: String? = null, val timestampEpochMs: Long = 0L) : ReaderCommand
    data class RemoveBookmark(val bookmarkId: String) : ReaderCommand
    data class GoToBookmark(val bookmarkId: String) : ReaderCommand
}

class ReaderSession(
    private val publication: Publication,
    initialBookState: InitialBookState = InitialBookState(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val documentId: String = initialBookState.documentId.ifBlank {
        publication.metadata.identifier ?: publication.metadata.title
    }

    private val resolvedToc: List<TocItem> = publication.toc.mapNotNull { entry ->
        val targetHref = normalizeHref(entry.href)
        val index = publication.spine.indexOfFirst { spineHref ->
            val cleanSpine = normalizeHref(spineHref)
            cleanSpine == targetHref || cleanSpine.endsWith("/$targetHref") || targetHref.endsWith("/$cleanSpine")
        }
        if (index < 0) null
        else TocItem(
            title = entry.title,
            subtitle = entry.subtitle,
            href = entry.href,
            spineIndex = index,
        )
    }

    private val pageSerializedHighlights = LinkedHashMap<Int, String>().apply {
        initialBookState.savedPageHighlights.forEach { (page, raw) ->
            put(page, PayloadCodec.decodeIfNeeded(raw))
        }
    }

    private val pageHighlightSummaries = LinkedHashMap<Int, List<HighlightItem>>()

    private val pageFormStates = LinkedHashMap<Int, PageFormState>().apply {
        initialBookState.savedPageForms.forEach { (page, raw) ->
            put(page, ReaderJsBridge.parsePageFormState(raw))
        }
    }

    private val bookmarks = initialBookState.savedBookmarks.toMutableList()

    private val _events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()

    private val _uiEffects = MutableSharedFlow<ReaderUiEffect>(extraBufferCapacity = 32)
    val uiEffects: SharedFlow<ReaderUiEffect> = _uiEffects.asSharedFlow()

    private val _state: MutableStateFlow<ReaderState>
    val state: StateFlow<ReaderState>

    init {
        val lastIndex = (publication.spine.size - 1).coerceAtLeast(0)
        val startPage = initialBookState.initialPageIndex.coerceIn(0, lastIndex)
        val startScroll = initialBookState.initialScrollY.coerceAtLeast(0)
        val startTheme = initialBookState.theme ?: ThemeTokens.material3()
        val startScale = initialBookState.fontScale.coerceIn(0.5f, 3.0f)

        val initialProgress = buildProgress(
            pageIndex = startPage,
            scrollY = startScroll,
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
            ),
        )
        state = _state.asStateFlow()
    }

    fun publication(): Publication = publication

    /**
     * Realiza busca textual case-insensitive em todos os capítulos do livro,
     * retornando trechos com contexto (`snippet`), índice da página e posição relativa.
     */
    fun search(query: String, maxResults: Int = 50): List<SearchResult> =
        BookSearchEngine.search(
            publication = publication,
            resolvedToc = resolvedToc,
            query = query,
            maxResults = maxResults,
        )

    /** Exporta a lista de marcadores (Bookmarks) como JSON. */
    fun exportBookmarksJson(): String =
        json.encodeToString(ListSerializer(Bookmark.serializer()), bookmarks)

    /** Importa uma lista de marcadores (Bookmarks) a partir de um JSON salvo pelo app host. */
    fun importBookmarksJson(rawJson: String): ReaderState {
        val parsed = runCatching {
            json.decodeFromString(ListSerializer(Bookmark.serializer()), rawJson)
        }.getOrDefault(emptyList())
        bookmarks.clear()
        bookmarks.addAll(parsed)
        val current = _state.value
        _state.value = current.copy(
            bookmarks = bookmarks.toList(),
            hasBookmarkAtCurrentPage = bookmarks.any { it.pageIndex == current.pageIndex },
        )
        return _state.value
    }

    /** Despacha um comando da UI e retorna o novo `ReaderState`. */
    fun dispatch(command: ReaderCommand): ReaderState {
        val current = _state.value
        val lastIndex = (current.spineLen - 1).coerceAtLeast(0)

        when (command) {
            ReaderCommand.NextPage -> {
                val nextIdx = (current.pageIndex + 1).coerceAtMost(lastIndex)
                if (nextIdx != current.pageIndex || current.scrollY != 0) {
                    navigateToPageInternal(nextIdx, scrollY = 0)
                }
            }

            ReaderCommand.PrevPage -> {
                val prevIdx = (current.pageIndex - 1).coerceAtLeast(0)
                if (prevIdx != current.pageIndex || current.scrollY != 0) {
                    navigateToPageInternal(prevIdx, scrollY = 0)
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

            is ReaderCommand.UpdateScroll -> {
                val clampedScroll = command.scrollY.coerceAtLeast(0)
                val newProgress = buildProgress(
                    pageIndex = current.pageIndex,
                    scrollY = clampedScroll,
                    contentHeightPx = command.contentHeightPx,
                    viewportHeightPx = command.viewportHeightPx,
                    timestampEpochMs = command.timestampEpochMs,
                )
                _state.value = current.copy(
                    scrollY = clampedScroll,
                    progress = newProgress,
                )
                _events.tryEmit(ReaderEvent.ProgressUpdated(newProgress))
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
                _uiEffects.tryEmit(
                    ReaderUiEffect.EvaluateJs(ReaderJsBridge.removeHighlightByIdCommand(command.highlightId)),
                )
            }

            is ReaderCommand.ToggleBookmark -> {
                val existingIdx = bookmarks.indexOfFirst { it.pageIndex == current.pageIndex }
                if (existingIdx >= 0) {
                    bookmarks.removeAt(existingIdx)
                } else {
                    val defaultLabel = command.label
                        ?: resolvedToc.firstOrNull { it.spineIndex == current.pageIndex }?.title
                        ?: "Página ${current.pageIndex + 1}"
                    bookmarks.add(
                        Bookmark(
                            id = "bm-${current.pageIndex}-${bookmarks.size + 1}",
                            pageIndex = current.pageIndex,
                            scrollY = current.scrollY,
                            label = defaultLabel,
                            createdAtEpochMs = command.timestampEpochMs,
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
     * Chamado pela plataforma quando o WebView retorna os dados de `readerJS.getHighlights()`
     * e opcionalmente `readerJS.summaryHighlights()`.
     * Atualiza o estado da sessão e emite `ReaderEvent.PageHighlightsChanged`.
     */
    fun onPageHighlightsReported(
        rawSerializedHighlights: String,
        rawSummaryJson: String? = null,
        pageIndex: Int = _state.value.pageIndex,
    ) {
        val cleanSerialized = PayloadCodec.decodeIfNeeded(
            ReaderJsBridge.unquoteJsEvaluation(rawSerializedHighlights),
        )
        val summaryList = if (rawSummaryJson != null) {
            ReaderJsBridge.parseSummaryHighlights(rawSummaryJson, pageIndex)
        } else {
            pageHighlightSummaries[pageIndex].orEmpty()
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
    fun onSummaryHighlightsLoaded(
        rawSummaryJson: String,
        pageIndex: Int = _state.value.pageIndex,
    ): List<HighlightItem> {
        val items = ReaderJsBridge.parseSummaryHighlights(rawSummaryJson, pageIndex)
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
    fun onPageFormReported(
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
     * Injeta dados vindos assincronamente do backend para uma página específica.
     */
    fun restorePageCloudData(
        pageIndex: Int,
        serializedHighlights: String? = null,
        annotationsJson: String? = null,
    ) {
        if (serializedHighlights != null) {
            val decoded = PayloadCodec.decodeIfNeeded(serializedHighlights)
            pageSerializedHighlights[pageIndex] = decoded
            if (pageIndex == _state.value.pageIndex) {
                _state.value = _state.value.copy(currentPageSerializedHighlights = decoded)
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
            }
        }
    }

    /**
     * Intercepta qualquer URL clicada na WebView (esquemas customizados como `verse://`, `footnote://`,
     * links externos `http://` ou links internos de capítulos com `#anchor`)
     * e emite o `ReaderUiEffect` ou navegação correspondente.
     * Retorna `true` se o clique foi tratado pela engine.
     */
    fun handleUrlNavigation(url: String): Boolean {
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

            is InterceptedUrl.ExternalWebUrl -> {
                _uiEffects.tryEmit(ReaderUiEffect.OpenExternalUrl(intercepted.url))
                true
            }

            InterceptedUrl.InvalidHighlightOnInput -> {
                _uiEffects.tryEmit(
                    ReaderUiEffect.ShowMessage("Marcação inválida neste campo."),
                )
                true
            }

            InterceptedUrl.ClipboardAction -> true

            is InterceptedUrl.InternalResource -> {
                val targetHref = normalizeHref(intercepted.href)
                val spineIdx = publication.spine.indexOfFirst { spineItem ->
                    val normalizedSpine = normalizeHref(spineItem)
                    normalizedSpine == targetHref ||
                        normalizedSpine.endsWith("/$targetHref") ||
                        targetHref.endsWith("/$normalizedSpine")
                }
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

    /** Retorna o HTML da página atual com CSS de tema, modo noturno e bridge JS injetados. */
    fun currentHtml(): String {
        val current = _state.value
        val href = publication.spine.getOrNull(current.pageIndex)
            ?: throw ReaderException.InvalidContent("Spine vazio ou índice inválido (${current.pageIndex})")
        val rawHtml = publication.readString(href)
        return ReaderJsBridge.injectThemeAndBridge(
            rawHtml = rawHtml,
            theme = current.theme,
            fontScale = current.fontScale,
            fontFamily = current.fontFamily,
            fontSizeClass = current.fontSizeClass,
            isNightMode = current.isNightMode,
        )
    }

    /** Lê um recurso interno do livro (imagem, CSS, fonte) pelo `href` relativo. */
    fun readResource(href: String): ByteArray = publication.read(href)

    /** Retorna o MIME type declarado no manifesto da publicação para o `href`, se existir. */
    fun resourceMediaType(href: String): String? = publication.findResource(href)?.mediaType

    private fun navigateToPageInternal(targetPageIndex: Int, scrollY: Int) {
        val current = _state.value
        val newProgress = buildProgress(
            pageIndex = targetPageIndex,
            scrollY = scrollY,
            contentHeightPx = 0,
            viewportHeightPx = 0,
            timestampEpochMs = 0L,
        )
        _state.value = current.copy(
            pageIndex = targetPageIndex,
            scrollY = scrollY,
            progress = newProgress,
            currentPageSerializedHighlights = pageSerializedHighlights[targetPageIndex].orEmpty(),
            currentPageHighlights = pageHighlightSummaries[targetPageIndex].orEmpty(),
            currentPageFormState = pageFormStates[targetPageIndex] ?: PageFormState(),
            hasBookmarkAtCurrentPage = bookmarks.any { it.pageIndex == targetPageIndex },
        )
        _events.tryEmit(ReaderEvent.ProgressUpdated(newProgress))
        if (scrollY > 0) {
            _uiEffects.tryEmit(ReaderUiEffect.ScrollToY(scrollYPx = scrollY))
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
                theme = state.theme,
            ),
        )
    }

    private fun buildProgress(
        pageIndex: Int,
        scrollY: Int,
        contentHeightPx: Int,
        viewportHeightPx: Int,
        timestampEpochMs: Long,
    ): ReadingProgress {
        val totalPages = publication.spine.size
        val chapterTitle = resolvedToc.firstOrNull { it.spineIndex == pageIndex }?.title
            ?: publication.spine.getOrNull(pageIndex).orEmpty()
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
            pageProgression = pageProgression,
            totalPercentage = percentage,
            chapterTitle = chapterTitle,
            lastUpdateEpochMs = timestampEpochMs,
        )
    }
}

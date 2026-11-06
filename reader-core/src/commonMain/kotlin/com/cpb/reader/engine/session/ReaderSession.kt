package com.cpb.reader.engine.session

import com.cpb.reader.engine.bridge.FirestorePayloadCodec
import com.cpb.reader.engine.bridge.InterceptedUrl
import com.cpb.reader.engine.bridge.ReaderJsBridge
import com.cpb.reader.engine.error.ReaderException
import com.cpb.reader.engine.model.HighlightItem
import com.cpb.reader.engine.model.HighlightStyle
import com.cpb.reader.engine.model.InitialBookState
import com.cpb.reader.engine.model.Metadata
import com.cpb.reader.engine.model.PageFormState
import com.cpb.reader.engine.model.Publication
import com.cpb.reader.engine.model.ReadingProgress
import com.cpb.reader.engine.model.TocItem
import com.cpb.reader.engine.provider.normalizeHref
import com.cpb.reader.engine.theme.ThemeTokens
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado reativo completo observado pela UI nativa (Compose / SwiftUI). */
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
)

/**
 * Eventos de domínio observáveis pelos 4 apps para sincronização com seus respectivos Backends
 * (Firestore `highlights` e `annotations`, API REST ou SharedPreferences/UserDefaults).
 */
sealed interface ReaderEvent {
    data class ProgressUpdated(
        val progress: ReadingProgress,
    ) : ReaderEvent

    data class PageHighlightsChanged(
        val documentId: String,
        val pageIndex: Int,
        val rawSerializedHighlights: String,
        /** Payload pronto para gravar no Firestore (comprimido com prefixo `gz:` se >= 200KB). */
        val firestoreValue: String,
        val summaryItems: List<HighlightItem>,
    ) : ReaderEvent

    data class PageFormChanged(
        val documentId: String,
        val pageIndex: Int,
        val rawJsonPayload: String,
        /** Payload pronto para gravar no Firestore (comprimido com prefixo `gz:` se >= 200KB). */
        val firestoreValue: String,
        val formState: PageFormState,
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
    data class OpenVerseModal(
        val referenceTitle: String,
        val verseText: String?,
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
}

class ReaderSession(
    private val publication: Publication,
    initialBookState: InitialBookState = InitialBookState(),
) {
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
            put(page, FirestorePayloadCodec.decodeIfNeeded(raw))
        }
    }

    private val pageHighlightSummaries = LinkedHashMap<Int, List<HighlightItem>>()

    private val pageFormStates = LinkedHashMap<Int, PageFormState>().apply {
        initialBookState.savedPageForms.forEach { (page, raw) ->
            put(page, ReaderJsBridge.parsePageFormState(raw))
        }
    }

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
            ),
        )
        state = _state.asStateFlow()
    }

    fun publication(): Publication = publication

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
        }

        return _state.value
    }

    /**
     * Chamado pela plataforma quando o WebView retorna os dados de `readerJS.getHighlights()`
     * e opcionalmente `readerJS.summaryHighlights()`.
     * Atualiza o estado da sessão e emite `ReaderEvent.PageHighlightsChanged` para o App salvar no Firestore.
     */
    fun onPageHighlightsReported(
        rawSerializedHighlights: String,
        rawSummaryJson: String? = null,
        pageIndex: Int = _state.value.pageIndex,
    ) {
        val cleanSerialized = FirestorePayloadCodec.decodeIfNeeded(
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

        val firestoreValue = FirestorePayloadCodec.encodeIfNeeded(cleanSerialized)
        _events.tryEmit(
            ReaderEvent.PageHighlightsChanged(
                documentId = documentId,
                pageIndex = pageIndex,
                rawSerializedHighlights = cleanSerialized,
                firestoreValue = firestoreValue,
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
     * Chamado pela plataforma quando o WebView retorna o JSON de `getAllModifications()`
     * (`{"annotations": [...], "answers": [...]}`).
     * Atualiza o estado e emite `ReaderEvent.PageFormChanged` para o App salvar na coleção `annotations` do Firestore.
     */
    fun onPageFormReported(
        rawModificationsJson: String,
        pageIndex: Int = _state.value.pageIndex,
    ) {
        val cleanJson = FirestorePayloadCodec.decodeIfNeeded(
            ReaderJsBridge.unquoteJsEvaluation(rawModificationsJson),
        )
        val formState = ReaderJsBridge.parsePageFormState(cleanJson)
        pageFormStates[pageIndex] = formState

        if (pageIndex == _state.value.pageIndex) {
            _state.value = _state.value.copy(currentPageFormState = formState)
        }

        val canonicalJson = if (cleanJson.startsWith("{")) cleanJson else ReaderJsBridge.serializePageFormState(formState)
        val firestoreValue = FirestorePayloadCodec.encodeIfNeeded(canonicalJson)

        _events.tryEmit(
            ReaderEvent.PageFormChanged(
                documentId = documentId,
                pageIndex = pageIndex,
                rawJsonPayload = canonicalJson,
                firestoreValue = firestoreValue,
                formState = formState,
            ),
        )
    }

    /**
     * Injeta dados vindos assincronamente do Firestore para uma página específica
     * (por exemplo, quando o App faz o fetch de `highlights/{docId|uid}` ao abrir a página).
     */
    fun restorePageCloudData(
        pageIndex: Int,
        serializedHighlights: String? = null,
        annotationsJson: String? = null,
    ) {
        if (serializedHighlights != null) {
            val decoded = FirestorePayloadCodec.decodeIfNeeded(serializedHighlights)
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
     * Intercepta qualquer URL clicada na WebView (`verse://`, `http://`, links internos de capítulos com `#anchor`)
     * e emite o `ReaderUiEffect` ou navegação correspondente.
     * Retorna `true` se o clique foi tratado pela engine.
     */
    fun handleUrlNavigation(url: String): Boolean {
        return when (val intercepted = ReaderJsBridge.parseSpecialUrl(url)) {
            is InterceptedUrl.BibleVerse -> {
                _uiEffects.tryEmit(
                    ReaderUiEffect.OpenVerseModal(
                        referenceTitle = intercepted.referenceTitle,
                        verseText = intercepted.verseText,
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
                    ReaderUiEffect.ShowMessage("Marcação inválida! Não é possível marcar uma resposta!"),
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
        )
        _events.tryEmit(ReaderEvent.ProgressUpdated(newProgress))
        if (scrollY > 0) {
            _uiEffects.tryEmit(ReaderUiEffect.ScrollToY(scrollYPx = scrollY))
        }
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

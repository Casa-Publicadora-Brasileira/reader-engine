package com.readerengine.core.session

import com.readerengine.core.model.MediaOverlayClip
import com.readerengine.core.provider.normalizeHref
import kotlinx.serialization.Serializable

/** Resultado de sincronização pontual de Media Overlay (SMIL) com a posição de reprodução do áudio. */
@Serializable
public data class MediaOverlaySyncResult(
    val clip: MediaOverlayClip,
    val pageIndex: Int,
    val chapterChanged: Boolean,
    val clipChanged: Boolean,
)

/**
 * Orquestrador multiplataforma de sincronização áudio/texto para **EPUB 3 Media Overlays (`.smil`)**:
 * - Localiza o clipe `<par>` ativo para `(audioHref, positionSeconds)`
 * - Navega automaticamente para o capítulo correspondente caso o áudio avance para outro recurso
 * - Emite `HighlightTtsSentence(elementId = ...)` apenas na troca de clipe (evitando spam na WebView)
 * - Suporta toque-para-áudio (`clipForElement(elementId)`) retornando o timestamp exato de início.
 */
public class MediaOverlaySynchronizer(
    private val session: ReaderSession,
) {
    private var lastClipKey: String? = null

    public fun syncAt(audioHref: String, positionSeconds: Double): MediaOverlaySyncResult? {
        val clip = session.findMediaOverlayClipAt(audioHref, positionSeconds) ?: return null
        val targetHref = normalizeHref(clip.textHref)
        val spine = session.publication().spine
        val targetPageIndex = spine.indexOfFirst { raw ->
            val clean = normalizeHref(raw)
            clean == targetHref || clean.endsWith("/$targetHref") || targetHref.endsWith("/$clean")
        }

        val currentState = session.state.value
        val resolvedPage = if (targetPageIndex >= 0) targetPageIndex else currentState.pageIndex
        var chapterChanged = false
        if (targetPageIndex >= 0 && targetPageIndex != currentState.pageIndex) {
            session.dispatch(ReaderCommand.GoToPage(targetPageIndex))
            chapterChanged = true
        }

        val clipKey = "${clip.textHref}#${clip.textFragmentId.orEmpty()}@${clip.clipBeginSeconds}"
        val clipChanged = clipKey != lastClipKey
        if (clipChanged || chapterChanged) {
            lastClipKey = clipKey
            val fragId = clip.textFragmentId?.trim()?.takeIf { it.isNotEmpty() }
            if (fragId != null) {
                session.dispatch(
                    ReaderCommand.HighlightTtsSentence(
                        sentenceIndex = 0,
                        text = null,
                        elementId = fragId,
                    ),
                )
            }
        }

        return MediaOverlaySyncResult(
            clip = clip,
            pageIndex = resolvedPage,
            chapterChanged = chapterChanged,
            clipChanged = clipChanged,
        )
    }

    public fun clipForElement(
        elementId: String,
        pageIndex: Int = session.state.value.pageIndex,
    ): MediaOverlayClip? {
        val target = elementId.trim()
        if (target.isEmpty()) return null
        return session.mediaOverlayClips(pageIndex).firstOrNull { clip ->
            clip.textFragmentId?.equals(target, ignoreCase = true) == true
        }
    }

    public fun reset() {
        lastClipKey = null
        session.dispatch(ReaderCommand.ClearTtsHighlight)
    }
}

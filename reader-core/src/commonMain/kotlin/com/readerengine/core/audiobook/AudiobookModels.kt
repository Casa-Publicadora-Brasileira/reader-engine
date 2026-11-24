package com.readerengine.core.audiobook

import com.readerengine.core.model.Metadata
import com.readerengine.core.model.ProgressMergePolicy
import com.readerengine.core.model.Resource
import com.readerengine.core.model.ResourceProvider
import kotlinx.serialization.Serializable

/** Faixa de áudio individual de um audiobook (`readingOrder`). */
@Serializable
public data class AudioTrack(
    val index: Int,
    val title: String,
    val href: String,
    val mediaType: String = "audio/mpeg",
    val durationSeconds: Double = 0.0,
    val startOffsetSeconds: Double = 0.0,
)

/** Capítulo ou marcador de sumário (`toc`) de um audiobook com timestamp inicial. */
@Serializable
public data class AudioChapter(
    val title: String,
    val trackIndex: Int,
    val startSeconds: Double = 0.0,
)

/** Marcador salvo pelo usuário em uma faixa e timestamp específicos do audiobook. */
@Serializable
public data class AudioBookmark(
    val id: String,
    val trackIndex: Int,
    val positionSeconds: Double,
    val label: String? = null,
    val createdAtEpochMs: Long = 0L,
)

/** Publicação de audiobook pronta para ser consumida por [AudiobookSession]. */
public class AudiobookPublication(
    public val metadata: Metadata,
    public val tracks: List<AudioTrack>,
    public val chapters: List<AudioChapter>,
    public val resources: List<Resource>,
    public val provider: ResourceProvider,
) {
    public val totalDurationSeconds: Double = tracks.sumOf { it.durationSeconds.coerceAtLeast(0.0) }

    public fun readTrackBytes(trackIndex: Int): ByteArray {
        val track = tracks.getOrNull(trackIndex) ?: return ByteArray(0)
        return provider.read(track.href)
    }

    public fun trackSize(trackIndex: Int): Long? {
        val track = tracks.getOrNull(trackIndex) ?: return null
        return provider.size(track.href)
    }

    public fun readTrackRange(trackIndex: Int, offset: Long, byteCount: Int): ByteArray {
        val track = tracks.getOrNull(trackIndex) ?: return ByteArray(0)
        return provider.readRange(track.href, offset, byteCount)
    }

    public fun readCoverBytes(): ByteArray? =
        metadata.coverHref?.let { runCatching { provider.read(it) }.getOrNull() }

    public fun close(): Unit = provider.close()
}

/** Estado inicial opcional fornecido pelo app host ao abrir um audiobook. */
public data class InitialAudiobookState(
    val documentId: String = "",
    val initialTrackIndex: Int = 0,
    val initialPositionSeconds: Double = 0.0,
    val playbackRate: Float = 1.0f,
    val savedBookmarks: List<AudioBookmark> = emptyList(),
)

/** Snapshot serializável de progresso e bookmarks de um audiobook para persistência/sync. */
@Serializable
public data class AudiobookStateSnapshot(
    val documentId: String,
    val trackIndex: Int = 0,
    val positionSeconds: Double = 0.0,
    val playbackRate: Float = 1.0f,
    val totalPercentage: Float = 0f,
    val bookmarks: List<AudioBookmark> = emptyList(),
    val updatedAtEpochMs: Long = 0L,
)

/** Resumo do resultado de um merge multi-dispositivo de audiobook. */
@Serializable
public data class AudiobookMergeResult(
    val progressUpdated: Boolean = false,
    val addedBookmarks: Int = 0,
    val policyApplied: ProgressMergePolicy = ProgressMergePolicy.NEWEST_TIMESTAMP,
)

/** Estado reativo observado pela UI de player (Compose / SwiftUI / Media3 / AVPlayer). */
public data class AudiobookState(
    val documentId: String,
    val metadata: Metadata,
    val tracks: List<AudioTrack>,
    val chapters: List<AudioChapter>,
    val currentTrackIndex: Int,
    val positionSeconds: Double,
    val trackDurationSeconds: Double,
    val totalDurationSeconds: Double,
    val globalPositionSeconds: Double,
    val totalPercentage: Float,
    val isPlaying: Boolean = false,
    val playbackRate: Float = 1.0f,
    val sleepTimerRemainingSeconds: Int? = null,
    val bookmarks: List<AudioBookmark> = emptyList(),
    val lastUpdateEpochMs: Long = 0L,
) {
    public val currentTrack: AudioTrack?
        get() = tracks.getOrNull(currentTrackIndex)
}

/** Eventos de domínio emitidos pela [AudiobookSession] para persistência no app host. */
public sealed interface AudiobookEvent {
    public data class ProgressUpdated(
        val documentId: String,
        val trackIndex: Int,
        val positionSeconds: Double,
        val totalPercentage: Float,
        val timestampEpochMs: Long,
    ) : AudiobookEvent

    public data class BookmarksChanged(
        val documentId: String,
        val bookmarks: List<AudioBookmark>,
        val serializedJson: String,
    ) : AudiobookEvent

    public data class PlaybackRateChanged(
        val playbackRate: Float,
    ) : AudiobookEvent
}

/** Efeitos pontuais de UI/Player para o app host executar no ExoPlayer / AVPlayer. */
public sealed interface AudiobookUiEffect {
    public data class LoadTrack(
        val trackIndex: Int,
        val track: AudioTrack,
        val startPositionSeconds: Double,
        val autoPlay: Boolean,
    ) : AudiobookUiEffect

    public data object PlayPlayback : AudiobookUiEffect
    public data object PausePlayback : AudiobookUiEffect

    public data class SeekPlayback(
        val positionSeconds: Double,
    ) : AudiobookUiEffect

    public data class UpdatePlaybackRate(
        val playbackRate: Float,
    ) : AudiobookUiEffect

    public data object SleepTimerExpired : AudiobookUiEffect
}

/** Comandos despachados pela UI/controles de mídia para a [AudiobookSession]. */
public sealed interface AudiobookCommand {
    public data object Play : AudiobookCommand
    public data object Pause : AudiobookCommand
    public data object TogglePlayPause : AudiobookCommand
    public data object NextTrack : AudiobookCommand
    public data object PrevTrack : AudiobookCommand
    public data class GoToTrack(val trackIndex: Int, val positionSeconds: Double = 0.0, val autoPlay: Boolean = true) : AudiobookCommand
    public data class GoToChapter(val chapterIndex: Int, val autoPlay: Boolean = true) : AudiobookCommand
    public data class SeekTo(val positionSeconds: Double) : AudiobookCommand
    public data class SeekBy(val deltaSeconds: Double) : AudiobookCommand
    public data class UpdatePosition(
        val positionSeconds: Double,
        val trackDurationSeconds: Double? = null,
        val timestampEpochMs: Long = 0L,
    ) : AudiobookCommand
    public data object TrackCompleted : AudiobookCommand
    public data class SetPlaybackRate(val rate: Float) : AudiobookCommand
    public data class SetSleepTimer(val durationSeconds: Int?) : AudiobookCommand
    public data class TickSleepTimer(val elapsedSeconds: Int = 1) : AudiobookCommand
    public data class AddBookmark(val label: String? = null, val timestampEpochMs: Long = 0L) : AudiobookCommand
    public data class RemoveBookmark(val bookmarkId: String) : AudiobookCommand
    public data class GoToBookmark(val bookmarkId: String, val autoPlay: Boolean = true) : AudiobookCommand
}

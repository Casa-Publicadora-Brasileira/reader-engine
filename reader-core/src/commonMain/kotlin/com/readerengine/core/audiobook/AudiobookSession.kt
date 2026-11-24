package com.readerengine.core.audiobook

import com.readerengine.core.bridge.PayloadCodec
import com.readerengine.core.model.ProgressMergePolicy
import com.readerengine.core.observability.ReaderObserver
import com.readerengine.core.session.FlowSubscription
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

/**
 * Máquina de estados reativa multiplataforma para reprodução de Audiobooks,
 * desenhada para conectar facilmente a **Media3 / ExoPlayer** (Android) e **AVPlayer** (iOS).
 */
public class AudiobookSession(
    private val publication: AudiobookPublication,
    initialState: InitialAudiobookState = InitialAudiobookState(),
    private val observer: ReaderObserver = ReaderObserver.NoOp,
) {
    public constructor(
        publication: AudiobookPublication,
        initialState: InitialAudiobookState,
    ) : this(
        publication = publication,
        initialState = initialState,
        observer = ReaderObserver.NoOp,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val documentId: String = initialState.documentId.ifBlank {
        publication.metadata.identifier?.takeIf { it.isNotBlank() }
            ?: publication.metadata.title.takeIf { it.isNotBlank() }
            ?: "audiobook-unknown"
    }

    private val trackDurations: DoubleArray = DoubleArray(publication.tracks.size) { idx ->
        publication.tracks[idx].durationSeconds.coerceAtLeast(0.0)
    }

    private val bookmarks = initialState.savedBookmarks.toMutableList()
    private var nextBookmarkSeq: Int = bookmarks.size + 1

    private val _events = MutableSharedFlow<AudiobookEvent>(replay = 1, extraBufferCapacity = 128)
    public val events: SharedFlow<AudiobookEvent> = _events.asSharedFlow()

    private val _uiEffects = MutableSharedFlow<AudiobookUiEffect>(extraBufferCapacity = 64)
    public val uiEffects: SharedFlow<AudiobookUiEffect> = _uiEffects.asSharedFlow()

    private val _state: MutableStateFlow<AudiobookState>
    public val state: StateFlow<AudiobookState>

    init {
        val lastIdx = (publication.tracks.size - 1).coerceAtLeast(0)
        val startTrack = initialState.initialTrackIndex.coerceIn(0, lastIdx)
        val startPos = initialState.initialPositionSeconds.coerceAtLeast(0.0)
        val startRate = initialState.playbackRate.coerceIn(0.5f, 3.0f)

        _state = MutableStateFlow(
            buildState(
                trackIndex = startTrack,
                positionSeconds = startPos,
                isPlaying = false,
                playbackRate = startRate,
                sleepTimer = null,
                timestampEpochMs = 0L,
            ),
        )
        state = _state.asStateFlow()
    }

    public fun publication(): AudiobookPublication = publication

    public fun watchState(onEach: (AudiobookState) -> Unit): FlowSubscription {
        val job = watchScope.launch { state.collect { onEach(it) } }
        return FlowSubscription { job.cancel() }
    }

    public fun watchEvents(onEach: (AudiobookEvent) -> Unit): FlowSubscription {
        val job = watchScope.launch { events.collect { onEach(it) } }
        return FlowSubscription { job.cancel() }
    }

    public fun watchUiEffects(onEach: (AudiobookUiEffect) -> Unit): FlowSubscription {
        val job = watchScope.launch { uiEffects.collect { onEach(it) } }
        return FlowSubscription { job.cancel() }
    }

    public fun readTrackBytes(trackIndex: Int): ByteArray = publication.readTrackBytes(trackIndex)

    public fun trackSize(trackIndex: Int): Long? = publication.trackSize(trackIndex)

    public fun readTrackRange(trackIndex: Int, offset: Long, byteCount: Int): ByteArray =
        publication.readTrackRange(trackIndex, offset, byteCount)

    public fun readCoverBytes(): ByteArray? = publication.readCoverBytes()

    public fun close() {
        watchScope.cancel()
        publication.close()
    }

    public fun dispatch(command: AudiobookCommand): AudiobookState {
        val cur = _state.value
        val lastIdx = (publication.tracks.size - 1).coerceAtLeast(0)

        when (command) {
            AudiobookCommand.Play -> {
                if (!cur.isPlaying) {
                    _state.value = cur.copy(isPlaying = true)
                    _uiEffects.tryEmit(AudiobookUiEffect.PlayPlayback)
                }
            }

            AudiobookCommand.Pause -> {
                if (cur.isPlaying) {
                    _state.value = cur.copy(isPlaying = false)
                    _uiEffects.tryEmit(AudiobookUiEffect.PausePlayback)
                }
            }

            AudiobookCommand.TogglePlayPause -> {
                val nextPlaying = !cur.isPlaying
                _state.value = cur.copy(isPlaying = nextPlaying)
                _uiEffects.tryEmit(
                    if (nextPlaying) AudiobookUiEffect.PlayPlayback else AudiobookUiEffect.PausePlayback,
                )
            }

            AudiobookCommand.NextTrack -> {
                val nextIdx = (cur.currentTrackIndex + 1).coerceAtMost(lastIdx)
                if (nextIdx != cur.currentTrackIndex) {
                    switchTrackInternal(nextIdx, positionSeconds = 0.0, autoPlay = cur.isPlaying)
                }
            }

            AudiobookCommand.PrevTrack -> {
                if (cur.positionSeconds > 5.0) {
                    seekInternal(0.0)
                } else {
                    val prevIdx = (cur.currentTrackIndex - 1).coerceAtLeast(0)
                    switchTrackInternal(prevIdx, positionSeconds = 0.0, autoPlay = cur.isPlaying)
                }
            }

            is AudiobookCommand.GoToTrack -> {
                val targetIdx = command.trackIndex.coerceIn(0, lastIdx)
                switchTrackInternal(
                    targetTrackIndex = targetIdx,
                    positionSeconds = command.positionSeconds.coerceAtLeast(0.0),
                    autoPlay = command.autoPlay,
                )
            }

            is AudiobookCommand.GoToChapter -> {
                val chapter = publication.chapters.getOrNull(command.chapterIndex)
                if (chapter != null) {
                    val targetIdx = chapter.trackIndex.coerceIn(0, lastIdx)
                    switchTrackInternal(
                        targetTrackIndex = targetIdx,
                        positionSeconds = chapter.startSeconds.coerceAtLeast(0.0),
                        autoPlay = command.autoPlay,
                    )
                }
            }

            is AudiobookCommand.SeekTo -> {
                seekInternal(command.positionSeconds)
            }

            is AudiobookCommand.SeekBy -> {
                seekInternal(cur.positionSeconds + command.deltaSeconds)
            }

            is AudiobookCommand.UpdatePosition -> {
                if (command.trackDurationSeconds != null && command.trackDurationSeconds > 0.0) {
                    if (cur.currentTrackIndex in trackDurations.indices) {
                        trackDurations[cur.currentTrackIndex] = command.trackDurationSeconds
                    }
                }
                val safePos = clampPosition(cur.currentTrackIndex, command.positionSeconds)
                val updated = buildState(
                    trackIndex = cur.currentTrackIndex,
                    positionSeconds = safePos,
                    isPlaying = cur.isPlaying,
                    playbackRate = cur.playbackRate,
                    sleepTimer = cur.sleepTimerRemainingSeconds,
                    timestampEpochMs = command.timestampEpochMs,
                )
                _state.value = updated
                _events.tryEmit(
                    AudiobookEvent.ProgressUpdated(
                        documentId = documentId,
                        trackIndex = updated.currentTrackIndex,
                        positionSeconds = updated.positionSeconds,
                        totalPercentage = updated.totalPercentage,
                        timestampEpochMs = command.timestampEpochMs,
                    ),
                )
            }

            AudiobookCommand.TrackCompleted -> {
                if (cur.currentTrackIndex < lastIdx) {
                    switchTrackInternal(cur.currentTrackIndex + 1, positionSeconds = 0.0, autoPlay = true)
                } else {
                    _state.value = cur.copy(isPlaying = false)
                    _uiEffects.tryEmit(AudiobookUiEffect.PausePlayback)
                }
            }

            is AudiobookCommand.SetPlaybackRate -> {
                val clamped = command.rate.coerceIn(0.5f, 3.0f)
                _state.value = cur.copy(playbackRate = clamped)
                _events.tryEmit(AudiobookEvent.PlaybackRateChanged(clamped))
                _uiEffects.tryEmit(AudiobookUiEffect.UpdatePlaybackRate(clamped))
            }

            is AudiobookCommand.SetSleepTimer -> {
                val safeSec = command.durationSeconds?.coerceAtLeast(1)
                _state.value = cur.copy(sleepTimerRemainingSeconds = safeSec)
            }

            is AudiobookCommand.TickSleepTimer -> {
                val remaining = cur.sleepTimerRemainingSeconds
                if (remaining != null) {
                    val next = remaining - command.elapsedSeconds.coerceAtLeast(1)
                    if (next <= 0) {
                        _state.value = cur.copy(isPlaying = false, sleepTimerRemainingSeconds = null)
                        _uiEffects.tryEmit(AudiobookUiEffect.PausePlayback)
                        _uiEffects.tryEmit(AudiobookUiEffect.SleepTimerExpired)
                    } else {
                        _state.value = cur.copy(sleepTimerRemainingSeconds = next)
                    }
                }
            }

            is AudiobookCommand.AddBookmark -> {
                val defaultLabel = command.label?.trim()?.takeIf { it.isNotEmpty() }
                    ?: cur.currentTrack?.title
                    ?: "Faixa ${cur.currentTrackIndex + 1}"
                bookmarks.add(
                    AudioBookmark(
                        id = "abm-${cur.currentTrackIndex}-${cur.positionSeconds.toLong()}-${nextBookmarkSeq++}",
                        trackIndex = cur.currentTrackIndex,
                        positionSeconds = cur.positionSeconds,
                        label = defaultLabel,
                        createdAtEpochMs = command.timestampEpochMs,
                    ),
                )
                syncBookmarksAndEmit()
            }

            is AudiobookCommand.RemoveBookmark -> {
                if (bookmarks.removeAll { it.id == command.bookmarkId }) {
                    syncBookmarksAndEmit()
                }
            }

            is AudiobookCommand.GoToBookmark -> {
                val bm = bookmarks.firstOrNull { it.id == command.bookmarkId }
                if (bm != null) {
                    val targetIdx = bm.trackIndex.coerceIn(0, lastIdx)
                    switchTrackInternal(
                        targetTrackIndex = targetIdx,
                        positionSeconds = bm.positionSeconds.coerceAtLeast(0.0),
                        autoPlay = command.autoPlay,
                    )
                }
            }
        }

        return _state.value
    }

    /** Exporta o estado completo do audiobook (posição, velocidade e bookmarks) como JSON. */
    public fun exportAudiobookSnapshot(): String {
        val cur = _state.value
        val snapshot = AudiobookStateSnapshot(
            documentId = documentId,
            trackIndex = cur.currentTrackIndex,
            positionSeconds = cur.positionSeconds,
            playbackRate = cur.playbackRate,
            totalPercentage = cur.totalPercentage,
            bookmarks = bookmarks.toList(),
            updatedAtEpochMs = cur.lastUpdateEpochMs,
        )
        return json.encodeToString(AudiobookStateSnapshot.serializer(), snapshot)
    }

    /** Importa um snapshot de audiobook sobrescrevendo o estado local. */
    public fun importAudiobookSnapshot(payload: String): AudiobookState {
        val decoded = PayloadCodec.decodeIfNeeded(payload)
        val snap = json.decodeFromString(AudiobookStateSnapshot.serializer(), decoded)
        val lastIdx = (publication.tracks.size - 1).coerceAtLeast(0)
        bookmarks.clear()
        bookmarks.addAll(snap.bookmarks)
        nextBookmarkSeq = bookmarks.size + 1

        val targetTrack = snap.trackIndex.coerceIn(0, lastIdx)
        val targetPos = clampPosition(targetTrack, snap.positionSeconds)
        val updated = buildState(
            trackIndex = targetTrack,
            positionSeconds = targetPos,
            isPlaying = false,
            playbackRate = snap.playbackRate.coerceIn(0.5f, 3.0f),
            sleepTimer = null,
            timestampEpochMs = snap.updatedAtEpochMs,
        )
        _state.value = updated
        syncBookmarksAndEmit()
        return updated
    }

    /** Faz merge multi-dispositivo sem perda de bookmarks entre o estado local e um snapshot remoto. */
    public fun mergeAudiobookSnapshot(
        remotePayload: String,
        progressPolicy: ProgressMergePolicy = ProgressMergePolicy.NEWEST_TIMESTAMP,
    ): AudiobookMergeResult {
        val decoded = PayloadCodec.decodeIfNeeded(remotePayload)
        val remote = json.decodeFromString(AudiobookStateSnapshot.serializer(), decoded)
        val cur = _state.value

        var addedBookmarks = 0
        val existingIds = bookmarks.map { it.id }.toMutableSet()
        val existingPositions = bookmarks.map { it.trackIndex to it.positionSeconds.toLong() }.toMutableSet()
        for (bm in remote.bookmarks) {
            val posKey = bm.trackIndex to bm.positionSeconds.toLong()
            if (posKey !in existingPositions) {
                val finalId = if (bm.id in existingIds) {
                    "abm-${bm.trackIndex}-${bm.positionSeconds.toLong()}-${nextBookmarkSeq++}"
                } else {
                    bm.id
                }
                bookmarks.add(bm.copy(id = finalId))
                existingIds.add(finalId)
                existingPositions.add(posKey)
                addedBookmarks++
            }
        }
        if (addedBookmarks > 0) {
            nextBookmarkSeq = bookmarks.size + 1
        }

        val adoptRemote = when (progressPolicy) {
            ProgressMergePolicy.KEEP_LOCAL -> false
            ProgressMergePolicy.PREFER_REMOTE -> true
            ProgressMergePolicy.NEWEST_TIMESTAMP -> remote.updatedAtEpochMs > cur.lastUpdateEpochMs
            ProgressMergePolicy.FURTHEST_PROGRESS ->
                remote.trackIndex > cur.currentTrackIndex ||
                    (remote.trackIndex == cur.currentTrackIndex && remote.positionSeconds > cur.positionSeconds)
        }

        val lastIdx = (publication.tracks.size - 1).coerceAtLeast(0)
        val finalTrack = if (adoptRemote) remote.trackIndex.coerceIn(0, lastIdx) else cur.currentTrackIndex
        val finalPos = if (adoptRemote) clampPosition(finalTrack, remote.positionSeconds) else cur.positionSeconds
        val finalTs = if (adoptRemote) remote.updatedAtEpochMs else cur.lastUpdateEpochMs

        _state.value = buildState(
            trackIndex = finalTrack,
            positionSeconds = finalPos,
            isPlaying = cur.isPlaying,
            playbackRate = cur.playbackRate,
            sleepTimer = cur.sleepTimerRemainingSeconds,
            timestampEpochMs = finalTs,
        )
        if (addedBookmarks > 0) {
            syncBookmarksAndEmit()
        }
        return AudiobookMergeResult(
            progressUpdated = adoptRemote,
            addedBookmarks = addedBookmarks,
            policyApplied = progressPolicy,
        )
    }

    private fun switchTrackInternal(targetTrackIndex: Int, positionSeconds: Double, autoPlay: Boolean) {
        val cur = _state.value
        val safePos = clampPosition(targetTrackIndex, positionSeconds)
        val updated = buildState(
            trackIndex = targetTrackIndex,
            positionSeconds = safePos,
            isPlaying = autoPlay,
            playbackRate = cur.playbackRate,
            sleepTimer = cur.sleepTimerRemainingSeconds,
            timestampEpochMs = cur.lastUpdateEpochMs,
        )
        _state.value = updated
        val track = publication.tracks.getOrNull(targetTrackIndex)
        if (track != null) {
            _uiEffects.tryEmit(
                AudiobookUiEffect.LoadTrack(
                    trackIndex = targetTrackIndex,
                    track = track,
                    startPositionSeconds = safePos,
                    autoPlay = autoPlay,
                ),
            )
        }
        _events.tryEmit(
            AudiobookEvent.ProgressUpdated(
                documentId = documentId,
                trackIndex = updated.currentTrackIndex,
                positionSeconds = updated.positionSeconds,
                totalPercentage = updated.totalPercentage,
                timestampEpochMs = updated.lastUpdateEpochMs,
            ),
        )
    }

    private fun seekInternal(targetSeconds: Double) {
        val cur = _state.value
        val safePos = clampPosition(cur.currentTrackIndex, targetSeconds)
        val updated = buildState(
            trackIndex = cur.currentTrackIndex,
            positionSeconds = safePos,
            isPlaying = cur.isPlaying,
            playbackRate = cur.playbackRate,
            sleepTimer = cur.sleepTimerRemainingSeconds,
            timestampEpochMs = cur.lastUpdateEpochMs,
        )
        _state.value = updated
        _uiEffects.tryEmit(AudiobookUiEffect.SeekPlayback(safePos))
    }

    private fun clampPosition(trackIndex: Int, rawSeconds: Double): Double {
        val dur = trackDurations.getOrNull(trackIndex) ?: 0.0
        val nonNeg = rawSeconds.coerceAtLeast(0.0)
        return if (dur > 0.0) nonNeg.coerceAtMost(dur) else nonNeg
    }

    private fun buildState(
        trackIndex: Int,
        positionSeconds: Double,
        isPlaying: Boolean,
        playbackRate: Float,
        sleepTimer: Int?,
        timestampEpochMs: Long,
    ): AudiobookState {
        val trackDur = trackDurations.getOrNull(trackIndex) ?: 0.0
        val totalDur = trackDurations.sum()
        val elapsedBefore = (0 until trackIndex.coerceIn(0, trackDurations.size)).sumOf { trackDurations[it] }
        val globalPos = elapsedBefore + positionSeconds
        val pct = if (totalDur > 0.0) {
            ((globalPos / totalDur) * 100.0).coerceIn(0.0, 100.0).toFloat()
        } else if (publication.tracks.isNotEmpty()) {
            (((trackIndex + 1).toDouble() / publication.tracks.size.toDouble()) * 100.0)
                .coerceIn(0.0, 100.0)
                .toFloat()
        } else {
            0f
        }
        return AudiobookState(
            documentId = documentId,
            metadata = publication.metadata,
            tracks = publication.tracks,
            chapters = publication.chapters,
            currentTrackIndex = trackIndex,
            positionSeconds = positionSeconds,
            trackDurationSeconds = trackDur,
            totalDurationSeconds = totalDur,
            globalPositionSeconds = globalPos,
            totalPercentage = pct,
            isPlaying = isPlaying,
            playbackRate = playbackRate,
            sleepTimerRemainingSeconds = sleepTimer,
            bookmarks = bookmarks.toList(),
            lastUpdateEpochMs = timestampEpochMs,
        )
    }

    private fun syncBookmarksAndEmit() {
        val copy = bookmarks.toList()
        _state.value = _state.value.copy(bookmarks = copy)
        val rawJson = json.encodeToString(ListSerializer(AudioBookmark.serializer()), copy)
        _events.tryEmit(
            AudiobookEvent.BookmarksChanged(
                documentId = documentId,
                bookmarks = copy,
                serializedJson = rawJson,
            ),
        )
    }
}

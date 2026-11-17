package app.mocast.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.mocast.data.Cue
import app.mocast.data.Episode
import app.mocast.data.FeedRepository
import app.mocast.data.Show
import app.mocast.data.Store
import app.mocast.play.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface Screen {
    data object Home : Screen
    data class ShowDetail(val showId: String) : Screen
    data class Player(val showId: String, val episodeId: String) : Screen
}

enum class TranscriptStatus { Idle, Loading, Ready, Empty, Error }

data class UiState(
    val shows: List<Show> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val screen: Screen = Screen.Home,
    val currentShow: Show? = null,
    val currentEpisode: Episode? = null,
    val cues: List<Cue> = emptyList(),
    val transcriptStatus: TranscriptStatus = TranscriptStatus.Idle,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val busy: Boolean = false,
    val addDialog: Boolean = false,
    val error: String? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = FeedRepository(Store(app))
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var lastProgressSave = 0L

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveCurrentProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncDuration()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncDuration()
        }
    }

    init {
        _ui.update { it.copy(shows = repo.shows()) }
        viewModelScope.launch { connectPlayer() }
        ticker = viewModelScope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) {
                    _ui.update { it.copy(positionMs = c.currentPosition, durationMs = c.duration.coerceAtLeast(0)) }
                    val now = System.currentTimeMillis()
                    if (now - lastProgressSave > 10_000) {
                        lastProgressSave = now
                        saveCurrentProgress()
                    }
                }
            }
        }
    }

    private suspend fun connectPlayer() {
        val app = getApplication<Application>()
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        runCatching { future.await() }.onSuccess {
            controller = it
            it.addListener(playerListener)
            _ui.update { s -> s.copy(isPlaying = it.isPlaying, speed = it.playbackParameters.speed) }
        }
    }

    fun openAddDialog() = _ui.update { it.copy(addDialog = true, error = null) }

    fun dismissAddDialog() = _ui.update { it.copy(addDialog = false) }

    fun addSubscription(input: String) {
        if (input.isBlank()) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.subscribe(input) }
                .onSuccess { show ->
                    _ui.update { it.copy(shows = repo.shows(), busy = false, addDialog = false) }
                    openShow(show.id)
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, error = friendly(e)) }
                }
        }
    }

    fun refresh(showId: String) {
        val show = _ui.value.shows.firstOrNull { it.id == showId } ?: return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.refresh(show) }
                .onSuccess { updated ->
                    _ui.update {
                        it.copy(shows = repo.shows(), busy = false, episodes = repo.episodesOf(updated.id))
                    }
                }
                .onFailure { e -> _ui.update { it.copy(busy = false, error = friendly(e)) } }
        }
    }

    fun openShow(showId: String) {
        val show = _ui.value.shows.firstOrNull { it.id == showId } ?: return
        _ui.update {
            it.copy(
                screen = Screen.ShowDetail(showId),
                currentShow = show,
                episodes = repo.episodesOf(showId),
                error = null
            )
        }
    }

    fun openEpisode(episode: Episode) {
        if (episode.audioUrl.isBlank()) {
            _ui.update {
                it.copy(error = "这一集没有直接音频（YouTube 视频流在 0.02 支持）")
            }
            return
        }
        val show = _ui.value.shows.firstOrNull { it.id == episode.showId }
        val saved = repo.progressOf(episode.id)
        _ui.update {
            it.copy(
                screen = Screen.Player(episode.showId, episode.id),
                currentShow = show,
                currentEpisode = episode,
                cues = emptyList(),
                transcriptStatus = TranscriptStatus.Idle,
                positionMs = saved,
                durationMs = episode.durationSec * 1000L,
                error = null
            )
        }
        bindPlayer(episode, show, saved)
        loadTranscript(episode)
    }

    private fun bindPlayer(episode: Episode, show: Show?, startMs: Long) {
        val c = controller ?: return
        val art = episode.artwork.ifBlank { show?.artwork.orEmpty() }
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(show?.title.orEmpty())
            .apply { if (art.startsWith("http")) setArtworkUri(Uri.parse(art)) }
            .build()
        val item = MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(episode.audioUrl)
            .setMediaMetadata(metadata)
            .build()
        c.setMediaItem(item)
        c.prepare()
        if (startMs > 0) c.seekTo(startMs)
        c.play()
    }

    private fun loadTranscript(episode: Episode) {
        if (episode.transcriptUrl.isBlank()) {
            _ui.update { it.copy(transcriptStatus = TranscriptStatus.Empty) }
            return
        }
        _ui.update { it.copy(transcriptStatus = TranscriptStatus.Loading) }
        viewModelScope.launch {
            runCatching { repo.transcriptOf(episode) }
                .onSuccess { cues ->
                    _ui.update {
                        it.copy(
                            cues = cues,
                            transcriptStatus = if (cues.isEmpty()) TranscriptStatus.Empty else TranscriptStatus.Ready
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(transcriptStatus = TranscriptStatus.Error, error = friendly(e)) }
                }
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
        _ui.update { it.copy(positionMs = ms.coerceAtLeast(0)) }
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        val target = (c.currentPosition + deltaMs).coerceAtLeast(0)
        c.seekTo(target)
        _ui.update { it.copy(positionMs = target) }
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        _ui.update { it.copy(speed = speed) }
    }

    fun back() {
        when (val s = _ui.value.screen) {
            is Screen.Player -> {
                saveCurrentProgress()
                openShow(s.showId)
            }
            is Screen.ShowDetail -> _ui.update { it.copy(screen = Screen.Home, episodes = emptyList()) }
            Screen.Home -> Unit
        }
    }

    fun consumeError() = _ui.update { it.copy(error = null) }

    private fun saveCurrentProgress() {
        val episode = _ui.value.currentEpisode ?: return
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == episode.id) {
            repo.saveProgress(episode.id, c.currentPosition)
        }
    }

    private fun syncDuration() {
        val c = controller ?: return
        val d = c.duration
        if (d > 0) _ui.update { it.copy(durationMs = d) }
    }

    override fun onCleared() {
        saveCurrentProgress()
        ticker?.cancel()
        controller?.release()
        controller = null
        super.onCleared()
    }

    private fun friendly(e: Throwable): String {
        val message = e.message.orEmpty()
        return when {
            message.startsWith("HTTP 404") -> "链接打不开（404），检查地址是否正确"
            message.startsWith("HTTP 403") -> "对方拒绝了请求（403），可能需要代理"
            message.startsWith("HTTP 5") -> "对方服务器出错（$message），稍后再试"
            message.contains("Unable to resolve host") || message.contains("Failed to connect") ->
                "网络连不上，检查网络或代理"
            message.isBlank() -> "操作失败，请重试"
            else -> message
        }
    }
}

private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener({
        try {
            cont.resume(get())
        } catch (t: Throwable) {
            cont.resumeWithException(t)
        }
    }, MoreExecutors.directExecutor())
    cont.invokeOnCancellation { cancel(true) }
}

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
import app.mocast.data.DisplayMode
import app.mocast.data.Episode
import app.mocast.data.FeedRepository
import app.mocast.data.KeywordLevel
import app.mocast.data.Settings
import app.mocast.data.SettingsStore
import app.mocast.data.Show
import app.mocast.data.Store
import app.mocast.data.ThemeMode
import app.mocast.data.TranslationCache
import app.mocast.data.TranslationMode
import app.mocast.net.TranslationClient
import app.mocast.play.PlaybackService
import app.mocast.update.UpdateManager
import app.mocast.update.UpdateState
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
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
    data object Settings : Screen
    data object Translation : Screen
    data object About : Screen
    data class ShowDetail(val showId: String) : Screen
    data class Player(val showId: String, val episodeId: String) : Screen
}

enum class TranscriptStatus { Idle, Loading, Ready, Empty, Error }

enum class TranslationStatus { Idle, NoKey, Loading, Ready, Error }

data class UiState(
    val shows: List<Show> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val screen: Screen = Screen.Home,
    val currentShow: Show? = null,
    val currentEpisode: Episode? = null,
    val cues: List<Cue> = emptyList(),
    val translated: List<String> = emptyList(),
    val transcriptStatus: TranscriptStatus = TranscriptStatus.Idle,
    val translationStatus: TranslationStatus = TranslationStatus.Idle,
    val translationDone: Int = 0,
    val translationTotal: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val settings: Settings = Settings(),
    val update: UpdateState = UpdateState.Idle,
    val busy: Boolean = false,
    val addDialog: Boolean = false,
    val confirmDeleteShowId: String? = null,
    val error: String? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    private val repo = FeedRepository(store)
    private val settingsStore = SettingsStore(app)
    private val translationCache = TranslationCache(app)
    private val translator = TranslationClient()
    private val updateManager = UpdateManager(app, viewModelScope)

    private val _ui = MutableStateFlow(UiState(settings = settingsStore.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null
    private var translationJob: Job? = null
    private var lastProgressSave = 0L
    /** 当前已翻到第几条（分段翻译的队尾），0 表示还没开翻 */
    private var segmentEnd = 0

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveCurrentProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncDuration()
            if (playbackState == Player.STATE_ENDED) playNext()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncDuration()
        }
    }

    init {
        _ui.update { it.copy(shows = repo.shows()) }
        viewModelScope.launch { connectPlayer() }
        viewModelScope.launch {
            updateManager.state.collect { state -> _ui.update { it.copy(update = state) } }
        }
        ticker = viewModelScope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) {
                    _ui.update { it.copy(positionMs = c.currentPosition, durationMs = c.duration.coerceAtLeast(0)) }
                    maybeExtendSegment()
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

    fun openSettings() = _ui.update { it.copy(screen = Screen.Settings, error = null) }

    fun openTranslation() = _ui.update { it.copy(screen = Screen.Translation, error = null) }

    fun openAbout() = _ui.update { it.copy(screen = Screen.About, error = null) }

    fun saveSettings(settings: Settings) {
        settingsStore.save(settings)
        _ui.update { it.copy(settings = settings, screen = Screen.Settings) }
    }

    fun setThemeMode(mode: ThemeMode) {
        val updated = _ui.value.settings.copy(themeMode = mode)
        settingsStore.save(updated)
        _ui.update { it.copy(settings = updated) }
    }

    fun checkUpdate() = updateManager.check()

    fun downloadUpdate() = updateManager.download()

    fun installUpdate() = updateManager.install()

    fun canInstallUpdate(): Boolean = updateManager.canInstall()

    fun setDisplayMode(mode: DisplayMode) {
        val updated = _ui.value.settings.copy(displayMode = mode)
        settingsStore.save(updated)
        _ui.update { it.copy(settings = updated) }
    }

    fun addSubscription(input: String) {
        if (input.isBlank()) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.subscribe(input) }
                .onSuccess { show ->
                    _ui.update { it.copy(shows = repo.shows(), busy = false, addDialog = false) }
                    openShow(show.id)
                }
                .onFailure { e -> _ui.update { it.copy(busy = false, error = friendly(e)) } }
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

    fun requestDelete(showId: String) = _ui.update { it.copy(confirmDeleteShowId = showId) }

    fun cancelDelete() = _ui.update { it.copy(confirmDeleteShowId = null) }

    fun confirmDelete() {
        val showId = _ui.value.confirmDeleteShowId ?: return
        repo.removeShow(showId)
        _ui.update {
            it.copy(
                shows = repo.shows(),
                confirmDeleteShowId = null,
                screen = Screen.Home,
                episodes = emptyList(),
                currentShow = null
            )
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
            _ui.update { it.copy(error = "这一集没有直接音频（YouTube 视频流在 0.03 支持）") }
            return
        }
        translationJob?.cancel()
        segmentEnd = 0
        val show = _ui.value.shows.firstOrNull { it.id == episode.showId }
        val saved = repo.progressOf(episode.id)
        _ui.update {
            it.copy(
                screen = Screen.Player(episode.showId, episode.id),
                currentShow = show,
                currentEpisode = episode,
                cues = emptyList(),
                translated = emptyList(),
                transcriptStatus = TranscriptStatus.Idle,
                translationStatus = TranslationStatus.Idle,
                translationDone = 0,
                translationTotal = 0,
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
                    val settings = _ui.value.settings
                    val cached = translationCache.load(episode.id, settings.translationVariant)
                        ?.takeIf { it.size == cues.size && it.any { line -> line.isNotBlank() } }
                    _ui.update {
                        it.copy(
                            cues = cues,
                            transcriptStatus = if (cues.isEmpty()) TranscriptStatus.Empty else TranscriptStatus.Ready,
                            translated = cached.orEmpty(),
                            translationStatus = if (cached != null) TranslationStatus.Ready else TranslationStatus.Idle,
                            translationDone = cached?.count { line -> line.isNotBlank() } ?: 0,
                            translationTotal = cues.size
                        )
                    }
                }
                .onFailure { e ->
                    _ui.update { it.copy(transcriptStatus = TranscriptStatus.Error, error = friendly(e)) }
                }
        }
    }

    fun translateCurrent() {
        val episode = _ui.value.currentEpisode ?: return
        val cues = _ui.value.cues
        if (cues.isEmpty()) {
            _ui.update { it.copy(error = "这一集没有字幕，先翻译不了") }
            return
        }
        val settings = _ui.value.settings
        if (!settings.ready) {
            _ui.update { it.copy(translationStatus = TranslationStatus.NoKey, error = "先在设置里填 API Key") }
            return
        }
        // 正在翻译时再点一次 = 停（分段翻译随时可停）
        if (translationJob?.isActive == true) {
            translationJob?.cancel()
            _ui.update { it.copy(translationStatus = TranslationStatus.Ready) }
            return
        }
        val start = currentCueIndex(cues, _ui.value.positionMs).let { it - WINDOW_LEAD }.coerceAtLeast(0)
        translateSegment(episode, cues, settings, start)
    }

    /**
     * 只翻「从 start 开始的一段」（SEGMENT_SIZE 条），单条请求串行发送，
     * 一批回来就立刻渲染 + 落盘，播放推进到段尾再自动补下一段。
     */
    private fun translateSegment(episode: Episode, cues: List<Cue>, settings: Settings, from: Int) {
        val end = (from + SEGMENT_SIZE).coerceAtMost(cues.size)
        if (from >= end) return
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            _ui.update {
                it.copy(
                    translationStatus = TranslationStatus.Loading,
                    translationDone = 0,
                    translationTotal = end - from,
                    error = null
                )
            }
            val result: MutableList<String> = MutableList(cues.size) { "" }
            translationCache.load(episode.id, settings.translationVariant)
                ?.takeIf { it.size == cues.size }
                ?.forEachIndexed { i, line -> result[i] = line }

            val pending = (from until end).filter { result[it].isBlank() }
            // 生词标注返回短，一批可以多带几句，请求数再少三成
            val batch = if (settings.translationMode == TranslationMode.KEYWORD) KEYWORD_BATCH else BATCH
            var firstError: String? = null

            for (chunk in pending.chunked(batch)) {
                try {
                    val translated = translator.translate(
                        settings,
                        chunk.map { cues[it].text },
                        chunk.first() + 1
                    )
                    chunk.forEachIndexed { offset, index ->
                        val raw = translated.getOrElse(offset) { "" }
                        result[index] = if (settings.translationMode == TranslationMode.KEYWORD) {
                            keepWordsInCue(cues[index].text, raw)
                        } else raw
                    }
                    var segmentDone = 0
                    for (i in from until end) if (result[i].isNotBlank()) segmentDone++
                    _ui.update { it.copy(translated = result.toList(), translationDone = segmentDone) }
                    translationCache.save(episode.id, settings.translationVariant, result)
                    delay(REQUEST_GAP_MS)
                } catch (e: CancellationException) {
                    translationCache.save(episode.id, settings.translationVariant, result)
                    throw e
                } catch (e: Exception) {
                    firstError = firstError ?: friendly(e)
                }
            }

            var segmentDone = 0
            for (i in from until end) if (result[i].isNotBlank()) segmentDone++
            if (segmentDone == 0) {
                _ui.update {
                    it.copy(
                        translationStatus = TranslationStatus.Error,
                        error = firstError ?: "翻译失败，检查服务商配置"
                    )
                }
            } else {
                _ui.update {
                    it.copy(
                        translationStatus = TranslationStatus.Ready,
                        translationDone = segmentDone,
                        translated = result.toList(),
                        error = firstError?.let { e -> "这一段没翻完：$e" }
                    )
                }
            }
            segmentEnd = end
        }
    }

    /** 播放推进到段尾前 REMAIN_AHEAD 条时，自动往后续一段（可在设置里关掉） */
    private fun maybeExtendSegment() {
        val state = _ui.value
        if (!state.settings.autoExtend) return
        if (translationJob?.isActive == true) return
        val episode = state.currentEpisode ?: return
        if (state.translationStatus != TranslationStatus.Ready) return
        val cues = state.cues
        if (cues.isEmpty()) return
        val end = segmentEnd
        if (end <= 0 || end >= cues.size) return
        val index = currentCueIndex(cues, state.positionMs)
        when {
            index >= end ->
                // 跳得比已翻的更靠后，直接从当前位置重新开一段
                translateSegment(episode, cues, state.settings, (index - WINDOW_LEAD).coerceAtLeast(0))
            index >= end - REMAIN_AHEAD ->
                // 快到段尾，续下一段
                translateSegment(episode, cues, state.settings, end)
        }
    }

    private fun currentCueIndex(cues: List<Cue>, positionMs: Long): Int =
        cues.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    /**
     * 只保留本句字幕里真的出现过的词。
     * 模型经常把相邻句、同根词也标进来（本句没有 happens 也标 happens），
     * 这里按词首比对一下，对不上就丢掉，避免落在一句不相关的字幕上。
     */
    private fun keepWordsInCue(cueText: String, entry: String): String {
        if (entry.isBlank()) return ""
        val cue = cueText.lowercase().replace(Regex("[^a-z' ]"), " ")
        val kept = entry.split('；', ';').map { it.trim() }.filter { item ->
            if (item.isEmpty()) return@filter false
            val head = item.substringBefore(' ').trim().lowercase()
            if (head.isEmpty()) return@filter false
            // 模型有时会把「专有名词，不标注」这类说明写进释义，直接丢掉
            if (item.contains("不标注") || item.contains("专有名词")) return@filter false
            cue.contains(head) || cue.contains(head.trimEnd('s', 'd', 'e', 'r', 'y', 'n', 'g'))
        }
        return kept.joinToString("；")
    }

    fun setTranslationMode(mode: TranslationMode) {
        val updated = _ui.value.settings.copy(translationMode = mode)
        applyTranslationSettings(updated)
    }

    fun setKeywordLevel(level: KeywordLevel) {
        val updated = _ui.value.settings.copy(keywordLevel = level)
        applyTranslationSettings(updated)
    }

    fun setAutoExtend(enabled: Boolean) {
        val updated = _ui.value.settings.copy(autoExtend = enabled)
        settingsStore.save(updated)
        _ui.update { it.copy(settings = updated) }
    }

    /** 换模式/难度后译文互不通用，重新载入对应缓存 */
    private fun applyTranslationSettings(settings: Settings) {
        settingsStore.save(settings)
        translationJob?.cancel()
        segmentEnd = 0
        val episode = _ui.value.currentEpisode
        val cues = _ui.value.cues
        val cached = if (episode != null) {
            translationCache.load(episode.id, settings.translationVariant)
                ?.takeIf { it.size == cues.size }
        } else null
        _ui.update {
            it.copy(
                settings = settings,
                translated = cached.orEmpty(),
                translationStatus = if (cached != null) TranslationStatus.Ready else TranslationStatus.Idle,
                translationDone = cached?.count { line -> line.isNotBlank() } ?: 0,
                translationTotal = cues.size
            )
        }
    }

    private fun playNext() {
        val current = _ui.value.currentEpisode ?: return
        val episodes = _ui.value.episodes
        val index = episodes.indexOfFirst { it.id == current.id }
        if (index < 0) return
        val next = episodes.drop(index + 1).firstOrNull { it.audioUrl.isNotBlank() } ?: return
        openEpisode(next)
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
                translationJob?.cancel()
                openShow(s.showId)
            }
            is Screen.ShowDetail -> _ui.update { it.copy(screen = Screen.Home, episodes = emptyList()) }
            Screen.Translation, Screen.About ->
                _ui.update { it.copy(screen = Screen.Settings) }
            Screen.Settings -> _ui.update { it.copy(screen = Screen.Home) }
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
        translationJob?.cancel()
        controller?.release()
        controller = null
        super.onCleared()
    }

    private fun friendly(e: Throwable): String {
        val message = e.message.orEmpty()
        return when {
            message.startsWith("HTTP 404") -> "链接打不开（404），检查地址是否正确"
            message.startsWith("HTTP 403") -> "对方拒绝了请求（403），可能需要代理"
            message.startsWith("HTTP 401") -> "API Key 不对（401），去设置里改"
            message.startsWith("HTTP 5") -> "对方服务器出错（$message），稍后再试"
            message.contains("Unable to resolve host") || message.contains("Failed to connect") ->
                "网络连不上，检查网络或代理"
            message.isBlank() -> "操作失败，请重试"
            else -> message
        }
    }

    companion object {
        /** 一批请求多少条字幕（完整翻译） */
        private const val BATCH = 12
        /** 生词标注每批条数，返回短但要求逐条对齐，10 条更稳 */
        private const val KEYWORD_BATCH = 10
        /** 一次翻译覆盖多少条字幕（≈ 3~4 个请求），播完再续下一段 */
        private const val SEGMENT_SIZE = 45
        /** 从当前位置往前留几句上下文 */
        private const val WINDOW_LEAD = 3
        /** 离段尾还剩多少条就开始续下一段 */
        private const val REMAIN_AHEAD = 12
        /** 两批之间歇一下，避免把服务商打爆 */
        private const val REQUEST_GAP_MS = 300L
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

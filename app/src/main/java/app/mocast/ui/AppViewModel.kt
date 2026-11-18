package app.mocast.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.mocast.MoCastApp
import app.mocast.asr.AsrPhase
import app.mocast.asr.AsrService
import app.mocast.asr.TranscriptSource
import app.mocast.data.Cue
import app.mocast.data.DisplayMode
import app.mocast.data.Episode
import app.mocast.data.FeedRepository
import app.mocast.data.KeywordLevel
import app.mocast.data.Settings
import app.mocast.data.SettingsStore
import app.mocast.data.Show
import app.mocast.data.ThemeMode
import app.mocast.data.TranslationCache
import app.mocast.data.TranslationMode
import app.mocast.data.room.DownloadEntity
import app.mocast.data.room.RoomStore
import app.mocast.download.DownloadManager
import app.mocast.download.DownloadProgress
import app.mocast.download.DownloadState
import app.mocast.net.FeedResolver
import app.mocast.net.Http
import app.mocast.net.Opml
import app.mocast.net.OpmlImportSummary
import app.mocast.net.ProxyConfig
import app.mocast.net.TranslationClient
import app.mocast.net.TranslationEstimate
import app.mocast.net.TranslationPricing
import app.mocast.play.PlaybackService
import app.mocast.update.UpdateManager
import app.mocast.update.UpdateState
import app.mocast.work.RefreshScheduler
import app.mocast.youtube.YouTubeStreamResolver
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
    val error: String? = null,
    // 0.4.0 YouTube：解析出的流只放内存，绝不写库
    val resolvedVideoUrl: String = "",
    val resolvedAudioUrl: String = "",
    val videoMode: Boolean = false,
    val videoIsDash: Boolean = false,
    val videoIsHls: Boolean = false,
    // 0.4.0 下载：Room 记录 + 实时进度（都按 episodeId 索引）
    val downloads: Map<String, DownloadEntity> = emptyMap(),
    val downloadProgress: Map<String, DownloadProgress> = emptyMap(),
    // 0.4.0 ASR：当前集的识别阶段与进度
    val asrPhase: AsrPhase = AsrPhase.IDLE,
    val asrProgress: Float = 0f,
    val asrError: String? = null,
    val transcriptSource: TranscriptSource = TranscriptSource.NONE,
    // 0.4.0 补项：播放队列（持久化在 Room，重启后还在）
    val queue: List<Episode> = emptyList(),
    val queueDialog: Boolean = false,
    // 0.4.0 补项：已听状态（每档节目的未听数，首页角标用）
    val unplayedCounts: Map<String, Int> = emptyMap(),
    /** 节目详情页“只看未听”开关（只影响展示，不改 episodes 列表本身）。 */
    val showUnplayedOnly: Boolean = false,
    // 0.4.0 补项：睡眠定时（剩余毫秒，null=没开；播完本集停另用 sleepEndOfEpisode）
    val sleepRemainingMs: Long? = null,
    /** 睡眠定时选中的档位（15/30/45/60），用于高亮 chip；播完本集停/没开时为 null。 */
    val sleepMinutes: Int? = null,
    val sleepEndOfEpisode: Boolean = false,
    // 0.4.0 P0 收尾：OPML 导入结果（弹窗展示，关掉即清）；导出文本（SAF 写盘前暂存）。
    val opmlImportSummary: OpmlImportSummary? = null,
    val opmlExportText: String? = null,
    // 0.4.0 P0 收尾：推荐订阅正在订阅的 feedUrl（该卡片按钮置灰防重复点）。
    val subscribingFeedUrl: String? = null,
    // 0.4.0 P0 收尾：翻译连接测试（进行中 / 结果文案 / 是否成功，null=没测过）。
    val testRunning: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    // 0.4.0 P0 收尾：当前集整集翻译费用预估（字幕到齐后算，换模式重算）。
    val translationEstimate: TranslationEstimate? = null
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    // 0.4.0：Room 单例与 MoCastApp 共用（首次构造会跑一次性旧数据迁移）。
    private val roomStore = (app as? MoCastApp)?.roomStore ?: RoomStore(app)
    private val repo = FeedRepository(roomStore, roomStore)
    private val downloadManager = (app as? MoCastApp)?.downloadManager
    private val settingsStore = SettingsStore(app)
    private val translationCache = TranslationCache(app)
    private val translator = TranslationClient()
    private val updateManager = UpdateManager(app, viewModelScope)

    private val _ui = MutableStateFlow(UiState(settings = settingsStore.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var controller: MediaController? = null
    /** 供播放页视频区用的播放器（MediaController 本身就是 Player）。 */
    val player: Player? get() = controller
    private var ticker: Job? = null
    private var translationJob: Job? = null
    private var lastProgressSave = 0L
    /** 当前已翻到第几条（分段翻译的队尾），0 表示还没开翻 */
    private var segmentEnd = 0
    /** 睡眠定时：倒计时任务 + 到期时间戳（毫秒 epoch），null=没开。 */
    private var sleepJob: Job? = null
    private var sleepDeadlineMs: Long? = null
    /** 本集已标过已听（避免 500ms 轮询里反复写库）；换集时清掉。 */
    private var markedPlayedEpisodeId: String? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveCurrentProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncDuration()
            if (playbackState == Player.STATE_ENDED) {
                // 睡眠定时“播完本集停”：到集尾只暂停、不接下一首，定时同时清掉。
                if (_ui.value.sleepEndOfEpisode) {
                    cancelSleep()
                    return
                }
                playNext()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncDuration()
        }
    }

    init {
        val initialSettings = settingsStore.load()
        _ui.update {
            it.copy(
                shows = repo.shows(),
                speed = initialSettings.playbackSpeed,
                settings = initialSettings
            )
        }
        refreshQueue()
        refreshUnplayedCounts()
        viewModelScope.launch { connectPlayer() }
        viewModelScope.launch {
            updateManager.state.collect { state -> _ui.update { it.copy(update = state) } }
        }
        refreshDownloads()
        viewModelScope.launch {
            downloadManager?.progressFlow?.collect { map ->
                _ui.update { it.copy(downloadProgress = map) }
                // 有单集跑到终态（完成/失败/取消）时，从 Room 刷新一次记录表。
                if (map.values.any {
                        it.state == DownloadState.COMPLETED ||
                            it.state == DownloadState.FAILED ||
                            it.state == DownloadState.CANCELLED
                    }
                ) refreshDownloads()
            }
        }
        viewModelScope.launch {
            AsrService.state.collect { job ->
                val current = _ui.value.currentEpisode
                if (job == null || current == null || job.episodeId != current.id) {
                    _ui.update { it.copy(asrPhase = AsrPhase.IDLE, asrProgress = 0f) }
                    return@collect
                }
                val progress = when (job.phase) {
                    AsrPhase.DOWNLOADING_MODEL -> job.downloadProgress
                    AsrPhase.RUNNING -> if (job.totalMs > 0) job.doneMs.toFloat() / job.totalMs else 0f
                    else -> _ui.value.asrProgress
                }
                _ui.update { it.copy(asrPhase = job.phase, asrProgress = progress, asrError = job.error) }
                // 识别完成且当前无字幕时重拉一次（命中刚存进 Room 的 ASR 缓存）。
                if (job.phase == AsrPhase.DONE && _ui.value.cues.isEmpty()) loadTranscript(current)
            }
        }
        ticker = viewModelScope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) {
                    _ui.update { it.copy(positionMs = c.currentPosition, durationMs = c.duration.coerceAtLeast(0)) }
                    maybeExtendSegment()
                    maybeMarkPlayed()
                    val now = System.currentTimeMillis()
                    if (now - lastProgressSave > 10_000) {
                        lastProgressSave = now
                        saveCurrentProgress()
                    }
                }
                // 睡眠定时倒计时显示（暂停时也继续走，关掉/到点才停）。
                updateSleepRemaining()
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
            // 倍速记忆（0.4.0 补项）：连接上就按保存的倍速设，不再每次回到 1 倍。
            val savedSpeed = _ui.value.settings.playbackSpeed
            runCatching { it.setPlaybackSpeed(savedSpeed) }
            _ui.update { s -> s.copy(isPlaying = it.isPlaying, speed = savedSpeed) }
        }
    }

    fun openAddDialog() = _ui.update { it.copy(addDialog = true, error = null) }

    fun dismissAddDialog() = _ui.update { it.copy(addDialog = false) }

    fun openSettings() = _ui.update { it.copy(screen = Screen.Settings, error = null) }

    fun openTranslation() = _ui.update { it.copy(screen = Screen.Translation, error = null) }

    fun openAbout() = _ui.update { it.copy(screen = Screen.About, error = null) }

    fun saveSettings(settings: Settings) {
        settingsStore.save(settings)
        // 代理改了立即重建 OkHttp 客户端，后续 RSS/翻译/下载都走新代理。
        Http.configure(ProxyConfig.fromSettings(settings))
        // 自动刷新开关改了立即重排/取消周期任务。
        RefreshScheduler.schedule(getApplication(), settings)
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
                    refreshUnplayedCounts()
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
                    refreshUnplayedCounts()
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
        translationJob?.cancel()
        segmentEnd = 0
        markedPlayedEpisodeId = null
        // 从队列里点的单集就是“正在播的”，播了就出队（队列弹窗点播、播完自动接队首都走这里）。
        if (_ui.value.queue.any { it.id == episode.id }) removeFromQueue(episode.id)
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
                resolvedVideoUrl = "",
                resolvedAudioUrl = "",
                videoMode = false,
                videoIsDash = false,
                videoIsHls = false,
                asrPhase = AsrPhase.IDLE,
                asrProgress = 0f,
                asrError = null,
                transcriptSource = TranscriptSource.NONE,
                error = null
            )
        }
        if (episode.audioUrl.isNotBlank()) {
            bindPlayer(episode, show, saved, playUrl = episode.audioUrl, mimeType = null, autoplay = true)
            loadTranscript(episode)
            return
        }
        // 音频直链为空：目前只有 YouTube 订阅的 RSS 不带 enclosure。
        // 不是 YouTube 的节目走到这里就是脏数据，直接报错，不静默。
        if (show != null && show.source != FeedResolver.SOURCE_YOUTUBE) {
            _ui.update { it.copy(error = "这一集没有直接音频") }
            return
        }
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { YouTubeStreamResolver.resolve(episode.pageUrl.ifBlank { episode.id }) }
                .onSuccess { streams ->
                    _ui.update {
                        it.copy(
                            busy = false,
                            resolvedVideoUrl = streams.videoUrl,
                            resolvedAudioUrl = streams.audioUrl,
                            videoMode = streams.isVideo,
                            videoIsDash = streams.videoIsDash,
                            videoIsHls = streams.videoIsHls
                        )
                    }
                    val playUrl = if (streams.isVideo) streams.videoUrl else streams.audioUrl
                    val mimeType = when {
                        streams.videoIsDash -> MimeTypes.APPLICATION_MPD
                        streams.videoIsHls -> MimeTypes.APPLICATION_M3U8
                        else -> null
                    }
                    bindPlayer(episode, show, saved, playUrl = playUrl, mimeType = mimeType, autoplay = true)
                    loadTranscript(episode)
                }
                .onFailure { e ->
                    _ui.update { it.copy(busy = false, error = friendly(e)) }
                }
        }
    }

    /**
     * 迷你播放条点开：只切回播放页，不重新绑定、不从头播、不清字幕。
     * （openEpisode 是“换一集”，这里是“回到正在播的那一集”。）
     */
    fun openPlayer() {
        val episode = _ui.value.currentEpisode ?: return
        _ui.update { it.copy(screen = Screen.Player(episode.showId, episode.id)) }
    }

    /** 播放页音频/视频模式切换（仅 YouTube 单集有视频流时可用）。 */
    fun toggleVideoMode() {
        val state = _ui.value
        val episode = state.currentEpisode ?: return
        val toVideo = !state.videoMode
        val playUrl = if (toVideo) state.resolvedVideoUrl
        else state.resolvedAudioUrl.ifBlank { episode.audioUrl }
        if (playUrl.isBlank()) {
            _ui.update { it.copy(error = "没有可用的${if (toVideo) "视频" else "音频"}流") }
            return
        }
        val mimeType = if (toVideo) when {
            state.videoIsDash -> MimeTypes.APPLICATION_MPD
            state.videoIsHls -> MimeTypes.APPLICATION_M3U8
            else -> null
        } else null
        _ui.update { it.copy(videoMode = toVideo) }
        // 从当前位置续播，不从头开始。
        bindPlayer(episode, state.currentShow, state.positionMs, playUrl = playUrl, mimeType = mimeType, autoplay = true)
    }

    private fun bindPlayer(
        episode: Episode,
        show: Show?,
        startMs: Long,
        playUrl: String,
        mimeType: String?,
        autoplay: Boolean
    ) {
        val c = controller ?: return
        val art = episode.artwork.ifBlank { show?.artwork.orEmpty() }
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(show?.title.orEmpty())
            .apply { if (art.startsWith("http")) setArtworkUri(Uri.parse(art)) }
            .build()
        // 音频模式优先播本地已下载文件（视频模式走解析出的在线流，不走本地）。
        val local = if (mimeType == null) downloadManager?.downloadedFile(episode.id, episode.audioUrl) else null
        val uri = local?.let { Uri.fromFile(it) } ?: Uri.parse(playUrl)
        val item = MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(uri)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(metadata)
            .build()
        c.setMediaItem(item)
        c.prepare()
        if (startMs > 0) c.seekTo(startMs)
        if (autoplay) c.play()
    }

    private fun loadTranscript(episode: Episode) {
        _ui.update { it.copy(transcriptStatus = TranscriptStatus.Loading) }
        viewModelScope.launch {
            runCatching { repo.transcriptOf(episode) }
                .onSuccess { result ->
                    val settings = _ui.value.settings
                    val cached = translationCache.load(episode.id, settings.translationVariant)
                        ?.takeIf { it.size == result.cues.size && it.any { line -> line.isNotBlank() } }
                    _ui.update {
                        it.copy(
                            cues = result.cues,
                            transcriptSource = result.source,
                            transcriptStatus = if (result.cues.isEmpty()) TranscriptStatus.Empty else TranscriptStatus.Ready,
                            translated = cached.orEmpty(),
                            translationStatus = if (cached != null) TranslationStatus.Ready else TranslationStatus.Idle,
                            translationDone = cached?.count { line -> line.isNotBlank() } ?: 0,
                            translationTotal = result.cues.size,
                            translationEstimate = if (result.cues.isEmpty()) null
                            else TranslationPricing.estimate(settings, result.cues)
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
                translationTotal = cues.size,
                translationEstimate = if (cues.isEmpty()) null else TranslationPricing.estimate(settings, cues)
            )
        }
    }

    // ------------------------------------------------------------------
    // 0.4.0 P0 收尾：OPML 导入 / 导出、推荐订阅、连接测试
    // ------------------------------------------------------------------

    /** OPML 导入：解析后逐个走 repo.subscribe（和粘贴链接同一条路）。 */
    fun importOpmlUri(uri: Uri) {
        viewModelScope.launch {
            val xml = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            }.getOrDefault("")
            importOpml(xml)
        }
    }

    fun importOpml(xml: String) {
        val feeds = try {
            Opml.parse(xml)
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: "OPML 解析失败") }
            return
        }
        viewModelScope.launch {
            val existing = repo.shows().map { it.feedUrl.trim() }.toSet()
            var added = 0
            var skipped = 0
            val failures = ArrayList<String>()
            for (feed in feeds) {
                if (feed.feedUrl.trim() in existing) {
                    skipped++
                    continue
                }
                runCatching { repo.subscribe(feed.feedUrl) }
                    .onSuccess { added++ }
                    .onFailure { e ->
                        if (failures.size < 10) failures.add("${feed.title}：${friendly(e)}")
                    }
            }
            val failed = feeds.size - added - skipped
            _ui.update {
                it.copy(
                    shows = repo.shows(),
                    opmlImportSummary = OpmlImportSummary(added, skipped, failed, failures)
                )
            }
            refreshUnplayedCounts()
        }
    }

    fun dismissOpmlSummary() = _ui.update { it.copy(opmlImportSummary = null) }

    /** 导出：先备好 OPML 文本，AppRoot 再用 SAF CreateDocument 让用户选保存位置。 */
    fun prepareOpmlExport() {
        val shows = repo.shows()
        if (shows.isEmpty()) {
            _ui.update { it.copy(error = "还没有订阅，没什么可导出的") }
            return
        }
        _ui.update {
            it.copy(opmlExportText = Opml.build(shows.map { s -> app.mocast.net.OpmlFeed(s.title, s.feedUrl) }))
        }
    }

    /** SAF 回调：把备好的 OPML 文本写进用户选的文件，写完清掉暂存。 */
    fun writeOpmlExport(uri: Uri) {
        val text = _ui.value.opmlExportText ?: return
        viewModelScope.launch {
            runCatching {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("打不开这个文件")
            }.onSuccess {
                _ui.update { it.copy(opmlExportText = null) }
            }.onFailure { e ->
                _ui.update { it.copy(opmlExportText = null, error = "导出失败：${friendly(e)}") }
            }
        }
    }

    fun consumeOpmlExport() = _ui.update { it.copy(opmlExportText = null) }

    /** 推荐订阅：点一个订阅一个，订阅完留在当前页（不像粘贴订阅那样跳进节目页）。 */
    fun subscribeRecommended(feedUrl: String) {
        val url = feedUrl.trim()
        if (url.isBlank()) return
        if (repo.shows().any { it.feedUrl.trim() == url }) return
        if (_ui.value.subscribingFeedUrl == url) return
        _ui.update { it.copy(subscribingFeedUrl = url, error = null) }
        viewModelScope.launch {
            runCatching { repo.subscribe(url) }
                .onSuccess {
                    _ui.update { it.copy(shows = repo.shows(), subscribingFeedUrl = null) }
                    refreshUnplayedCounts()
                }
                .onFailure { e ->
                    _ui.update { it.copy(subscribingFeedUrl = null, error = friendly(e)) }
                }
        }
    }

    /** 翻译连接测试：测的是调用方传进来的设置（翻译页正在编辑、还没保存的值），不保存、不写缓存。 */
    fun testConnection(settings: Settings) {
        if (_ui.value.testRunning) return
        _ui.update { it.copy(testRunning = true, testResult = null, testOk = null) }
        viewModelScope.launch {
            val result = translator.testConnection(settings)
            _ui.update { it.copy(testRunning = false, testResult = result.message, testOk = result.ok) }
        }
    }

    fun clearTestResult() = _ui.update { it.copy(testResult = null, testOk = null) }

    private fun playNext() {
        val current = _ui.value.currentEpisode ?: return
        // 播完先标已听（STATE_ENDED 时进度已到 100%，不依赖 95% 轮询）。
        markPlayedInternal(current.id)
        // 1. 队列优先：队首就是下一首，出队后直接播。
        val queued = _ui.value.queue.firstOrNull()
        if (queued != null) {
            removeFromQueue(queued.id)
            openEpisode(queued)
            return
        }
        // 2. 队列空了，按原逻辑自动接同一节目下一集。
        val episodes = _ui.value.episodes
        val index = episodes.indexOfFirst { it.id == current.id }
        if (index < 0) return
        // YouTube 单集 audioUrl 为空，但 openEpisode 会现场解析流，所以也算可播。
        val isYouTube = _ui.value.currentShow?.source == FeedResolver.SOURCE_YOUTUBE
        val next = episodes.drop(index + 1)
            .firstOrNull { it.audioUrl.isNotBlank() || isYouTube } ?: return
        openEpisode(next)
    }

    // ------------------------------------------------------------------
    // 0.4.0 下载
    // ------------------------------------------------------------------

    fun downloadEpisode(episode: Episode) {
        val manager = downloadManager
        if (manager == null) {
            _ui.update { it.copy(error = "下载服务未初始化") }
            return
        }
        if (episode.audioUrl.isBlank()) {
            _ui.update { it.copy(error = "YouTube 单集暂不支持下载（只有在线流，没有直接音频地址）") }
            return
        }
        manager.enqueue(episode)
        refreshDownloads()
    }

    fun cancelDownload(episodeId: String) {
        downloadManager?.cancel(episodeId)
        refreshDownloads()
    }

    fun deleteDownload(episodeId: String) {
        downloadManager?.delete(episodeId)
        refreshDownloads()
    }

    private fun refreshDownloads() {
        viewModelScope.launch {
            val list = runCatching { roomStore.listDownloads() }.getOrDefault(emptyList())
            _ui.update { it.copy(downloads = list.associateBy { d -> d.episodeId }) }
        }
    }

    // ------------------------------------------------------------------
    // 0.4.0 补项：播放队列（持久化在 Room）
    // ------------------------------------------------------------------

    private fun refreshQueue() {
        viewModelScope.launch {
            val list = runCatching { roomStore.listQueue() }.getOrDefault(emptyList())
            _ui.update { it.copy(queue = list) }
        }
    }

    /** 单集行“稍后听”：已在队列返回 false，由调用方（UI）显示“已在队列”。 */
    fun enqueueEpisode(episode: Episode) {
        viewModelScope.launch {
            val added = runCatching { roomStore.enqueueEpisode(episode) }.getOrDefault(false)
            if (!added) {
                _ui.update { it.copy(error = "已经在队列里了") }
                return@launch
            }
            refreshQueue()
        }
    }

    fun removeFromQueue(episodeId: String) {
        viewModelScope.launch {
            runCatching { roomStore.removeFromQueue(episodeId) }
            refreshQueue()
        }
    }

    /** 队列里上移/下移一位（到顶/到底时不动）。 */
    fun moveQueue(episodeId: String, up: Boolean) {
        val queue = _ui.value.queue
        val from = queue.indexOfFirst { it.id == episodeId }
        if (from < 0) return
        val to = if (up) from - 1 else from + 1
        if (to < 0 || to >= queue.size) return
        viewModelScope.launch {
            runCatching { roomStore.moveQueue(episodeId, to) }
            refreshQueue()
        }
    }

    fun clearQueue() {
        viewModelScope.launch {
            runCatching { roomStore.clearQueue() }
            refreshQueue()
        }
    }

    fun openQueueDialog() = _ui.update { it.copy(queueDialog = true) }

    fun dismissQueueDialog() = _ui.update { it.copy(queueDialog = false) }

    // ------------------------------------------------------------------
    // 0.4.0 补项：已听状态 + 未听角标
    // ------------------------------------------------------------------

    private fun refreshUnplayedCounts() {
        viewModelScope.launch {
            val counts = runCatching { repo.countUnplayedByShow() }.getOrDefault(emptyMap())
            _ui.update { it.copy(unplayedCounts = counts) }
        }
    }

    fun toggleUnplayedOnly() {
        _ui.update { it.copy(showUnplayedOnly = !it.showUnplayedOnly) }
    }

    /** 单集行手动标已听/未听（切换）；自动标已听走 markPlayedInternal。 */
    fun togglePlayed(episode: Episode) {
        setPlayed(episode, !episode.played)
    }

    fun setPlayed(episode: Episode, played: Boolean) {
        viewModelScope.launch {
            runCatching { repo.markPlayed(episode.id, played) }
            if (episode.id == markedPlayedEpisodeId && !played) markedPlayedEpisodeId = null
            _ui.update { state ->
                state.copy(
                    episodes = state.episodes.map {
                        if (it.id == episode.id) it.copy(played = played, playedAt = if (played) System.currentTimeMillis() else 0L) else it
                    }
                )
            }
            refreshUnplayedCounts()
        }
    }

    /** 进度 >=95% 时标已听（500ms 轮询里调）；每集只写一次库。 */
    private fun maybeMarkPlayed() {
        val state = _ui.value
        val episode = state.currentEpisode ?: return
        if (episode.played || markedPlayedEpisodeId == episode.id) return
        if (state.durationMs <= 0 || state.positionMs <= 0) return
        if (state.positionMs * 100L / state.durationMs >= PLAYED_THRESHOLD_PERCENT) {
            markedPlayedEpisodeId = episode.id
            markPlayedInternal(episode.id)
        }
    }

    /** 写库 + 同步 UiState 里的 episodes 列表（不刷新整档，不动播放状态）。 */
    private fun markPlayedInternal(episodeId: String) {
        viewModelScope.launch {
            runCatching { repo.markPlayed(episodeId, true) }
            _ui.update { state ->
                state.copy(
                    episodes = state.episodes.map {
                        if (it.id == episodeId) it.copy(played = true, playedAt = System.currentTimeMillis()) else it
                    }
                )
            }
            refreshUnplayedCounts()
        }
    }

    // ------------------------------------------------------------------
    // 0.4.0 补项：睡眠定时
    // ------------------------------------------------------------------

    /** 倒计时睡眠：15/30/45/60 分钟，到点暂停播放（不杀进程、不清进度）。 */
    fun setSleepMinutes(minutes: Int) {
        cancelSleepJob()
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        sleepDeadlineMs = deadline
        _ui.update { it.copy(sleepRemainingMs = minutes * 60_000L, sleepMinutes = minutes, sleepEndOfEpisode = false) }
        sleepJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            saveCurrentProgress()
            sleepDeadlineMs = null
            _ui.update { it.copy(sleepRemainingMs = null, sleepMinutes = null, sleepEndOfEpisode = false) }
        }
    }

    /** “播完本集停”：集尾 STATE_ENDED 时暂停、不接下一首（见 playerListener）。 */
    fun setSleepEndOfEpisode() {
        cancelSleepJob()
        sleepDeadlineMs = null
        _ui.update { it.copy(sleepRemainingMs = null, sleepMinutes = null, sleepEndOfEpisode = true) }
    }

    fun cancelSleep() {
        cancelSleepJob()
        sleepDeadlineMs = null
        _ui.update { it.copy(sleepRemainingMs = null, sleepMinutes = null, sleepEndOfEpisode = false) }
    }

    private fun cancelSleepJob() {
        sleepJob?.cancel()
        sleepJob = null
    }

    /** 500ms 轮询里刷新倒计时显示；到点由 sleepJob 本身处理暂停。 */
    private fun updateSleepRemaining() {
        val deadline = sleepDeadlineMs ?: return
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) return
        _ui.update { it.copy(sleepRemainingMs = remaining) }
    }

    // ------------------------------------------------------------------
    // 0.4.0 本机 ASR
    // ------------------------------------------------------------------

    /** 播放页“生成 AI 字幕”入口：前台 Service 里跑，可取消、可断点续跑。 */
    fun startAsr() {
        val episode = _ui.value.currentEpisode ?: return
        AsrService.start(getApplication(), episode.id)
    }

    fun cancelAsr() {
        AsrService.cancel(getApplication())
    }

    fun setAsrModel(model: String) {
        val updated = _ui.value.settings.copy(asrModel = model)
        settingsStore.save(updated)
        _ui.update { it.copy(settings = updated) }
    }

    fun setAsrWifiOnly(enabled: Boolean) {
        val updated = _ui.value.settings.copy(asrWifiOnly = enabled)
        settingsStore.save(updated)
        _ui.update { it.copy(settings = updated) }
    }

    fun setAsrAuto(enabled: Boolean) {
        val updated = _ui.value.settings.copy(asrAuto = enabled)
        settingsStore.save(updated)
        _ui.update { it.copy(settings = updated) }
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
        val clamped = speed.coerceIn(0.5f, 3f)
        controller?.setPlaybackSpeed(clamped)
        // 倍速记忆（0.4.0 补项）：存进 Settings，下次打开沿用。
        val updated = _ui.value.settings.copy(playbackSpeed = clamped)
        settingsStore.save(updated)
        _ui.update { it.copy(speed = clamped, settings = updated) }
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
        cancelSleepJob()
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
        /** 进度到这个百分比即算已听（0.4.0 补项）。 */
        private const val PLAYED_THRESHOLD_PERCENT = 95L
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

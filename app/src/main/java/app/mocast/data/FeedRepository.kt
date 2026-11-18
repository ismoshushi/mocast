package app.mocast.data

import app.mocast.asr.AsrTranscriptStore
import app.mocast.asr.TranscriptSource
import app.mocast.data.room.RoomStore
import app.mocast.net.FeedResolver
import app.mocast.net.Http
import app.mocast.net.RssParser
import app.mocast.net.TranscriptParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FeedRepository(private val store: RoomStore, private val asrStore: AsrTranscriptStore? = null) {

    suspend fun subscribe(input: String): Show = withContext(Dispatchers.IO) {
        val resolved = FeedResolver.resolve(input)
        val showId = RssParser.stableId("show", resolved.feedUrl)
        val xml = Http.get(resolved.feedUrl, "application/rss+xml, application/atom+xml, application/xml, text/xml")
        val parsed = RssParser.parse(xml, showId, resolved.source)
        val show = Show(
            id = showId,
            title = parsed.title,
            author = parsed.author,
            artwork = parsed.artwork,
            description = parsed.description,
            feedUrl = resolved.feedUrl,
            source = resolved.source,
            lastUpdated = System.currentTimeMillis(),
            episodeCount = parsed.episodes.size
        )
        store.upsertShow(show)
        store.saveEpisodes(showId, parsed.episodes)
        show
    }

    suspend fun refresh(show: Show): Show = withContext(Dispatchers.IO) {
        val xml = Http.get(show.feedUrl, "application/rss+xml, application/atom+xml, application/xml, text/xml")
        val parsed = RssParser.parse(xml, show.id, show.source)
        val updated = show.copy(
            title = parsed.title.ifBlank { show.title },
            author = parsed.author.ifBlank { show.author },
            artwork = parsed.artwork.ifBlank { show.artwork },
            description = parsed.description.ifBlank { show.description },
            lastUpdated = System.currentTimeMillis(),
            episodeCount = parsed.episodes.size
        )
        store.upsertShow(updated)
        store.saveEpisodes(show.id, parsed.episodes)
        updated
    }

    data class TranscriptResult(val cues: List<Cue>, val source: TranscriptSource)

    suspend fun transcriptOf(episode: Episode): TranscriptResult = withContext(Dispatchers.IO) {
        // 1. 官方字幕优先。
        if (episode.transcriptUrl.isNotBlank()) {
            val cues = runCatching {
                TranscriptParser.parse(Http.get(episode.transcriptUrl))
            }.getOrDefault(emptyList())
            if (cues.isNotEmpty()) return@withContext TranscriptResult(cues, TranscriptSource.OFFICIAL)
            // 官方拉取失败或解析为空：不要直接报错，往下看本地 ASR 缓存。
        }
        // 2. 没有官方字幕（或官方为空）时读 Room ASR 缓存。
        val cached = runCatching { asrStore?.loadAsrTranscript(episode.id) }.getOrNull()
        if (!cached.isNullOrEmpty()) {
            return@withContext TranscriptResult(cached, TranscriptSource.ASR)
        }
        // 3. 都没有才返回空列表。
        TranscriptResult(emptyList(), TranscriptSource.NONE)
    }

    fun episodesOf(showId: String): List<Episode> = store.loadEpisodes(showId)

    fun shows(): List<Show> = store.listShows()

    fun removeShow(showId: String) = store.removeShow(showId)

    fun progressOf(episodeId: String): Long = store.progressOf(episodeId)

    fun saveProgress(episodeId: String, positionMs: Long) = store.saveProgress(episodeId, positionMs)

    /** 标已听/未听（0.4.0 补项）：直接写 Room，不经过 RSS。 */
    fun markPlayed(episodeId: String, played: Boolean) = store.setPlayed(episodeId, played)

    /** 每档节目的未听数（首页角标用）。 */
    fun countUnplayedByShow(): Map<String, Int> = store.countUnplayedByShow()

    /**
     * 后台刷新全部订阅（0.4.0 补项，自动刷新 Worker 用）：
     * 逐档跑，单档失败只跳过该档、不中断其余档；调用方（Worker）保证在 IO 线程。
     * 返回成功刷新的档数。
     */
    suspend fun refreshAll(): Int = withContext(Dispatchers.IO) {
        var ok = 0
        for (show in shows()) {
            runCatching { refresh(show) }.onSuccess { ok++ }
        }
        ok
    }
}

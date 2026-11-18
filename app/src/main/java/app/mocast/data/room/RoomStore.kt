package app.mocast.data.room

import android.content.Context
import androidx.room.withTransaction
import app.mocast.asr.AsrTranscriptStore
import app.mocast.data.Cue
import app.mocast.data.Episode
import app.mocast.data.Show
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Room 版 Store（0.4.0 数据层）：旧 Store 的直接替换品。
 *
 * 用法（与旧 Store 一一对应）：
 * ```
 * // 旧：private val store = Store(app)
 * // 新：
 * private val store = RoomStore(app)
 * ```
 * FeedRepository 的构造参数类型同步由 Store 改为 RoomStore，除此之外
 * FeedRepository / AppViewModel 不用改任何调用（方法签名与旧 Store 相同）。
 *
 * 线程模型（重要）：
 * - 本类方法保持与旧 Store 一样的同步签名，内部用 runBlocking(Dispatchers.IO)
 *   桥接 suspend DAO。Room 的 suspend 查询走 Room 自有执行器，不会与
 *   runBlocking 死锁；但调用线程会被阻塞到查询完成。
 * - 因此调用方应尽量在 IO 线程调用（FeedRepository.subscribe/refresh 已是
 *   withContext(Dispatchers.IO)）。AppViewModel 里现在仍在主线程直接调的
 *   repo.shows() / episodesOf() / removeShow()（init、confirmDelete、openShow）
 *   是小数据量读/写，首次可接受；后续应改为 viewModelScope.launch(Dispatchers.IO)，
 *   那是调用方改造，不在本文件做。
 * - 绝不要在持有 Room 事务 / 协程锁的上下文里调本类方法（runBlocking 会占住线程）。
 */
class RoomStore : AsrTranscriptStore {

    private val db: MoCastDatabase

    /** 正常入口：传 Application/Context；内部经 MoCastDatabase.get() 拿单例（首次会跑迁移）。 */
    constructor(context: Context) {
        db = MoCastDatabase.get(context.applicationContext)
    }

    /** 测试/注入入口：直接传数据库（一般是 Room.inMemoryDatabaseBuilder 的实例）。 */
    constructor(database: MoCastDatabase) {
        db = database
    }

    // ------------------------------------------------------------------
    // 与旧 Store 完全相同的公开方法（签名、语义、默认值都不变）
    // ------------------------------------------------------------------

    /** 按 lastUpdated 倒序（DAO 的 ORDER BY 与旧 Store 的内存排序结果一致）。 */
    fun listShows(): List<Show> = io {
        db.showDao().getAll().map { it.toShow() }
    }

    /** 全量替换订阅列表（旧 Store.saveShows 语义：整表替换，一整个事务）。 */
    fun saveShows(shows: List<Show>) = io {
        db.showDao().replaceAll(shows.map(ShowEntity::from))
    }

    fun upsertShow(show: Show) = io {
        db.showDao().upsert(ShowEntity.from(show))
    }

    /**
     * 删节目 + 删其全部单集（同一事务）。
     * 旧行为对齐：进度记录不删（旧 Store 删节目也不清进度 JSON）。
     * 下载记录也不删：下载文件在磁盘上，删节目不应悄悄丢下载状态；
     * 下载功能方如需联动，用 removeDownload / DownloadDao.deleteByShow 自行处理。
     * 队列联动（0.4.0 补项）：该节目的单集从队列里清掉，否则队列会留死链。
     */
    fun removeShow(showId: String) = io {
        db.withTransaction {
            db.showDao().deleteById(showId)
            db.episodeDao().deleteByShow(showId)
            db.queueDao().deleteByShow(showId)
        }
    }

    /**
     * 替换某档节目的全部单集（旧 Store.saveEpisodes 语义：一整个事务）。
     * 空 id 的单集跳过（主键 "" 会互相覆盖）；showId 以传入参数为准，
     * 逐条强制覆盖，避免旧 JSON 里 showId 缺失/写错导致单集查不到。
     * 已听保护（0.4.0 补项）：RSS 刷新会把整档单集重写一遍，刷新前先读出
     * 本档已有的 played/playedAt，按 episodeId 贴回新行——否则用户标过
     * 的已听一刷新就全丢了。
     */
    fun saveEpisodes(showId: String, episodes: List<Episode>) = io {
        val playedById: Map<String, Pair<Boolean, Long>> =
            db.episodeDao().getByShow(showId).associate { it.id to (it.played to it.playedAt) }
        val entities = episodes
            .filter { it.id.isNotBlank() }
            .map { episode ->
                val saved = playedById[episode.id]
                val merged = if (saved != null) episode.copy(played = saved.first, playedAt = saved.second) else episode
                EpisodeEntity.from(merged.copy(showId = showId))
            }
        db.episodeDao().replaceForShow(showId, entities)
    }

    /** 按 publishedAt 倒序（旧文件是 RSS 顺序，一般即倒序；此处显式定序）。 */
    fun loadEpisodes(showId: String): List<Episode> = io {
        db.episodeDao().getByShow(showId).map { it.toEpisode() }
    }

    /** 单集按 id 取一行（下载完成钩子判断该集有无官方字幕用），无记录返回 null。 */
    fun getEpisode(episodeId: String): Episode? = io {
        db.episodeDao().getById(episodeId)?.toEpisode()
    }

    /** 空 episodeId 直接丢弃（与旧 Store.saveProgress 的守卫一致）。 */
    fun saveProgress(episodeId: String, positionMs: Long) {
        if (episodeId.isBlank()) return
        io {
            db.progressDao().upsert(
                ProgressEntity(
                    episodeId = episodeId,
                    positionMs = positionMs,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** 无记录返回 0L（与旧 Store.progressOf 一致）。 */
    fun progressOf(episodeId: String): Long = io {
        db.progressDao().getByEpisode(episodeId)?.positionMs ?: 0L
    }

    // ------------------------------------------------------------------
    // 已听（0.4.0 补项）
    // ------------------------------------------------------------------

    /** 标已听/未听；标已听时 playedAt=现在，标未听时 playedAt 清 0。 */
    fun setPlayed(episodeId: String, played: Boolean) {
        if (episodeId.isBlank()) return
        io {
            db.episodeDao().setPlayed(
                episodeId,
                played,
                if (played) System.currentTimeMillis() else 0L
            )
        }
    }

    /** 每档节目的未听数（首页角标用）。 */
    fun countUnplayedByShow(): Map<String, Int> = io {
        db.episodeDao().countUnplayedByShow().associate { it.showId to it.unplayed }
    }

    // ------------------------------------------------------------------
    // 播放队列（0.4.0 补项，持久化在 queue_items 表）
    // ------------------------------------------------------------------

    /**
     * 队列按 position 顺序返回 Episode；队列里指向已删单集的行会被顺手清掉
     * （单集删了、节目取消订阅后，队列不留死链）。
     */
    fun listQueue(): List<Episode> = io {
        val items = db.queueDao().getAll()
        if (items.isEmpty()) return@io emptyList()
        val episodes = items.mapNotNull { db.episodeDao().getById(it.episodeId)?.toEpisode() }
        val found = episodes.map { it.id }.toSet()
        val stale = items.filter { it.episodeId !in found }
        if (stale.isNotEmpty()) {
            stale.forEach { db.queueDao().deleteByEpisode(it.episodeId) }
        }
        episodes
    }

    fun queueIds(): Set<String> = io {
        db.queueDao().getAll().map { it.episodeId }.toSet()
    }

    /** 加入队尾；已在队列返回 false（调用方据此显示“已在队列”，不重复加）。 */
    fun enqueueEpisode(episode: Episode): Boolean = io {
        if (db.queueDao().getByEpisode(episode.id) != null) return@io false
        val next = (db.queueDao().maxPosition() ?: -1) + 1
        db.queueDao().upsert(
            QueueItemEntity(
                episodeId = episode.id,
                showId = episode.showId,
                position = next,
                addedAt = System.currentTimeMillis()
            )
        )
        true
    }

    fun removeFromQueue(episodeId: String) = io {
        db.queueDao().deleteByEpisode(episodeId)
    }

    /** 把某集移到目标下标（越界时夹到队首/队尾），其余项 position 重排。 */
    fun moveQueue(episodeId: String, toIndex: Int) = io {
        val items = db.queueDao().getAll().toMutableList()
        val from = items.indexOfFirst { it.episodeId == episodeId }
        if (from < 0) return@io
        val item = items.removeAt(from)
        val target = toIndex.coerceIn(0, items.size)
        items.add(target, item)
        db.queueDao().replaceAll(
            items.mapIndexed { index, it -> it.copy(position = index) }
        )
    }

    fun clearQueue() = io {
        db.queueDao().deleteAll()
    }

    // ------------------------------------------------------------------
    // 下载（DownloadEntity 即契约，不另造领域模型）
    // ------------------------------------------------------------------

    /** 新增或更新一条下载记录（state 取值见 DownloadState，非法值直接存、由调用方保证）。 */
    fun upsertDownload(download: DownloadEntity) = io {
        db.downloadDao().upsert(download)
    }

    fun getDownload(episodeId: String): DownloadEntity? = io {
        db.downloadDao().getByEpisode(episodeId)
    }

    /** 全部下载，按 createdAt 倒序。 */
    fun listDownloads(): List<DownloadEntity> = io {
        db.downloadDao().getAll()
    }

    /** 某档节目的下载，按 createdAt 倒序。 */
    fun listDownloadsByShow(showId: String): List<DownloadEntity> = io {
        db.downloadDao().getByShow(showId)
    }

    /** 按 state 筛选（如只列 DOWNLOADING/PAUSED 做断点续传扫描）。 */
    fun listDownloadsByState(state: String): List<DownloadEntity> = io {
        db.downloadDao().getByState(state)
    }

    fun removeDownload(episodeId: String) = io {
        db.downloadDao().deleteByEpisode(episodeId)
    }

    // ------------------------------------------------------------------
    // ASR 字幕（AsrTranscriptEntity + Cue JSON）
    // ------------------------------------------------------------------

    /**
     * 存一集的 ASR 字幕（同 episodeId 覆盖旧行；source 固定 "asr"）。
     * cues 为空也存一行空数组（表示“已跑过 ASR、确实无字幕”，与“没跑过”区分）。
     */
    override fun saveAsrTranscript(
        episodeId: String,
        model: String,
        language: String,
        cues: List<Cue>
    ) {
        if (episodeId.isBlank()) return
        io {
            db.asrTranscriptDao().upsert(
                AsrTranscriptEntity(
                    episodeId = episodeId,
                    model = model,
                    language = language,
                    source = "asr",
                    cuesJson = CueJson.encode(cues),
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * 取一集的 ASR 字幕；无记录返回 null（调用方以此区分“没跑过 ASR”）。
     * 有记录但 cuesJson 解析出空列表时返回空列表（不是 null）。
     */
    override fun loadAsrTranscript(episodeId: String): List<Cue>? = io {
        val entity = db.asrTranscriptDao().getByEpisode(episodeId) ?: return@io null
        CueJson.decode(entity.cuesJson)
    }

    /** 取 ASR 元数据（model / language / source / createdAt；不含解析，只取行）。 */
    fun getAsrMeta(episodeId: String): AsrTranscriptEntity? = io {
        db.asrTranscriptDao().getByEpisode(episodeId)
    }

    fun removeAsrTranscript(episodeId: String) = io {
        db.asrTranscriptDao().deleteByEpisode(episodeId)
    }

    // ------------------------------------------------------------------
    // 生词本（DB v3）
    // ------------------------------------------------------------------

    /**
     * 收一个词：词条不存在就建，然后按「同词同释义」去重插一条释义。
     * 返回 true 表示新加了一条释义，false 表示这个词/这个释义已经有了。
     */
    fun collectWord(
        word: String,
        pos: String,
        ipa: String,
        gloss: String,
        level: String,
        example: String,
        showId: String,
        episodeId: String,
        startMs: Long
    ): Boolean = io {
        val key = word.trim().lowercase()
        if (key.isBlank() || gloss.isBlank()) return@io false
        db.withTransaction {
            val existed = db.wordDao().getWord(key) != null
            if (!existed) {
                db.wordDao().insertWord(
                    WordEntity(word = key, level = level, addedAt = System.currentTimeMillis())
                )
            }
            val same = db.wordDao().countSense(key, gloss) > 0
            if (same) {
                false
            } else {
                db.wordDao().insertSense(
                    WordSenseEntity(
                        word = key,
                        pos = pos,
                        gloss = gloss,
                        ipa = ipa,
                        example = example,
                        showId = showId,
                        episodeId = episodeId,
                        startMs = startMs,
                        createdAt = System.currentTimeMillis()
                    )
                )
                true
            }
        }
    }

    /** 某一集里已经收过的词（播放页词片显示"已收"用）。 */
    fun collectedWordsInEpisode(episodeId: String): Set<String> = io {
        db.wordDao().getSensesByEpisode(episodeId).map { it.word }.toSet()
    }

    fun listWords(): List<WordEntity> = io { db.wordDao().getAllWords() }

    fun sensesOf(word: String): List<WordSenseEntity> = io {
        db.wordDao().getSenses(word.trim().lowercase())
    }

    fun setWordMastered(word: String, mastered: Boolean) = io {
        db.wordDao().setMastered(word.trim().lowercase(), mastered)
    }

    fun bumpWordReview(word: String) = io {
        db.wordDao().incrementReview(word.trim().lowercase())
    }

    /** 删词条（连它的全部释义一起删）。 */
    fun deleteWord(word: String) = io {
        val key = word.trim().lowercase()
        db.withTransaction {
            db.wordDao().deleteSensesOfWord(key)
            db.wordDao().deleteWord(key)
        }
    }

    fun removeSense(id: Long) = io { db.wordDao().deleteSense(id) }

    /**
     * 取消收藏某一集里的某个词：只删这集的释义；
     * 这个词在本集之外没有释义了，才把词条一起删掉。
     */
    fun uncollectWordInEpisode(word: String, episodeId: String) = io {
        val key = word.trim().lowercase()
        db.withTransaction {
            db.wordDao().deleteSensesOfWordInEpisode(key, episodeId)
            if (db.wordDao().countSenses(key) == 0) db.wordDao().deleteWord(key)
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 同步签名桥接 suspend DAO：调用线程阻塞到 IO 完成（见类注释的线程模型）。 */
    private fun <T> io(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }
}

/**
 * Cue 列表的 JSON 编解码（与 AsrTranscriptEntity.cuesJson 同格式）。
 *
 * 写格式（冻结）：JSONArray，每条 {"startMs":..,"endMs":..,"text":..}（毫秒 Long）。
 * 读格式（宽容）：优先读 startMs/endMs；读不到时兼容 TranscriptParser 的
 * JSON 段格式——startTime/endTime 或 start/end（秒，Double）+ body/text。
 * 注意：TranscriptParser.parseJson 本身只认秒级 startTime/start，不认 startMs；
 * 所以本格式不要直接喂给 TranscriptParser.parse()，走这里的 CueJson.decode()。
 * 单条坏数据只跳过该条，绝不让整集字幕解析失败。
 */
internal object CueJson {

    fun encode(cues: List<Cue>): String {
        val arr = JSONArray()
        cues.forEach { cue ->
            arr.put(
                JSONObject().apply {
                    put("startMs", cue.startMs)
                    put("endMs", cue.endMs)
                    put("text", cue.text)
                }
            )
        }
        return arr.toString()
    }

    fun decode(raw: String?): List<Cue> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<Cue>(arr.length())
            for (i in 0 until arr.length()) {
                runCatching {
                    val o = arr.getJSONObject(i)
                    val startMs = when {
                        o.has("startMs") -> o.optLong("startMs", -1L)
                        o.has("startTime") -> (o.optDouble("startTime", -1.0) * 1000).toLong()
                        o.has("start") -> (o.optDouble("start", -1.0) * 1000).toLong()
                        else -> -1L
                    }
                    val endMs = when {
                        o.has("endMs") -> o.optLong("endMs", -1L)
                        o.has("endTime") -> (o.optDouble("endTime", -1.0) * 1000).toLong()
                        o.has("end") -> (o.optDouble("end", -1.0) * 1000).toLong()
                        else -> -1L
                    }
                    val text = o.optString("text").ifEmpty { o.optString("body") }
                    if (startMs < 0 || text.isBlank()) return@runCatching
                    out.add(Cue(startMs, if (endMs < 0) startMs else endMs, text.trim()))
                }
            }
            out.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }
}

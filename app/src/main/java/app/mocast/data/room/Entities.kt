package app.mocast.data.room

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.mocast.data.Episode
import app.mocast.data.Show

/**
 * Room 实体（0.4.0 数据层，数据库 mocast.db，version=1）。
 *
 * 约定（冻结，不可自创命名）：
 * - 字段名用驼峰，与 app.mocast.data 的领域模型同名同义；Room 列名即字段名。
 * - 表名：shows / episodes / progress / downloads / asr_transcripts。
 * - 不设外键（ForeignKey）：订阅流程先写 Show 再写 Episode，但迁移/回滚时
 *   允许单集先于节目入库；一致性由 RoomStore 的事务方法保证，不靠 FK 级联。
 *   删除节目时 RoomStore 在同一事务里删节目 + 删单集（见 RoomStore.removeShow），
 *   进度与下载记录按旧 Store 行为保留（旧 Store 删节目也不清进度）。
 */

// ---------------------------------------------------------------------------
// Show
// ---------------------------------------------------------------------------

@Entity(tableName = "shows")
data class ShowEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val artwork: String,
    val description: String,
    val feedUrl: String,
    val source: String,
    val lastUpdated: Long,
    val episodeCount: Int
) {
    fun toShow(): Show = Show(
        id = id,
        title = title,
        author = author,
        artwork = artwork,
        description = description,
        feedUrl = feedUrl,
        source = source,
        lastUpdated = lastUpdated,
        episodeCount = episodeCount
    )

    companion object {
        fun from(show: Show): ShowEntity = ShowEntity(
            id = show.id,
            title = show.title,
            author = show.author,
            artwork = show.artwork,
            description = show.description,
            feedUrl = show.feedUrl,
            source = show.source,
            lastUpdated = show.lastUpdated,
            episodeCount = show.episodeCount
        )
    }
}

// ---------------------------------------------------------------------------
// Episode
// ---------------------------------------------------------------------------

@Entity(
    tableName = "episodes",
    indices = [Index("showId")]
)
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val showId: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val pageUrl: String,
    val artwork: String,
    val durationSec: Int,
    val publishedAt: Long,
    val transcriptUrl: String,
    val transcriptType: String,
    /** 已听标记（DB v2 新增列，默认 0=未听；旧库迁移时 ADD COLUMN ... DEFAULT 0）。 */
    val played: Boolean = false,
    /** 标为已听的时间（毫秒 epoch），未听为 0。 */
    val playedAt: Long = 0L
) {
    fun toEpisode(): Episode = Episode(
        id = id,
        showId = showId,
        title = title,
        description = description,
        audioUrl = audioUrl,
        pageUrl = pageUrl,
        artwork = artwork,
        durationSec = durationSec,
        publishedAt = publishedAt,
        transcriptUrl = transcriptUrl,
        transcriptType = transcriptType,
        played = played,
        playedAt = playedAt
    )

    companion object {
        fun from(episode: Episode): EpisodeEntity = EpisodeEntity(
            id = episode.id,
            showId = episode.showId,
            title = episode.title,
            description = episode.description,
            audioUrl = episode.audioUrl,
            pageUrl = episode.pageUrl,
            artwork = episode.artwork,
            durationSec = episode.durationSec,
            publishedAt = episode.publishedAt,
            transcriptUrl = episode.transcriptUrl,
            transcriptType = episode.transcriptType,
            played = episode.played,
            playedAt = episode.playedAt
        )
    }
}

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val episodeId: String,
    val positionMs: Long,
    val updatedAt: Long
)

// ---------------------------------------------------------------------------
// Download
// ---------------------------------------------------------------------------

/** DownloadEntity.state 的取值（冻结字符串，调用方用常量别手写字面量）。 */
object DownloadState {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    val ALL: Set<String> = setOf(QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED)

    fun isValid(state: String): Boolean = state in ALL
}

@Entity(
    tableName = "downloads",
    indices = [Index("showId")]
)
data class DownloadEntity(
    @PrimaryKey val episodeId: String,
    val showId: String,
    val title: String,
    val localPath: String,
    val state: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val createdAt: Long,
    val updatedAt: Long
)

// ---------------------------------------------------------------------------
// ASR transcript
// ---------------------------------------------------------------------------

@Entity(tableName = "asr_transcripts")
data class AsrTranscriptEntity(
    @PrimaryKey val episodeId: String,
    val model: String,
    val language: String,
    val source: String = "asr",
    /** Cue 列表的 JSON：JSONArray，每条 {"startMs":..,"endMs":..,"text":..}。 */
    val cuesJson: String,
    val createdAt: Long
)

// ---------------------------------------------------------------------------
// Queue （播放队列，DB v2 新增表）
// ---------------------------------------------------------------------------

/**
 * 播放队列项（0.4.0 补项）：只存 episodeId + 顺序，标题/封面等展示字段
 * 每次从 episodes 表按 episodeId 现取——单集被删或刷新后队列自动跟当前库走，
 * 不会出现队列里躺着一份过期标题。
 * position 从 0 起按播放顺序递增；同一 episodeId 只允许一行（主键去重）。
 */
@Entity(
    tableName = "queue_items",
    indices = [Index("position")]
)
data class QueueItemEntity(
    @PrimaryKey val episodeId: String,
    val showId: String,
    val position: Int,
    val addedAt: Long
)


// ---------------------------------------------------------------------------
// 生词本（DB v3 新增表）
// ---------------------------------------------------------------------------

/**
 * 生词本词条：一个单词一条（主键是小写原词）。
 * 词条本身只放"整体状态"，具体释义/例句放 word_senses，一词可有多条。
 */
@Entity(tableName = "words")
data class WordEntity(
    @PrimaryKey val word: String,
    /** 加入时所在的词汇层级（KeywordLevel.name），只作记录用 */
    val level: String = "",
    val addedAt: Long,
    /** 复习次数：每标一次"认识"或"不认识"都 +1 */
    val reviewCount: Int = 0,
    val mastered: Boolean = false
)

/** 一条释义 + 一句例句（同一词可多条；同一词同一释义不重复插）。 */
@Entity(
    tableName = "word_senses",
    indices = [Index("word"), Index("episodeId")]
)
data class WordSenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val pos: String,
    /** 中文释义 */
    val gloss: String,
    /** 音标（模型给，可能不准；可空串） */
    val ipa: String,
    /** 原字幕句 */
    val example: String,
    val showId: String,
    val episodeId: String,
    /** 例句在音频里的位置（毫秒），点例句可跳回 */
    val startMs: Long,
    val createdAt: Long
)

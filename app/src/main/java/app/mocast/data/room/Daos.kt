package app.mocast.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO（0.4.0 数据层）。
 * 约定：方法全部 suspend，返回 List / 实体 / nullable；不以 Flow 作为唯一接口
 * （调用方是同步风格的 RoomStore，可在 IO 线程调用；需要响应式时由调用方
 *  在 RoomStore 之上另包 Flow，不要改 DAO 签名）。
 * SQL 里的表名必须与 Entities.kt 的 tableName 一致：
 * shows / episodes / progress / downloads / asr_transcripts。
 */

// ---------------------------------------------------------------------------
// Show
// ---------------------------------------------------------------------------

@Dao
interface ShowDao {

    @Query("SELECT * FROM shows ORDER BY lastUpdated DESC")
    suspend fun getAll(): List<ShowEntity>

    @Query("SELECT * FROM shows WHERE id = :id")
    suspend fun getById(id: String): ShowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(show: ShowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(shows: List<ShowEntity>)

    @Query("DELETE FROM shows WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM shows")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM shows")
    suspend fun count(): Int

    /** 全量替换订阅列表（旧 Store.saveShows 的语义），一整个事务。 */
    @Transaction
    suspend fun replaceAll(shows: List<ShowEntity>) {
        deleteAll()
        upsertAll(shows)
    }
}

// ---------------------------------------------------------------------------
// Episode
// ---------------------------------------------------------------------------

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episodes WHERE showId = :showId ORDER BY publishedAt DESC")
    suspend fun getByShow(showId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: String): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(episode: EpisodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE showId = :showId")
    suspend fun deleteByShow(showId: String)

    @Query("DELETE FROM episodes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM episodes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM episodes")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM episodes WHERE showId = :showId")
    suspend fun countByShow(showId: String): Int

    /** 已听标记（0.4.0 补项）：单集切换已听/未听，playedAt 由调用方给（标已听=现在，未听=0）。 */
    @Query("UPDATE episodes SET played = :played, playedAt = :playedAt WHERE id = :episodeId")
    suspend fun setPlayed(episodeId: String, played: Boolean, playedAt: Long)

    /** 每档节目的未听数（首页角标用）；Room 不能直接返回 Map，用小数据类接。 */
    @Query("SELECT showId, COUNT(*) AS unplayed FROM episodes WHERE played = 0 GROUP BY showId")
    suspend fun countUnplayedByShow(): List<UnplayedCount>

    /** 替换某档节目的全部单集（旧 Store.saveEpisodes 的语义），一整个事务。 */
    @Transaction
    suspend fun replaceForShow(showId: String, episodes: List<EpisodeEntity>) {
        deleteByShow(showId)
        upsertAll(episodes)
    }
}

/** countUnplayedByShow 的行：showId + 该节目未听数。 */
data class UnplayedCount(val showId: String, val unplayed: Int)

// ---------------------------------------------------------------------------
// Queue （播放队列，DB v2 新增表）
// ---------------------------------------------------------------------------

@Dao
interface QueueDao {

    @Query("SELECT * FROM queue_items ORDER BY position ASC")
    suspend fun getAll(): List<QueueItemEntity>

    @Query("SELECT * FROM queue_items WHERE episodeId = :episodeId")
    suspend fun getByEpisode(episodeId: String): QueueItemEntity?

    @Query("SELECT MAX(position) FROM queue_items")
    suspend fun maxPosition(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: QueueItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<QueueItemEntity>)

    @Query("DELETE FROM queue_items WHERE episodeId = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)

    @Query("DELETE FROM queue_items WHERE showId = :showId")
    suspend fun deleteByShow(showId: String)

    @Query("DELETE FROM queue_items")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM queue_items")
    suspend fun count(): Int

    /** 整队重排（调用方算好新 position 后整表替换，队列短、事务内完成）。 */
    @Transaction
    suspend fun replaceAll(items: List<QueueItemEntity>) {
        deleteAll()
        upsertAll(items)
    }
}

// ---------------------------------------------------------------------------
// Progress
// ---------------------------------------------------------------------------

@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress")
    suspend fun getAll(): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE episodeId = :episodeId")
    suspend fun getByEpisode(episodeId: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(progress: List<ProgressEntity>)

    @Query("DELETE FROM progress WHERE episodeId = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)

    @Query("DELETE FROM progress")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM progress")
    suspend fun count(): Int
}

// ---------------------------------------------------------------------------
// Download
// ---------------------------------------------------------------------------

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE episodeId = :episodeId")
    suspend fun getByEpisode(episodeId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE showId = :showId ORDER BY createdAt DESC")
    suspend fun getByShow(showId: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state = :state ORDER BY createdAt DESC")
    suspend fun getByState(state: String): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(downloads: List<DownloadEntity>)

    @Query("DELETE FROM downloads WHERE episodeId = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)

    @Query("DELETE FROM downloads WHERE showId = :showId")
    suspend fun deleteByShow(showId: String)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun count(): Int
}

// ---------------------------------------------------------------------------
// ASR transcript
// ---------------------------------------------------------------------------

@Dao
interface AsrTranscriptDao {

    @Query("SELECT * FROM asr_transcripts WHERE episodeId = :episodeId")
    suspend fun getByEpisode(episodeId: String): AsrTranscriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transcript: AsrTranscriptEntity)

    @Query("DELETE FROM asr_transcripts WHERE episodeId = :episodeId")
    suspend fun deleteByEpisode(episodeId: String)

    @Query("SELECT COUNT(*) FROM asr_transcripts")
    suspend fun count(): Int
}

package app.mocast.data.room

import android.content.Context
import app.mocast.data.Show
import app.mocast.data.episodesFromJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 旧存储 → Room 一次性迁移（0.4.0 数据层）。
 *
 * 触发点：MoCastDatabase.get() 首次建库后同步跑一次（进程内一次）。
 * 也可手动调（幂等），但正常不用——RoomStore 构造即已完成迁移。
 *
 * 迁移源（全部只读，迁移后不删旧文件，留作回滚）：
 * 1. SharedPreferences "mocast"：key "shows"（节目 JSON 数组）与
 *    key "progress"（{episodeId: positionMs}，含 &quot; 二次转义兜底）。
 * 2. filesDir/feeds/<showId>.json：每档节目的单集 JSON 数组。
 * 3. filesDir/translations/（如有）：译文缓存（<episodeId>_<variant>.json）。
 *    v1 没有译文表——该目录只计数、不搬迁、不删除，TranslationCache 继续
 *    用文件工作；以后若译文进 Room，另写迁移 v2。
 *
 * 安全铁律：
 * - 任何一条坏数据只跳过该条（记入 skipped* 计数），绝不抛异常卡死首页。
 * - 整个 migrateIfNeeded() 包在 try/catch 里，极端情况下也返回结果、不抛。
 * - 只有 Room 三张旧表（shows/episodes/progress）全空时才迁移；Room 已有
 *   数据说明已迁移过或用户已在新版产生数据，直接跳过，不覆盖。
 * - flag "mocast_room_migrated_v1" 写在同一个 prefs "mocast" 里；只有本次
 *   真正跑完迁移流程才写 true。Room 非空而跳过时不写 flag（下次启动仍会
 *   做一次空表检查，代价是三次 COUNT 查询，可忽略）。
 * - 性能：按文件逐批 upsertAll，不把全库读进内存；节目数/单集数极大时
 *   首次启动会多花一点 IO，但仍在 get() 的同步等待里，建议真机实测
 *   （见报告“未验证项”）。
 */
object LegacyMigration {

    const val PREFS_NAME = "mocast"
    const val KEY_MIGRATED = "mocast_room_migrated_v1"

    private const val KEY_SHOWS = "shows"
    private const val KEY_PROGRESS = "progress"

    data class MigrationResult(
        /** prefs flag 已是 true，本次什么都没做。 */
        val alreadyMigrated: Boolean = false,
        /** Room 已有数据，本次跳过迁移（不写 flag）。 */
        val roomNotEmpty: Boolean = false,
        val showsMigrated: Int = 0,
        val episodesMigrated: Int = 0,
        val progressMigrated: Int = 0,
        /** translations/ 下看到的文件数（只计数，未搬迁，见类注释）。 */
        val translationFilesSeen: Int = 0,
        val skippedShows: Int = 0,
        val skippedEpisodes: Int = 0,
        val skippedProgress: Int = 0
    )

    suspend fun migrateIfNeeded(context: Context, db: MoCastDatabase): MigrationResult {
        return try {
            migrateInternal(context, db)
        } catch (t: Throwable) {
            // 极端兜底：迁移任何环节炸了也不影响首页启动（返回空结果，不写 flag，
            // 下次启动会再试一次；Room 里已写入的部分行保留，不回滚）。
            MigrationResult()
        }
    }

    private suspend fun migrateInternal(context: Context, db: MoCastDatabase): MigrationResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) {
            return MigrationResult(alreadyMigrated = true)
        }
        if (db.showDao().count() > 0 ||
            db.episodeDao().count() > 0 ||
            db.progressDao().count() > 0
        ) {
            return MigrationResult(roomNotEmpty = true)
        }

        var showsMigrated = 0
        var episodesMigrated = 0
        var progressMigrated = 0
        var skippedShows = 0
        var skippedEpisodes = 0
        var skippedProgress = 0

        // --- 1. 节目（逐条解析，坏条跳过） ---
        runCatching {
            val raw = prefs.getString(KEY_SHOWS, null) ?: ""
            if (raw.isNotBlank()) {
                val arr = JSONArray(raw)
                val entities = ArrayList<ShowEntity>(arr.length())
                for (i in 0 until arr.length()) {
                    runCatching {
                        val show = Show.fromJson(arr.getJSONObject(i))
                        if (show.id.isBlank()) {
                            skippedShows++
                        } else {
                            entities.add(ShowEntity.from(show))
                        }
                    }.onFailure { skippedShows++ }
                }
                if (entities.isNotEmpty()) {
                    db.showDao().upsertAll(entities)
                    showsMigrated = entities.size
                }
            }
        }

        // --- 2. 单集（按文件逐个读，坏文件跳过整个文件，坏单集跳过该条） ---
        runCatching {
            val feedsDir = File(context.filesDir, "feeds")
            val files = feedsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                .orEmpty()
                .sortedBy { it.name }
            for (file in files) {
                runCatching {
                    val showId = file.nameWithoutExtension
                    val episodes = episodesFromJson(file.readText())
                    val entities = ArrayList<EpisodeEntity>(episodes.size)
                    for (episode in episodes) {
                        if (episode.id.isBlank()) {
                            skippedEpisodes++
                            continue
                        }
                        // showId 以文件名为准（旧 JSON 里个别单集缺 showId 时也能归位）。
                        entities.add(EpisodeEntity.from(episode.copy(showId = showId)))
                    }
                    if (entities.isNotEmpty()) {
                        db.episodeDao().upsertAll(entities)
                        episodesMigrated += entities.size
                    }
                }
            }
        }

        // --- 3. 进度（逐条解析，坏条跳过；沿用旧 Store 的 &quot; 兜底） ---
        runCatching {
            val progress = readLegacyProgress(prefs.getString(KEY_PROGRESS, null))
            val keys = progress.keys()
            val entities = ArrayList<ProgressEntity>()
            while (keys.hasNext()) {
                val episodeId = keys.next()
                runCatching {
                    if (episodeId.isBlank()) {
                        skippedProgress++
                    } else {
                        val positionMs = progress.getLong(episodeId)
                        entities.add(
                            ProgressEntity(
                                episodeId = episodeId,
                                positionMs = positionMs,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }.onFailure { skippedProgress++ }
            }
            if (entities.isNotEmpty()) db.progressDao().upsertAll(entities)
            progressMigrated = entities.size
        }

        // --- 4. 译文目录：只计数，不搬迁、不删除（见类注释） ---
        val translationFilesSeen = runCatching {
            val dir = File(context.filesDir, "translations")
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.size ?: 0
        }.getOrDefault(0)

        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        return MigrationResult(
            showsMigrated = showsMigrated,
            episodesMigrated = episodesMigrated,
            progressMigrated = progressMigrated,
            translationFilesSeen = translationFilesSeen,
            skippedShows = skippedShows,
            skippedEpisodes = skippedEpisodes,
            skippedProgress = skippedProgress
        )
    }

    /**
     * 旧 Store.readProgress 的原样复刻（迁移不能依赖将被替换的 Store 类，
     * 所以这里独立实现一份；行为一致：先原样解析，失败再做 HTML 实体反转义）。
     */
    private fun readLegacyProgress(raw: String?): JSONObject {
        val text = raw.orEmpty()
        if (text.isBlank()) return JSONObject()
        return runCatching { JSONObject(text) }.getOrElse {
            runCatching {
                JSONObject(
                    text.replace("&quot;", "\"")
                        .replace("&apos;", "'")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&amp;", "&")
                )
            }.getOrDefault(JSONObject())
        }
    }
}

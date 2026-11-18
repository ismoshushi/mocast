package app.mocast.data

import org.json.JSONArray
import org.json.JSONObject

data class Show(
    val id: String,
    val title: String,
    val author: String,
    val artwork: String,
    val description: String,
    val feedUrl: String,
    val source: String,
    val lastUpdated: Long = System.currentTimeMillis(),
    val episodeCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("author", author)
        put("artwork", artwork)
        put("description", description)
        put("feedUrl", feedUrl)
        put("source", source)
        put("lastUpdated", lastUpdated)
        put("episodeCount", episodeCount)
    }

    companion object {
        fun fromJson(o: JSONObject) = Show(
            id = o.optString("id"),
            title = o.optString("title"),
            author = o.optString("author"),
            artwork = o.optString("artwork"),
            description = o.optString("description"),
            feedUrl = o.optString("feedUrl"),
            source = o.optString("source"),
            lastUpdated = o.optLong("lastUpdated"),
            episodeCount = o.optInt("episodeCount")
        )
    }
}

data class Episode(
    val id: String,
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
    /** 已听标记（0.4.0 补项）：进度 >=95% 或播完自动标已听，也可手动切换。旧数据默认未听。 */
    val played: Boolean = false,
    /** 标为已听的时间（毫秒 epoch），未听为 0。 */
    val playedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("showId", showId)
        put("title", title)
        put("description", description)
        put("audioUrl", audioUrl)
        put("pageUrl", pageUrl)
        put("artwork", artwork)
        put("durationSec", durationSec)
        put("publishedAt", publishedAt)
        put("transcriptUrl", transcriptUrl)
        put("transcriptType", transcriptType)
        put("played", played)
        put("playedAt", playedAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Episode(
            id = o.optString("id"),
            showId = o.optString("showId"),
            title = o.optString("title"),
            description = o.optString("description"),
            audioUrl = o.optString("audioUrl"),
            pageUrl = o.optString("pageUrl"),
            artwork = o.optString("artwork"),
            durationSec = o.optInt("durationSec"),
            publishedAt = o.optLong("publishedAt"),
            transcriptUrl = o.optString("transcriptUrl"),
            transcriptType = o.optString("transcriptType"),
            played = o.optBoolean("played", false),
            playedAt = o.optLong("playedAt", 0L)
        )
    }
}

data class Cue(val startMs: Long, val endMs: Long, val text: String)

fun episodesToJson(list: List<Episode>): String {
    val arr = JSONArray()
    list.forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun episodesFromJson(raw: String?): List<Episode> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { Episode.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}

package app.mocast.asr

import app.mocast.data.Cue
import org.json.JSONArray
import org.json.JSONObject

/**
 * ASR 字幕的持久化接口。
 *
 * 本文件只定义接口 + Cue 列表的 JSON 编解码，不建 Room 实体、不碰数据库。
 * Room 任务按约定提供实现：AsrTranscriptEntity(episodeId @PrimaryKey, model, language,
 * source="asr", cuesJson, createdAt)，并让 RoomStore 实现这个接口。
 *
 * Room 任务实现时注意：
 * - saveAsrTranscript 是“全量覆盖写”：调用方每完成一段就把“截止目前的全部段”传进来，
 *   不要追加写一条；幂等，崩溃重跑也不会出现重复段。
 * - cuesJson 的格式必须用 [AsrCuesJson] 编解码，不要自己再发明一种，保证两边读写一致。
 * - loadAsrTranscript 返回 null 表示“这一集从没跑过 ASR”，返回空列表表示“跑过但一个字
 *   都没识别出来”（例如整集都是音乐）；Service 靠这个区分“续跑”与“从头跑”。
 */
interface AsrTranscriptStore {
    fun saveAsrTranscript(episodeId: String, model: String, language: String, cues: List<Cue>)
    fun loadAsrTranscript(episodeId: String): List<Cue>?
}

/** Cue 列表 ↔ JSON 字符串。Room 的 cuesJson 列、翻译缓存都不走这个格式，各用各的。 */
object AsrCuesJson {

    /** [{"startMs":1234,"endMs":5678,"text":"..."}, ...]，按 startMs 排序。 */
    fun encode(cues: List<Cue>): String {
        val arr = JSONArray()
        cues.sortedBy { it.startMs }.forEach { cue ->
            arr.put(JSONObject().apply {
                put("startMs", cue.startMs)
                put("endMs", cue.endMs)
                put("text", cue.text)
            })
        }
        return arr.toString()
    }

    /** 解析失败返回空列表（不要抛：坏一行 JSON 不该让整集字幕打不开）。 */
    fun decode(raw: String?): List<Cue> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val text = o.optString("text").trim()
                if (text.isEmpty()) return@mapNotNull null
                Cue(
                    startMs = o.optLong("startMs", 0L).coerceAtLeast(0),
                    endMs = o.optLong("endMs", 0L).coerceAtLeast(0),
                    text = text
                )
            }.sortedBy { it.startMs }
        }.getOrDefault(emptyList())
    }
}

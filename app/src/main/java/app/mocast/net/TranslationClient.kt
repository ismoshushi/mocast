package app.mocast.net

import app.mocast.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class TranslationClient {

    suspend fun translate(settings: Settings, texts: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
            val body = JSONObject().apply {
                put("model", settings.model)
                put("temperature", 0.2)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt(settings.targetLang))
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt(settings.targetLang, texts))
                    })
                })
            }
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${settings.apiKey.trim()}")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            Http.client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("翻译失败（HTTP ${resp.code}）：${raw.take(180)}")
                }
                val content = JSONObject(raw).optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                parse(content, texts.size)
            }
        }

    private fun systemPrompt(lang: String) =
        "你是播客字幕翻译引擎。把用户给出的每条字幕逐条翻译成$lang，保持口语、简洁、连贯。" +
            "只输出一个 JSON 字符串数组，元素数量必须与输入条数完全一致，不要解释、不要加序号。"

    private fun userPrompt(lang: String, texts: List<String>): String {
        val numbered = texts.mapIndexed { i, t -> "${i + 1}. ${t.replace("\n", " ")}" }
        return "把下面 ${texts.size} 条字幕逐条翻译成$lang，输出长度为 ${texts.size} 的 JSON 数组。\n\n" +
            numbered.joinToString("\n")
    }

    private fun parse(content: String, expected: Int): List<String> {
        val cleaned = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start >= 0 && end > start) {
            val json = runCatching { JSONArray(cleaned.substring(start, end + 1)) }.getOrNull()
            if (json != null) {
                val out = ArrayList<String>(expected)
                for (i in 0 until expected) {
                    out.add(json.optString(i, "").trim())
                }
                return out
            }
        }
        // 模型没按格式返回时按行兜底
        val lines = cleaned.split("\n").map { it.trim().trimStart('-', '*', ' ') }
            .filter { it.isNotEmpty() }
        val out = ArrayList<String>(expected)
        for (i in 0 until expected) out.add(lines.getOrElse(i) { "" })
        return out
    }
}

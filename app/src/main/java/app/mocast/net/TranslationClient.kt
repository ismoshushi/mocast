package app.mocast.net

import app.mocast.data.Settings
import app.mocast.data.TranslationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class TranslationClient {

    /**
     * 测试连接（0.4.0 P0 收尾）：只发一个极小试探请求（翻一句 "Hello"），
     * 不写翻译缓存、不翻整集。返回耗时与中文结论，调用方直接展示。
     * 失败按 HTTP 码 / 异常类型分中文原因，不抛错（除非调用方自己要抛）。
     */
    data class ConnectionTestResult(val ok: Boolean, val latencyMs: Long, val message: String)

    suspend fun testConnection(settings: Settings): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (settings.baseUrl.isBlank() || settings.apiKey.isBlank() || settings.model.isBlank()) {
            return@withContext ConnectionTestResult(false, 0, "先填 Base URL、API Key 和模型")
        }
        val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject().apply {
            put("model", settings.model)
            put("temperature", 0.0)
            put("max_tokens", 10)
            if (isZhipu(settings.baseUrl)) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Hello")
                })
            })
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val start = System.currentTimeMillis()
        try {
            Http.client.newCall(request).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - start
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val reason = when (resp.code) {
                        401, 403 -> "API Key 无效（HTTP ${resp.code}），去设置里检查 Key 是否填错、是否过期"
                        404 -> "模型不存在（HTTP 404），检查模型名是否写错，或该服务商没有这个模型"
                        429 -> "请求被限流（HTTP 429），稍后再试"
                        in 500..599 -> "服务商出错（HTTP ${resp.code}），稍后再试"
                        else -> "连接失败（HTTP ${resp.code}）：${raw.take(120)}"
                    }
                    return@withContext ConnectionTestResult(false, elapsed, reason)
                }
                val content = runCatching {
                    JSONObject(raw).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                }.getOrNull().orEmpty()
                if (content.isBlank()) {
                    return@withContext ConnectionTestResult(false, elapsed, "返回格式异常：服务商没给回模型回复")
                }
                return@withContext ConnectionTestResult(true, elapsed, "连接正常，耗时 ${elapsed}ms")
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - start
            val msg = e.message.orEmpty()
            val reason = when {
                e is java.net.SocketTimeoutException || msg.contains("timeout", ignoreCase = true) ->
                    "连接超时，检查网络或代理，服务商也可能正忙"
                e is java.net.UnknownHostException || msg.contains("Unable to resolve host") ->
                    "地址连不上：Base URL 的域名解析失败，检查地址是否写错"
                msg.contains("Failed to connect") || msg.contains("Connection refused") ->
                    "地址连不上：连不上 Base URL，检查地址、网络或代理"
                else -> "连接失败：$msg"
            }
            return@withContext ConnectionTestResult(false, elapsed, reason)
        }
    }

    suspend fun translate(settings: Settings, texts: List<String>, firstNumber: Int = 1): List<String> =
        withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyList()
            val url = settings.baseUrl.trimEnd('/') + "/chat/completions"
            val body = JSONObject().apply {
                put("model", settings.model)
                put("temperature", 0.2)
                // 智谱 GLM 是混合思考模型，默认先吐 reasoning_content 再吐正文；
                // 字幕翻译要的是稳定 JSON，关掉思考既省 token 也避免正文被挤压或格式漂移。
                // 只在智谱域名下加，其他 OpenAI 兼容服务商不认识 thinking 字段会报参数错误。
                if (isZhipu(settings.baseUrl)) {
                    put("thinking", JSONObject().put("type", "disabled"))
                }
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt(settings))
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt(settings, texts))
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
                parse(content, texts.size, settings.translationMode)
            }
        }

    private fun isZhipu(baseUrl: String): Boolean =
        baseUrl.contains("bigmodel.cn", ignoreCase = true)

    private fun systemPrompt(settings: Settings): String = when (settings.translationMode) {
        TranslationMode.FULL ->
            "你是播客字幕翻译引擎。把用户给出的每条字幕逐条翻译成${settings.targetLang}，保持口语、简洁、连贯。" +
                "只输出一个 JSON 字符串数组，元素数量必须与输入条数完全一致，不要解释、不要加序号。"

        TranslationMode.KEYWORD ->
            "你是英语生词标注引擎，服务对象是练听力的中国学习者，当前词汇层级为「${settings.keywordLevel.label}」" +
                "（约 ${settings.keywordLevel.vocab} 词，${settings.keywordLevel.hint}）。规则：\n" +
                "1) 只标注超出该层级的词（比它更难一档及以上的词），层级内的常见词、专有名词、人名地名一律不标；\n" +
                "2) 每条字幕输出一个字符串：「序号|生词列表」，序号必须与输入编号一致；" +
                "生词列表格式为 `word 词性. 中文释义`，多个用「；」分隔，每条最多 3 个；\n" +
                "3) 每条字幕都要有对应元素，没有超纲词就输出「序号|」，后面留空；\n" +
                "4) 释义只用中文写，不要照抄英文原词，也不要把英文名解释成「人名」「软件名」糊过去；\n" +
                "5) 人名、地名、公司名、产品名、软件名、代号缩写一律不标，也不要写「不标注」「专有名词」这类说明；\n" +
                "6) 只收真正超出该层级的词，computer/product/name/people/time 这类初级常见词不要标；宁可漏标也不要凑数；\n" +
                "示例：[\"1|installer n. 安装程序\",\"2|\",\"3|junkware n. 捆绑垃圾软件\"]\n" +
                "只输出这个 JSON 字符串数组，不要解释。"
    }

    private fun userPrompt(settings: Settings, texts: List<String>): String {
        val numbered = texts.mapIndexed { i, t -> "${i + 1}. ${t.replace("\n", " ")}" }
        val task = when (settings.translationMode) {
            TranslationMode.FULL ->
                "把下面 ${texts.size} 条字幕逐条翻译成${settings.targetLang}，输出长度为 ${texts.size} 的 JSON 数组"
            TranslationMode.KEYWORD ->
                "标注下面 ${texts.size} 条字幕里超出「${settings.keywordLevel.label}」层级的生词，" +
                    "按「序号|生词释义」输出 ${texts.size} 个元素，序号要与下面编号一致"
        }
        return "$task。\n\n" + numbered.joinToString("\n")
    }

    private fun parse(
        content: String,
        expected: Int,
        mode: TranslationMode
    ): List<String> {
        val cleaned = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (mode == TranslationMode.KEYWORD) return parseIndexed(cleaned, expected)

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

    /**
     * 生词标注按「序号|内容」对齐：模型漏条、多给、顺序乱都不会错位到别的字幕上。
     * 有的模型会把多条塞进一个 JSON 元素里（形如 `36|a n. 甲|2|b n. 乙`），
     * 所以这里不按 JSON 元素切，而是扫全文里的「数字|」标记，按标记切段。
     */
    private fun parseIndexed(cleaned: String, expected: Int): List<String> {
        val out = MutableList(expected) { "" }
        fun hasChinese(text: String) = text.any { it.code in 0x4E00..0x9FFF }

        val markers = Regex("""(\d{1,4})\s*\|""").findAll(cleaned).toList()
        if (markers.isEmpty()) {
            // 完全没有序号：只可能是一条一条按行给的，按行兜底
            cleaned.split("\n")
                .map { it.trim().trimStart('-', '*', ' ') }
                .filter { it.isNotEmpty() && hasChinese(it) }
                .take(expected)
                .forEachIndexed { i, line -> out[i] = cleanEntry(line) }
            return out
        }

        markers.forEachIndexed { i, marker ->
            val index = marker.groupValues[1].toIntOrNull() ?: return@forEachIndexed
            val bodyStart = marker.range.last + 1
            val bodyEnd = markers.getOrNull(i + 1)?.range?.first ?: cleaned.length
            if (index !in 1..expected) return@forEachIndexed
            val body = cleanEntry(cleaned.substring(bodyStart, bodyEnd))
            // 只给英文单词、或释义里根本没中文的（「人名」「软件名」糊过去）都留空
            val chineseCount = body.count { it.code in 0x4E00..0x9FFF }
            if (body.isNotBlank() && chineseCount >= 2 && out[index - 1].isBlank()) {
                out[index - 1] = body
            }
        }
        return out
    }

    /** 去掉模型带出来的引号、逗号、括号等包装，并去掉重复释义段 */
    private fun cleanEntry(raw: String): String {
        val junk = "\"'，,；;、:：|[]{}（）() \n".toSet()
        val cleaned = raw.trim().trim { it in junk }.replace(Regex("[；;]+$"), "").trim()
        // 模型常把同一段释义重复输出（「发生；出现；产生；发生；出现；产生」），这里去重
        val parts = cleaned.split('；', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return cleaned.take(120)
        val seen = LinkedHashSet<String>()
        for (p in parts) seen.add(p)
        return seen.joinToString("；").replace(Regex("[；;]+$"), "").take(120)
    }
}

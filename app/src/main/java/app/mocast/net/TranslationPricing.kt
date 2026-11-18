package app.mocast.net

import app.mocast.data.Cue
import app.mocast.data.Settings
import app.mocast.data.TranslationMode

/**
 * 单集翻译费用预估（0.4.0 P0 收尾）。
 *
 * 只是估算，不是账单：token 数按字幕文本估，费用按模型单价算。
 * 估算规则写死在注释里，UI 上也要写明是估算、单价可能已变。
 *
 * token 估算：英文约 4 字符 ≈ 1 token；中文/日韩字符约 1 字符 ≈ 1 token；
 * 每条字幕再加 4 token（编号与格式开销）；每批请求另加约 250 token
 * 系统提示词。输出：完整翻译约等于输入文本量，关键词标注每条约 10 token。
 */
data class TranslationEstimate(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    /** 美元；null = 单价未知（不在内置表里、设置里也没填自定义单价）。 */
    val costUsd: Double?,
    val model: String,
    /** 单价来源说明，UI 原样展示，例如 "gpt-4o-mini 单价 2026-11-18 快照"。 */
    val priceNote: String,
    val unknownPrice: Boolean
)

data class ModelPrice(val inputPer1M: Double, val outputPer1M: Double, val note: String)

object TranslationPricing {

    /** 内置单价表（美元 / 1M token），2026-11-18 核实快照，价格随时会变，UI 必须注明。 */
    const val PRICE_DATE = "2026-11-18"

    private val KNOWN: Map<String, ModelPrice> = mapOf(
        // OpenAI 官方价，多家比价站 2026-11-17/18 快照一致：$0.15/$0.60。
        "gpt-4o-mini" to ModelPrice(0.15, 0.60, "gpt-4o-mini 官方单价 $0.15/$0.60，2026-11-18 快照"),
        // DeepSeek 官方 deepseek-chat：LiteLLM 2026-11-18 快照 $0.28/$0.42；
        // 注意 DeepSeek 官方已转向 V4 系列，deepseek-chat 是旧别名，价格可能已变。
        "deepseek-chat" to ModelPrice(0.28, 0.42, "deepseek-chat 单价 $0.28/$0.42，2026-11-18 快照，DeepSeek 官方已转向 V4 系列"),
        // Z.ai（智谱）官方：glm-4.7-flash 免费；第三方中转约 $0.06/$0.40，用的官方地址就按免费算。
        "glm-4.7-flash" to ModelPrice(0.0, 0.0, "glm-4.7-flash 在 Z.ai 官方免费（第三方中转约 $0.06/$0.40）"),
        "glm-4.7" to ModelPrice(0.60, 2.20, "glm-4.7 单价 $0.60/$2.20，2026-11-18 快照"),
        // 硅基流动 Qwen/Qwen2.5-7B-Instruct：多家比价站 2026-11-18 一致 $0.05/$0.05。
        "qwen2.5-7b-instruct" to ModelPrice(0.05, 0.05, "Qwen2.5-7B-Instruct（硅基流动）单价 $0.05/$0.05，2026-11-18 快照")
    )

    fun priceFor(model: String, baseUrl: String): ModelPrice? {
        val lower = model.lowercase()
        // 本机模型（Ollama 等）不花钱。
        val host = baseUrl.lowercase()
        if (host.contains("127.0.0.1") || host.contains("localhost") || host.contains("192.168.")) {
            return ModelPrice(0.0, 0.0, "本机模型，不花钱")
        }
        // 先匹配长名字（glm-4.7-flash 要在 glm-4.7 前面，否则会被后者截走）。
        for ((key, price) in KNOWN) {
            if (lower.contains(key)) return price
        }
        return null
    }

    fun estimate(settings: Settings, cues: List<Cue>): TranslationEstimate {
        val batch = if (settings.translationMode == TranslationMode.KEYWORD) 10 else 12
        var textTokens = 0
        for (cue in cues) textTokens += estimateTextTokens(cue.text)
        val batches = if (cues.isEmpty()) 0 else (cues.size + batch - 1) / batch
        val inputTokens = textTokens + cues.size * 4 + batches * 250
        val outputTokens = if (settings.translationMode == TranslationMode.KEYWORD) {
            cues.size * 10
        } else {
            textTokens
        }
        val total = inputTokens + outputTokens
        val custom = settings.customPricePer1kTokens
        if (custom > 0f) {
            return TranslationEstimate(
                inputTokens, outputTokens, total,
                costUsd = total / 1000.0 * custom,
                model = settings.model,
                priceNote = "按设置里填的自定义单价 \$${custom}/1k token 估算",
                unknownPrice = false
            )
        }
        val price = priceFor(settings.model, settings.baseUrl)
        if (price == null) {
            return TranslationEstimate(
                inputTokens, outputTokens, total,
                costUsd = null,
                model = settings.model,
                priceNote = "单价未知：在翻译设置里填每 1k token 单价，即可算出预估费用",
                unknownPrice = true
            )
        }
        val cost = inputTokens / 1_000_000.0 * price.inputPer1M +
            outputTokens / 1_000_000.0 * price.outputPer1M
        return TranslationEstimate(
            inputTokens, outputTokens, total,
            costUsd = cost,
            model = settings.model,
            priceNote = price.note + "，价格可能已变，仅供参考",
            unknownPrice = false
        )
    }

    private fun estimateTextTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            val code = ch.code
            if (code in 0x4E00..0x9FFF || code in 0x3040..0x30FF || code in 0xAC00..0xD7AF) cjk++ else other++
        }
        return cjk + other / 4
    }
}

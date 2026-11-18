package app.mocast.asr

import app.mocast.data.Cue

/**
 * Whisper 幻觉过滤：只做“去掉明显假的”，不改写、不润色、不合并原文本。
 *
 * Whisper 在静音、音乐、片头曲上最容易“编”出台词，典型症状就三类：
 * 1. 空段（VAD 误判 + 模型硬编出空白/标点）；
 * 2. 同一句连续重复（同一句话在相邻段里一模一样地出现两三次）；
 * 3. 段内词语循环（一句话里同一个词滚雪球，例如“谢谢 谢谢 谢谢 谢谢……”）。
 *
 * 规则是保守的：只在“高度疑似幻觉”时丢，宁可留一句可疑的真话，也不多删。
 * 中英文混说、口头禅（“然后然后”）、歌词重复都不在打击范围——
 * 口头禅是两个词，循环判定要 60% 以上 token 相同才触发；歌词重复是不同段唱同一句，
 * 连续重复判定只看“紧邻的两段文本完全相同”，隔段重复不删。
 */
object HallucinationFilter {

    /** 逐段过滤，输入是按时间排好序的段。返回过滤后的新列表，不改输入。 */
    fun filter(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>(cues.size)
        var lastKeptNormalized: String? = null
        var repeatRun = 0
        for (cue in cues) {
            val text = cue.text.trim()
            if (text.isEmpty()) continue
            // 纯标点 / 纯空白符号：模型在静音上常吐 "..."、"，"。
            if (text.all { it.isWhitespace() || isPunctuation(it) }) continue
            // 整段只有一个 "[音乐]" "[掌声]" 这类标注：不是人说的话，不上屏。
            if (isBracketTagOnly(text)) continue
            val normalized = normalize(text)
            if (normalized.isEmpty()) continue
            // 段内词语循环：同一个词占了六成以上 token。
            if (isTokenLoop(text)) continue
            // 连续重复：与上一段“留下来”的文本归一化后完全相同。
            // 同一句连着出现 3 次及以上时，从第 3 次起丢；只连着出现两次的先留着
            //（副歌/口号真有可能连说两遍），宁可错留，不断真句。
            if (normalized == lastKeptNormalized) {
                repeatRun++
                if (repeatRun >= 2) continue
            } else {
                repeatRun = 0
                lastKeptNormalized = normalized
            }
            out.add(cue)
        }
        return out
    }

    /**
     * 单段判定（Service 每解完一段就调这个，不用等全集跑完）。
     * 调用方自己维护一个 [State] 实例即可。
     */
    class State {
        private var lastKeptNormalized: String? = null
        private var repeatRun = 0

        /** 返回 true 表示这段该丢。 */
        fun shouldDrop(cue: Cue): Boolean {
            val text = cue.text.trim()
            if (text.isEmpty()) return true
            if (text.all { it.isWhitespace() || isPunctuation(it) }) return true
            if (isBracketTagOnly(text)) return true
            val normalized = normalize(text)
            if (normalized.isEmpty()) return true
            if (isTokenLoop(text)) return true
            if (normalized == lastKeptNormalized) {
                repeatRun++
                if (repeatRun >= 2) return true
            } else {
                repeatRun = 0
                lastKeptNormalized = normalized
            }
            return false
        }
    }

    /** 归一化：去空白、去标点、全小写；中英文都适用。 */
    internal fun normalize(text: String): String =
        text.filter { !it.isWhitespace() && !isPunctuation(it) }.lowercase()

    private fun isPunctuation(c: Char): Boolean =
        c in ".,!?;:\"'，。、；：？！·…—-()（）[]【】《》\"'“”‘’、～~·•◦"

    private fun isBracketTagOnly(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("[") && t.endsWith("]") && t.length <= 12) ||
            (t.startsWith("【") && t.endsWith("】") && t.length <= 12)
    }

    /**
     * 段内循环：把文本按“词/字”切开，出现次数最多的那个 token 占比 >= 60% 且 token 总数 >= 4。
     * 中文按字切（“谢谢谢谢谢谢”→ 谢/谢/谢/谢……），英文按空格切。
     */
    private fun isTokenLoop(text: String): Boolean {
        val tokens = tokenize(text)
        if (tokens.size < 4) return false
        val counts = tokens.groupingBy { it }.eachCount()
        val max = counts.maxOf { it.value }
        return max * 1.0 / tokens.size >= 0.6
    }

    private fun tokenize(text: String): List<String> {
        val latin = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        // 含 CJK 时按字切更准；纯英文按空格切。
        return if (text.any { it in '\u4E00'..'\u9FFF' }) {
            text.filter { !it.isWhitespace() && !isPunctuation(it) }.map { it.toString() }
        } else {
            latin
        }
    }
}

package app.mocast.net

/**
 * 关键词（生词）标注行的解析器。
 *
 * 模型输出的一行长这样（新版，带音标）：
 * `installer n. /ɪnˈstɔːlər/ 安装程序；junkware n. /ˈdʒʌŋkweə/ 捆绑垃圾软件`
 * 旧缓存里没有音标：
 * `installer n. 安装程序；junkware n. 捆绑垃圾软件`
 *
 * 解析规则（宽容，坏条目整条丢弃，绝不把乱文本写进库）：
 * - 先按「；」「;」切条目；
 * - 词性标签（n. / v. / adj. …）之前的部分当单词（支持 give up 这种词组）；
 * - 词性标签之后、`/…/` 音标之前/之后的中文部分是释义；
 * - 音标取第一个 `/…/`，拿不到就留空串（旧格式兼容）。
 */
data class ParsedWord(
    val word: String,
    val pos: String,
    val ipa: String,
    val gloss: String
)

object KeywordParser {

    /** 常见词性缩写（小写比较）。 */
    private val POS_TAGS = setOf(
        "n.", "v.", "vt.", "vi.", "adj.", "adv.", "prep.", "conj.", "pron.",
        "phr.", "abbr.", "num.", "art.", "aux.", "int.", "det."
    )

    private val WORD_RE = Regex("^[A-Za-z][A-Za-z'’\\-]*$")
    private val IPA_RE = Regex("/[^/]{1,60}/")

    /** 拆一整行（可能含多个用「；」分隔的词条）。解析不出来的条目直接跳过。 */
    fun parseLine(line: String): List<ParsedWord> {
        if (line.isBlank()) return emptyList()
        return line.split('；', ';')
            .mapNotNull { parseOne(it) }
    }

    /** 解析单个词条；解析失败返回 null。 */
    fun parseOne(raw: String): ParsedWord? {
        val text = raw.trim().trim('-', '·', '•', '*').trim()
        if (text.isEmpty()) return null

        val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        // 找词性标签的位置：它前面是单词，后面是释义（可能有音标）
        val posIndex = parts.indexOfFirst { it.lowercase() in POS_TAGS }
        val wordParts = if (posIndex > 0) parts.subList(0, posIndex) else listOf(parts.first())
        val pos = if (posIndex > 0) parts[posIndex] else ""
        val restParts = if (posIndex > 0) parts.subList(posIndex + 1, parts.size) else parts.drop(1)

        val word = wordParts.joinToString(" ").trim().trim(',', '.', ':')
        if (word.isEmpty() || !WORD_RE.matches(word)) return null

        val rest = restParts.joinToString(" ")
        val ipaMatch = IPA_RE.find(rest)
        val ipa = ipaMatch?.value ?: ""
        val gloss = rest.replace(ipa, "").trim().trim('，', ',', '。', '.', '：', ':').trim()
        if (gloss.isEmpty()) return null

        return ParsedWord(word = word, pos = pos, ipa = ipa, gloss = gloss)
    }
}

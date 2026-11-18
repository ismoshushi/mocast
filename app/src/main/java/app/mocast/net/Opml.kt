package app.mocast.net

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OPML 导入 / 导出（0.4.0 P0 收尾）。
 *
 * 导入只认 outline 的 xmlUrl（兼容嵌套 outline、title/text 任一属性）；
 * 导出生成标准 OPML 2.0。订阅本身不另写逻辑，导入后逐个走
 * FeedRepository.subscribe，和粘贴链接订阅是同一条路。
 * Room v2 不动：订阅已经是 Room 里的 Show，不需要新表。
 */
data class OpmlFeed(val title: String, val feedUrl: String)

data class OpmlImportSummary(
    val added: Int,
    val skipped: Int,
    val failed: Int,
    /** 失败项：每条 "标题：原因"，最多 10 条，UI 直接展示。 */
    val failures: List<String> = emptyList()
)

object Opml {

    /** 解析 OPML 文本。文件不是 OPML / 完全没有 xmlUrl 时抛中文错，调用方展示，不崩溃。 */
    fun parse(xml: String): List<OpmlFeed> {
        if (xml.isBlank()) throw IllegalArgumentException("文件是空的")
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), null)
        val out = ArrayList<OpmlFeed>()
        var sawOpml = false
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event != XmlPullParser.START_TAG) continue
            val name = parser.name.lowercase(Locale.US)
            if (name == "opml") sawOpml = true
            if (name != "outline") continue
            val feedUrl = (parser.getAttributeValue(null, "xmlUrl") ?: "").trim()
            if (feedUrl.isBlank()) continue
            val title = (parser.getAttributeValue(null, "title")
                ?: parser.getAttributeValue(null, "text") ?: "").trim()
            out.add(OpmlFeed(title.ifBlank { feedUrl }, feedUrl))
        }
        if (!sawOpml) throw IllegalArgumentException("这不是 OPML 文件（找不到 <opml>）")
        if (out.isEmpty()) throw IllegalArgumentException("文件里没有可导入的订阅（找不到带 xmlUrl 的 outline）")
        return out
    }

    /** 从 Room 现有订阅生成 OPML 2.0 文本。title 用订阅标题，xmlUrl 是 RSS 直链。 */
    fun build(shows: List<OpmlFeed>): String {
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<opml version=\"2.0\">\n<head>\n")
        sb.append("<title>MoCast 订阅</title>\n")
        sb.append("<dateCreated>").append(date).append("</dateCreated>\n")
        sb.append("</head>\n<body>\n")
        for (show in shows) {
            sb.append("<outline type=\"rss\" text=\"").append(esc(show.title))
                .append("\" title=\"").append(esc(show.title))
                .append("\" xmlUrl=\"").append(esc(show.feedUrl)).append("\"/>\n")
        }
        sb.append("</body>\n</opml>\n")
        return sb.toString()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

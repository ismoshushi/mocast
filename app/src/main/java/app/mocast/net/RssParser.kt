package app.mocast.net

import android.util.Xml
import app.mocast.data.Episode
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ParsedFeed(
    val title: String,
    val author: String,
    val artwork: String,
    val description: String,
    val episodes: List<Episode>
)

object RssParser {

    private val rfc822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }

    fun parse(xml: String, showId: String, source: String): ParsedFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml.toByteArray()), null)

        var title = ""
        var author = ""
        var artwork = ""
        var description = ""
        val items = mutableListOf<Episode>()

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event != XmlPullParser.START_TAG) continue
            when (parser.name.lowercase(Locale.US)) {
                "item", "entry" -> {
                    val ep = readItem(parser, showId, artwork)
                    if (ep != null) items.add(ep)
                }
                "title" -> if (title.isEmpty()) title = readText(parser)
                "itunes:author", "author" -> if (author.isEmpty()) author = readText(parser)
                "itunes:image" -> if (artwork.isEmpty()) artwork = attr(parser, "href")
                "media:thumbnail" -> if (artwork.isEmpty()) artwork = attr(parser, "url")
                "description", "subtitle", "itunes:summary" -> if (description.isEmpty()) description = readText(parser)
            }
        }

        if (title.isEmpty()) title = "未命名节目"
        if (artwork.isEmpty()) artwork = items.firstOrNull()?.artwork.orEmpty()

        return ParsedFeed(
            title = title.trim(),
            author = author.trim().ifEmpty { source },
            artwork = artwork,
            description = stripHtml(description),
            episodes = items.map { it.copy(artwork = it.artwork.ifEmpty { artwork }) }
        )
    }

    private fun readItem(parser: XmlPullParser, showId: String, fallbackArt: String): Episode? {
        var title = ""
        var desc = ""
        var audio = ""
        var pageUrl = ""
        var duration = 0
        var published = 0L
        var guid = ""
        var art = ""
        var transcriptUrl = ""
        var transcriptType = ""

        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.US)) {
                    "title" -> if (title.isEmpty()) title = readText(parser)
                    "description", "itunes:summary", "content:encoded" ->
                        if (desc.isEmpty()) desc = readText(parser)
                    "enclosure" -> {
                        if (audio.isEmpty()) audio = attr(parser, "url")
                    }
                    "media:content" -> {
                        val url = attr(parser, "url")
                        val type = attr(parser, "type")
                        if (audio.isEmpty() && url.isNotEmpty() && type.startsWith("audio")) audio = url
                    }
                    "itunes:duration" -> duration = parseDuration(readText(parser))
                    "pubdate", "published", "updated" -> if (published == 0L) published = parseDate(readText(parser))
                    "guid", "id" -> if (guid.isEmpty()) guid = readText(parser)
                    "link" -> {
                        val href = attr(parser, "href")
                        if (href.isNotEmpty()) pageUrl = href else if (pageUrl.isEmpty()) pageUrl = readText(parser)
                    }
                    "podcast:transcript" -> {
                        transcriptUrl = attr(parser, "url")
                        transcriptType = attr(parser, "type")
                    }
                    "itunes:image" -> if (art.isEmpty()) art = attr(parser, "href")
                    "media:thumbnail" -> if (art.isEmpty()) art = attr(parser, "url")
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase(Locale.US)
                    if (name == "item" || name == "entry") break
                }
            }
        }

        if (title.isBlank() && audio.isBlank()) return null
        val id = stableId(showId, guid.ifEmpty { audio.ifEmpty { pageUrl.ifEmpty { title } } })
        return Episode(
            id = id,
            showId = showId,
            title = stripHtml(title).ifBlank { "未命名单集" },
            description = stripHtml(desc),
            audioUrl = audio,
            pageUrl = pageUrl,
            artwork = art.ifEmpty { fallbackArt },
            durationSec = duration,
            publishedAt = published,
            transcriptUrl = transcriptUrl,
            transcriptType = transcriptType
        )
    }

    private fun attr(parser: XmlPullParser, name: String): String =
        parser.getAttributeValue(null, name)
            ?: parser.getAttributeValue("http://www.w3.org/2005/Atom", name)
            ?: ""

    private fun readText(parser: XmlPullParser): String {
        if (parser.next() == XmlPullParser.TEXT) {
            val text = parser.text ?: ""
            parser.next()
            return text
        }
        var depth = 1
        val sb = StringBuilder()
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text ?: "")
                XmlPullParser.END_TAG -> depth--
            }
        }
        return sb.toString()
    }

    private fun parseDuration(raw: String): Int {
        val t = raw.trim()
        if (t.isEmpty()) return 0
        if (t.all { it.isDigit() }) return t.toIntOrNull() ?: 0
        val parts = t.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    private fun parseDate(raw: String): Long {
        val t = raw.trim()
        if (t.isEmpty()) return 0L
        runCatching { return rfc822.parse(t)?.time ?: 0L }
        runCatching {
            return iso.parse(t.substringBefore("+").substringBefore("Z").take(19))?.time ?: 0L
        }
        return 0L
    }

    fun stableId(showId: String, key: String): String {
        val digest = MessageDigest.getInstance("MD5").digest("$showId|$key".toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        return html
            .replace(Regex("<(script|style)[^>]*>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }
}

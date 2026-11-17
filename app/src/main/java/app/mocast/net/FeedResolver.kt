package app.mocast.net

import org.json.JSONObject

/**
 * 把用户粘贴的东西统一解析成可抓取的 Feed 地址。
 * 支持：RSS 直链、Apple Podcasts、YouTube 频道/播放列表、小宇宙。
 */
object FeedResolver {

    const val SOURCE_RSS = "rss"
    const val SOURCE_APPLE = "apple"
    const val SOURCE_YOUTUBE = "youtube"
    const val SOURCE_XYZ = "xyz"

    data class Resolved(val feedUrl: String, val source: String)

    fun resolve(rawInput: String): Resolved {
        val input = rawInput.trim()
        require(input.isNotEmpty()) { "请输入链接" }

        if (!input.startsWith("http://") && !input.startsWith("https://")) {
            if (input.startsWith("@")) {
                return resolveYouTubeChannel("https://www.youtube.com/$input")
            }
            throw IllegalArgumentException("请粘贴完整链接（http/https 开头）")
        }

        return when {
            input.contains("podcasts.apple.com") -> resolveApple(input)
            input.contains("youtube.com") || input.contains("youtu.be") -> resolveYouTube(input)
            input.contains("xiaoyuzhoufm.com") -> resolveXiaoyuzhou(input)
            else -> Resolved(input, SOURCE_RSS)
        }
    }

    private fun resolveApple(url: String): Resolved {
        val id = Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Apple Podcasts 链接里找不到 id")
        val lookup = "https://itunes.apple.com/lookup?id=$id&entity=podcast"
        val json = JSONObject(Http.get(lookup))
        val results = json.optJSONArray("results")
            ?: throw IllegalArgumentException("Apple 没有返回结果")
        val first = results.optJSONObject(0)
            ?: throw IllegalArgumentException("找不到这个播客")
        val feed = first.optString("feedUrl")
        require(feed.startsWith("http")) { "这个播客没有可用的 RSS" }
        return Resolved(feed, SOURCE_APPLE)
    }

    private fun resolveYouTube(url: String): Resolved {
        val playlistId = Regex("[?&]list=(PL[\\w-]+)").find(url)?.groupValues?.get(1)
        if (playlistId != null) {
            return Resolved(
                "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId",
                SOURCE_YOUTUBE
            )
        }
        val channelInUrl = Regex("/channel/(UC[\\w-]{22})").find(url)?.groupValues?.get(1)
        if (channelInUrl != null) {
            return Resolved("https://www.youtube.com/feeds/videos.xml?channel_id=$channelInUrl", SOURCE_YOUTUBE)
        }
        if (url.contains("watch?v=") || url.contains("youtu.be/")) {
            throw IllegalArgumentException("0.01 暂不支持单个视频，请粘贴频道或播放列表链接")
        }
        return resolveYouTubeChannel(url)
    }

    private fun resolveYouTubeChannel(url: String): Resolved {
        val html = Http.get(url)
        val channelId = Regex("\"channelId\":\"(UC[\\w-]{22})\"").find(html)?.groupValues?.get(1)
            ?: Regex("itemprop=\"channelId\" content=\"(UC[\\w-]{22})\"").find(html)?.groupValues?.get(1)
            ?: Regex("/channel/(UC[\\w-]{22})").find(html)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("没识别出频道 ID，请改用 youtube.com/channel/UC... 链接")
        return Resolved("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId", SOURCE_YOUTUBE)
    }

    private fun resolveXiaoyuzhou(url: String): Resolved {
        val html = Http.get(url)
        val direct = Regex("https://feed\\.xyzfm\\.space/[A-Za-z0-9_-]+").find(html)?.value
        if (direct != null) return Resolved(direct, SOURCE_XYZ)
        val token = Regex("feed\\.xyzfm\\.space/([A-Za-z0-9_-]+)").find(html)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("没识别出小宇宙 RSS，请到节目页复制 RSS 链接")
        return Resolved("https://feed.xyzfm.space/$token", SOURCE_XYZ)
    }
}

package app.mocast.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeoRestrictedException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException

/**
 * 解析失败时抛出的异常，[message] 已经是可直接展示给用户的中文，
 * AppViewModel.friendly() 会原样透传（不要再包一层英文）。
 */
class YouTubeResolveException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * 一次解析的结果。
 *
 * 流地址是 YouTube 给的临时地址（通常几小时内过期），只放内存（UiState），
 * 绝不写数据库、绝不写回 Episode.audioUrl。
 * [videoUrl] 为空表示这个视频只有音频流（[isVideo] 为 false 时播放页不显示视频切换）。
 * [audioUrl] 为空表示只有纯视频流（无音频、无 DASH/HLS 清单）——极少见，
 * 此时音频模式不可用，调用方应强制视频模式（无声画面）。
 */
data class YouTubeStreams(
    val videoUrl: String,
    val audioUrl: String,
    val isVideo: Boolean,
    val title: String = "",
    /** [videoUrl] 是否为 DASH 清单：ExoPlayer 需 setMimeType(APPLICATION_MPD) */
    val videoIsDash: Boolean = false,
    /** [videoUrl] 是否为 HLS 清单：ExoPlayer 需 setMimeType(APPLICATION_M3U8) */
    val videoIsHls: Boolean = false
)

/**
 * YouTube 取流：NewPipe Extractor（TeamNewPipe/NewPipeExtractor v0.27.1，JitPack）。
 *
 * 调用链：ServiceList.YouTube.getStreamExtractor(watchUrl) -> fetchPage()
 * -> getVideoStreams()/getVideoOnlyStreams()/getAudioStreams()/getDashMpdUrl()/getHlsUrl()。
 * 全部是同步阻塞调用，[resolve] 内部切到 Dispatchers.IO，绝不在主线程调。
 *
 * 流选择策略（0.4.0 定）：
 * 1. 视频：优先带音频的合并流（getVideoStreams，YouTube 合并流最高 720p），
 *    取其中 ≤720p 的最高档；没有 ≤720p 时取最低档（能播优先于清晰度）。
 *    没有合并流时依次退到 DASH 清单、HLS 清单、纯视频流。
 *    直播（LIVE_STREAM）流列表常为空，直接走 HLS 清单，其次 DASH 清单。
 * 2. 音频：取平均码率最高的 audio 流（averageBitrate 未知时退到 bitrate）。
 *    没有纯音频流时，音频模式复用合并流/DASH/HLS 的地址（里面本来就有音频）。
 */
object YouTubeStreamResolver {

    /** 视频最高档位：720p。移动端 + 省流量，够字幕学习用。 */
    private const val MAX_VIDEO_HEIGHT = 720

    /** 解析重试次数：首次失败（仅网络错）再试 1 次。解析错不重试。 */
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 800L

    /** 整次解析（含多次请求）的总时限，超时按网络失败处理。 */
    private const val OVERALL_TIMEOUT_MS = 60_000L

    @Volatile
    private var initialized = false

    /**
     * 输入 videoId（11 位）或任意 YouTube 链接（watch / youtu.be / shorts / live / embed），
     * 返回可直接给 ExoPlayer 的流地址。取不到时抛 [YouTubeResolveException]（中文），
     * 绝不静默返回空串。
     */
    suspend fun resolve(videoIdOrUrl: String): YouTubeStreams = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(videoIdOrUrl)
            ?: throw YouTubeResolveException("这个链接里找不到 YouTube 视频 ID")
        ensureInitialized()
        var lastError: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val result = withTimeoutOrNull(OVERALL_TIMEOUT_MS) { extractOnce(videoId) }
                    ?: throw YouTubeResolveException("解析超时，网络太慢或 YouTube 没有响应，请重试")
                return@withContext result
            } catch (e: CancellationException) {
                // 调用方取消（例如用户已离开播放页）：必须原样抛出，不能当成失败重试
                throw e
            } catch (e: IOException) {
                // 只有网络错重试一次；解析错、不存在、地区限制都不重试
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            } catch (e: Exception) {
                throw mapError(e)
            }
        }
        throw mapError(lastError ?: YouTubeResolveException("视频解析失败，请重试"))
    }

    /** 从 watch / youtu.be / shorts / live / embed 链接或裸 videoId 里抠出 11 位 videoId。 */
    fun extractVideoId(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(text)) return text
        return Regex("[?&]v=([A-Za-z0-9_-]{11})").find(text)?.groupValues?.get(1)
            ?: Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(text)?.groupValues?.get(1)
            ?: Regex("/(?:shorts|live|embed|v)/([A-Za-z0-9_-]{11})").find(text)?.groupValues?.get(1)
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            // 内容国家默认 US（用户已搬到美国）；如需跟随系统语言/地区，改这一行即可
            NewPipe.init(
                OkHttpNewPipeDownloader(),
                Localization("en", "US"),
                ContentCountry("US")
            )
            initialized = true
        }
    }

    /**
     * 同步阻塞：内部有多次网络请求，只能在 Dispatchers.IO 上调。
     * 注意 Stream.getUrl() 已废弃，统一用 getContent()（Kotlin 里是 .content）。
     */
    private fun extractOnce(videoId: String): YouTubeStreams {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val title = runCatching { extractor.name }.getOrDefault("")
        val streamType = runCatching { extractor.streamType }.getOrNull()

        val combined = extractor.videoStreams.orEmpty()
            .filter { it.isUrl && !it.videoOnly }
        val videoOnly = extractor.videoOnlyStreams.orEmpty()
            .filter { it.isUrl }
        val audio = extractor.audioStreams.orEmpty()
            .filter { it.isUrl }
        val dashMpdUrl = runCatching { extractor.dashMpdUrl }.getOrDefault("").orEmpty()
        val hlsUrl = runCatching { extractor.hlsUrl }.getOrDefault("").orEmpty()

        val isLive = streamType == StreamType.LIVE_STREAM ||
            streamType == StreamType.AUDIO_LIVE_STREAM

        // ≤720p 里取最高档；没有 ≤720p 时取最低档（能播优先）
        val combinedChoice = combined
            .sortedByDescending { heightOf(it) }
            .let { sorted ->
                sorted.firstOrNull { heightOf(it) <= MAX_VIDEO_HEIGHT } ?: sorted.lastOrNull()
            }
        val videoOnlyChoice = videoOnly
            .sortedByDescending { heightOf(it) }
            .let { sorted ->
                sorted.firstOrNull { heightOf(it) <= MAX_VIDEO_HEIGHT } ?: sorted.lastOrNull()
            }

        val (videoUrl, videoIsDash, videoIsHls) = when {
            isLive && hlsUrl.isNotBlank() -> Triple(hlsUrl, false, true)
            isLive && dashMpdUrl.isNotBlank() -> Triple(dashMpdUrl, true, false)
            combinedChoice != null -> Triple(combinedChoice.content, false, false)
            dashMpdUrl.isNotBlank() -> Triple(dashMpdUrl, true, false)
            hlsUrl.isNotBlank() -> Triple(hlsUrl, false, true)
            // 只剩纯视频流：有画面无声音，调用方应强制视频模式（见类注释）
            videoOnlyChoice != null -> Triple(videoOnlyChoice.content, false, false)
            else -> Triple("", false, false)
        }

        val bestAudio = audio.maxByOrNull { it.effectiveBitrate() }
        val audioUrl = bestAudio?.content
            // 合并流/DASH/HLS 本来就带音频，音频模式可直接播同一条地址
            ?: combinedChoice?.content
            ?: if (videoIsDash || videoIsHls) videoUrl else ""

        if (videoUrl.isBlank() && audioUrl.isBlank()) {
            throw YouTubeResolveException("这个视频没有可用的音视频流，可能已被删除或限制播放")
        }
        return YouTubeStreams(
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            isVideo = videoUrl.isNotBlank(),
            title = title,
            videoIsDash = videoIsDash,
            videoIsHls = videoIsHls
        )
    }

    /** "720p" -> 720；"720p60"/RESOLUTION_UNKNOWN 等解析不到时返回 -1（排最后）。 */
    private fun heightOf(stream: VideoStream): Int {
        val resolution = runCatching { stream.resolution }.getOrDefault("")
        return Regex("^(\\d+)").find(resolution)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    private fun AudioStream.effectiveBitrate(): Int {
        val avg = runCatching { averageBitrate }.getOrDefault(AudioStream.UNKNOWN_BITRATE)
        if (avg > 0) return avg
        return runCatching { bitrate }.getOrDefault(0)
    }

    /** 把 NewPipe/网络异常翻译成中文。顺序重要：子类异常要排在父类（ExtractionException）前面。 */
    private fun mapError(e: Throwable): Exception {
        if (e is YouTubeResolveException) return e
        val msg = e.message.orEmpty()
        return when (e) {
            is GeoRestrictedException ->
                YouTubeResolveException("这个视频在你所在的地区无法播放（地区版权限制）", e)
            is ContentNotAvailableException ->
                YouTubeResolveException("这个视频不存在、已被删除或设为私享", e)
            is AgeRestrictedContentException ->
                YouTubeResolveException("这个视频需要登录验证年龄，暂不支持播放", e)
            is ReCaptchaException ->
                YouTubeResolveException("YouTube 要求验证你是真人，暂时无法解析，请稍后再试", e)
            is ParsingException, is ExtractionException ->
                YouTubeResolveException("YouTube 接口有变化，暂时解析不了这个视频，请等更新后重试", e)
            is IOException ->
                YouTubeResolveException("网络连不上，检查网络或代理后重试", e)
            else -> when {
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true) ->
                    YouTubeResolveException("网络连不上，检查网络或代理后重试", e)
                else -> YouTubeResolveException("视频解析失败，请重试", e)
            }
        }
    }
}

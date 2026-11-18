package app.mocast.download

import android.content.Context
import java.io.File

/**
 * 下载文件的落盘规则（0.4.0）。
 *
 * 只放应用私有目录 filesDir/downloads/，不放公开 Download/ 目录，
 * 卸载即清，不需要存储权限。
 */
object DownloadFiles {

    private const val DIR_NAME = "downloads"

    /** 允许的音频后缀；audioUrl 里取不到时默认 mp3。 */
    private val ALLOWED_EXT = setOf("mp3", "m4a", "mp4", "aac", "ogg", "opus")

    fun downloadsDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * episodeId 只保留 [A-Za-z0-9_-]，其余字符一律换成下划线，
     * 防止 RSS 里的 id 含 "/"、"?" 等路径穿越/非法字符。
     */
    fun sanitizeEpisodeId(episodeId: String): String {
        val clean = episodeId.replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')
        return clean.ifBlank { "episode" }
    }

    /** 从 audioUrl 末尾取后缀（去掉 ? 查询串）；不在白名单里就回落 mp3。 */
    fun extensionFromUrl(audioUrl: String): String {
        val path = audioUrl.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in ALLOWED_EXT) ext else "mp3"
    }

    /** 单集的唯一本地文件：filesDir/downloads/<episodeId>.<ext> */
    fun fileFor(context: Context, episodeId: String, audioUrl: String): File =
        File(
            downloadsDir(context),
            "${sanitizeEpisodeId(episodeId)}.${extensionFromUrl(audioUrl)}"
        )
}

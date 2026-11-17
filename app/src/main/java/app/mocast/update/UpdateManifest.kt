package app.mocast.update

import org.json.JSONObject
import java.net.URI

/** 一次发布的内容：版本、更新说明、安装包位置与校验值。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long
)

/**
 * update.json 的解析。格式和涛声一致，只是去掉增量包：
 *
 * {
 *   "versionCode": 300,
 *   "versionName": "0.3.0",
 *   "notes": "更新说明，可多行",
 *   "apk": { "url": "app-release.apk", "sha256": "64 位十六进制", "size": 20851234 }
 * }
 *
 * apk.url 可以是绝对地址，也可以相对 update.json 所在目录。
 */
object UpdateManifest {

    fun parse(text: String, manifestUrl: String): UpdateInfo {
        val root = JSONObject(text)
        val code = when (val v = root.opt("versionCode")) {
            is Int -> v
            is Long -> v.toInt()
            else -> error("versionCode 缺失")
        }
        require(code in 1..Int.MAX_VALUE) { "versionCode 不合法" }
        val name = root.optString("versionName").trim()
        require(name.matches(Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?"))) { "versionName 需为 0.3.0 这种格式" }
        val notes = root.optString("notes").trim()
        val apk = root.getJSONObject("apk")
        val rawUrl = apk.optString("url").trim()
        require(rawUrl.isNotEmpty()) { "apk.url 缺失" }
        val url = resolve(manifestUrl, rawUrl)
        require(url.startsWith("http://") || url.startsWith("https://")) { "apk.url 必须是 http(s)" }
        val sha = apk.optString("sha256").trim().lowercase()
        require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' }) { "sha256 不合法" }
        val size = when (val v = apk.opt("size")) {
            is Int -> v.toLong()
            is Long -> v
            else -> 0L
        }
        return UpdateInfo(code, name, notes, url, sha, size.coerceAtLeast(0))
    }

    private fun resolve(base: String, url: String): String = runCatching {
        if (url.startsWith("http://") || url.startsWith("https://")) url
        else URI(base).resolve(url).toString()
    }.getOrDefault(url)
}

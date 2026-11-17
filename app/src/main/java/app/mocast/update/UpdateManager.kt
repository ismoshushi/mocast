package app.mocast.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.mocast.BuildConfig
import app.mocast.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val doneBytes: Long, val totalBytes: Long) : UpdateState
    data class Ready(val info: UpdateInfo) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateState
}

/**
 * 只做手动更新：关于页点「检查更新」，有新版就下载并按 sha256 校验，
 * 校验通过后直接拉起系统安装器；不做自动检查、不做后台通知。
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    private val gate = Mutex()
    private var manifest: UpdateInfo? = null
    private var readyApk: File? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    private val updatesDir: File get() = File(context.filesDir, "updates")

    private val downloadClient by lazy {
        Http.client.newBuilder().readTimeout(300, TimeUnit.SECONDS).build()
    }

    fun check() {
        scope.launch { checkNow() }
    }

    private suspend fun checkNow() = withContext(Dispatchers.IO) {
        gate.withLock {
            val now = System.currentTimeMillis()
            _state.value = UpdateState.Checking
            try {
                val info = UpdateManifest.parse(
                    Http.get(DEFAULT_SOURCE, "application/json"),
                    DEFAULT_SOURCE
                )
                if (info.versionCode <= currentVersionCode) {
                    clearRelease()
                    _state.value = UpdateState.UpToDate(now)
                } else {
                    manifest = info
                    _state.value = UpdateState.Available(info)
                }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(friendly(e), manifest)
            }
        }
    }

    fun download() {
        scope.launch {
            gate.withLock {
                val info = manifest ?: return@withLock
                if (_state.value is UpdateState.Downloading) return@withLock
                val dir = updatesDir.apply { mkdirs() }
                val part = File(dir, "${info.sha256}.part")
                val target = File(dir, "${info.sha256}.apk")
                part.delete()
                _state.value = UpdateState.Downloading(info, 0L, info.sizeBytes)
                try {
                    withContext(Dispatchers.IO) {
                        val request = Request.Builder()
                            .url(info.apkUrl)
                            .header("User-Agent", "MoCast/${BuildConfig.VERSION_NAME}")
                            .header("Accept", "*/*")
                            .build()
                        downloadClient.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            val body = resp.body ?: throw IOException("空响应")
                            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                            var done = 0L
                            var lastShown = 0L
                            body.byteStream().use { input ->
                                FileOutputStream(part).use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        out.write(buffer, 0, read)
                                        done += read
                                        val now = System.currentTimeMillis()
                                        if (now - lastShown > 200 || done == total) {
                                            lastShown = now
                                            _state.value = UpdateState.Downloading(
                                                info,
                                                done,
                                                if (total > 0) total else done
                                            )
                                        }
                                    }
                                    out.flush()
                                }
                            }
                        }
                    }
                    val hash = part.sha256()
                    if (hash != info.sha256) {
                        part.delete()
                        _state.value = UpdateState.Failed("下载的安装包不完整，再试一次", info)
                        return@withLock
                    }
                    target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    readyApk = target
                    if (canInstall()) install() else _state.value = UpdateState.Ready(info)
                } catch (e: Exception) {
                    part.delete()
                    _state.value = UpdateState.Failed(friendly(e), info)
                }
            }
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install() {
        val info = manifest ?: return
        val apk = readyApk?.takeIf { it.isFile } ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { _state.value = UpdateState.Installing(info) }
            .onFailure { _state.value = UpdateState.Failed("打不开系统安装器，检查安装权限", info) }
    }

    private fun clearRelease() {
        manifest = null
        readyApk = null
        runCatching { updatesDir.listFiles()?.forEach { it.deleteRecursively() } }
    }

    private fun friendly(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            message.startsWith("HTTP 404") -> "仓库里的 update.json 还没放好（404）"
            message.startsWith("HTTP 403") -> "更新源拒绝了请求（403）"
            message.startsWith("HTTP 5") -> "更新服务器出错，稍后再试"
            message.contains("Unable to resolve host") || message.contains("Failed to connect") ->
                "连不上更新服务器，检查网络或代理"
            message.startsWith("version") || message.startsWith("apk") || message.startsWith("sha256") ->
                "更新说明格式不对：$message"
            message.isBlank() -> "检查更新失败，稍后再试"
            else -> message
        }
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 仓库根目录的 update.json；换分支或换仓库只改这一行 */
        const val DEFAULT_SOURCE =
            "https://raw.githubusercontent.com/ismoshushi/mocast/master/update.json"
    }
}

/** 设置页「版本更新」右侧那一小段 */
fun updateSummary(current: String, state: UpdateState): Pair<String, Boolean> = when (state) {
    is UpdateState.Available -> "${state.info.versionName} · 有新版本" to true
    is UpdateState.Ready -> "${state.info.versionName} · 待安装" to true
    is UpdateState.Downloading -> {
        val percent = if (state.totalBytes > 0) state.doneBytes * 100 / state.totalBytes else 0
        "正在下载 $percent%" to true
    }
    is UpdateState.Installing -> "正在安装" to true
    is UpdateState.Failed -> state.info?.let { "${it.versionName} · 待重试" to true } ?: (current to false)
    is UpdateState.UpToDate -> "$current · 已是最新" to false
    else -> current to false
}

/** 「0.03」原样返回；这里留给以后的预览版命名，比如 0.04-preview.1 → 0.04 预览版 1 */
fun displayVersion(version: String): String =
    Regex("""^(.+)-preview\.(\d+)$""").matchEntire(version)
        ?.let { "${it.groupValues[1]} 预览版 ${it.groupValues[2]}" } ?: version

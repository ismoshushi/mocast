package app.mocast.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.mocast.data.room.DownloadEntity
import app.mocast.data.Episode
import app.mocast.data.SettingsStore
import app.mocast.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Room DownloadEntity.state 的取值，与 Room 任务约定一致。 */
object DownloadState {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

/** UI 与通知共用的单集下载进度（Room 实体之外的轻量快照）。 */
data class DownloadProgress(
    val episodeId: String,
    val state: String,
    val bytesDownloaded: Long = 0L,
    /** 总字节数；服务器没给 Content-Length 时为 -1，只显示已下载字节数。 */
    val totalBytes: Long = -1L,
    val error: String? = null
) {
    /** 总大小已知时 0..100，未知时 -1。 */
    val percent: Int
        get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else -1

    val isActive: Boolean
        get() = state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED
}

/**
 * 下载管理器（0.4.0）。
 *
 * 职责：串行下载队列（一次一个，避免多路抢带宽）、断点续传、
 * 仅 Wi-Fi 门禁、状态写 Room、进度节流上报。
 *
 * - 实际下载走 [Http.client]，所以设置里的代理对下载同样生效。
 * - 取消只停协程、关流，保留已下载部分与 Room 记录，状态记 CANCELLED，
 *   之后点“继续”等同于断点续传；只有用户点“删除”才删文件 + 删记录。
 * - 前台 Service 的启停由本类负责（enqueue 时拉起 DownloadService），
 *   Service 只负责把进度画进通知栏，不持有下载逻辑。
 *
 * ASR 钩子：下载完成时调用 [onDownloadCompleted]，ASR 任务在
 * MoCastApp 或自己的初始化里给它赋值即可，本类不实现识别。
 */
class DownloadManager(
    context: Context,
    private val store: DownloadStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val appContext = context.applicationContext
    private val settingsStore = SettingsStore(appContext)

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val progressFlow: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    /** 下载完成钩子（ASR 任务接线用，默认空）。 */
    var onDownloadCompleted: ((episodeId: String) -> Unit)? = null

    private data class Request(
        val episodeId: String,
        val showId: String,
        val title: String,
        val audioUrl: String
    )

    private val queue = ArrayDeque<Request>()
    private var activeId: String? = null
    private var activeJob: Job? = null
    private val lock = Any()

    init {
        scope.launch { refreshFromStore() }
    }

    // ------------------------------------------------------------------ 对外 API

    fun enqueue(episode: Episode) =
        enqueue(episode.id, episode.showId, episode.title, episode.audioUrl)

    /** 入队（幂等：已在队列或正在下载时直接返回）。入队即拉起前台 Service。 */
    fun enqueue(episodeId: String, showId: String, title: String, audioUrl: String) {
        if (episodeId.isBlank() || audioUrl.isBlank()) return
        synchronized(lock) {
            if (activeId == episodeId || queue.any { it.episodeId == episodeId }) return
            queue.addLast(Request(episodeId, showId, title, audioUrl))
        }
        // 同步先占位 QUEUED（字节数随后由 IO 协程用 Room 真实值覆盖）：
        // DownloadService 的 collector 靠它判断“还有活”，否则服务可能在
        // Room 写回之前看到空 map 而误停。
        _progress.update {
            it + (episodeId to (it[episodeId] ?: DownloadProgress(episodeId, DownloadState.QUEUED)))
        }
        scope.launch {
            // 入队协程是异步的：若用户在它跑起来之前就点了取消，
            // 队列里已无此任务，直接返回，别再把 QUEUED 写回 Room。
            val stillQueued = synchronized(lock) {
                queue.any { it.episodeId == episodeId } || activeId == episodeId
            }
            if (!stillQueued) return@launch
            val existing = runCatching { store.getDownload(episodeId) }.getOrNull()
            writeState(
                Request(episodeId, showId, title, audioUrl),
                DownloadState.QUEUED,
                existing?.bytesDownloaded ?: 0L,
                existing?.totalBytes ?: -1L,
                existing?.createdAt ?: System.currentTimeMillis()
            )
            pump()
        }
        // 用户点下载是前台操作，拉起前台 Service 合规；Service 随后接管通知。
        runCatching {
            appContext.startForegroundService(DownloadService.startIntent(appContext, episodeId, showId, title, audioUrl))
        }
    }

    /** 取消正在下载/排队的任务：停协程、关流，保留已下载部分以便续传。 */
    fun cancel(episodeId: String) {
        var wasActive = false
        synchronized(lock) {
            queue.removeAll { it.episodeId == episodeId }
            if (activeId == episodeId) {
                wasActive = true
                activeJob?.cancel()
            }
        }
        // 排队中被取消的没有协程在跑，这里直接写状态；正在下载的由
        // runDownload 的 CancellationException 分支写状态，两处写同一值无害。
        if (!wasActive) {
            scope.launch {
                val existing = runCatching { store.getDownload(episodeId) }.getOrNull()
                // 只对“还没下完”的记录写 CANCELLED；已完成/已失败的不动，
                // 避免误触把 COMPLETED 覆盖掉。
                if (existing != null && existing.state != DownloadState.COMPLETED &&
                    existing.state != DownloadState.FAILED
                ) {
                    writeState(
                        Request(episodeId, existing.showId, existing.title, ""),
                        DownloadState.CANCELLED,
                        existing.bytesDownloaded,
                        existing.totalBytes,
                        existing.createdAt
                    )
                } else {
                    _progress.update { it - episodeId }
                }
            }
        }
    }

    /**
     * 删除：停任务、删本地文件、删 Room 记录。
     *
     * 顺序很关键：先等正在跑的协程真正停下来（它的 CancellationException
     * 分支会写一条 CANCELLED），再删文件 + 删记录，否则那条 CANCELLED
     * 会在 removeDownload 之后落库，把已删的记录复活。
     * 若删除等待期间用户又重新点了下载，则放弃删除。
     */
    fun delete(episodeId: String) {
        val jobToWait: Job?
        synchronized(lock) {
            queue.removeAll { it.episodeId == episodeId }
            jobToWait = if (activeId == episodeId) activeJob else null
            jobToWait?.cancel()
        }
        scope.launch {
            runCatching { jobToWait?.join() }
            val requeued = synchronized(lock) {
                activeId == episodeId || queue.any { it.episodeId == episodeId }
            }
            if (requeued) return@launch
            val existing = runCatching { store.getDownload(episodeId) }.getOrNull()
            existing?.localPath?.takeIf { it.isNotBlank() }?.let { File(it).delete() }
            runCatching { store.removeDownload(episodeId) }
            _progress.update { it - episodeId }
        }
    }

    /** 供 AppViewModel.bindPlayer 用：已完成且文件真实存在才返回本地文件。 */
    fun downloadedFile(episodeId: String, audioUrl: String): File? {
        if (_progress.value[episodeId]?.state != DownloadState.COMPLETED) return null
        val file = DownloadFiles.fileFor(appContext, episodeId, audioUrl)
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    /** 当前是否在 Wi-Fi 上（需要 ACCESS_NETWORK_STATE，Manifest 已有）。 */
    fun isWifiConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ------------------------------------------------------------------ 内部

    /** 从 Room 恢复进度快照；上次进程被杀时卡在 DOWNLOADING 的记录先回 PAUSED。 */
    private suspend fun refreshFromStore() {
        val all = runCatching { store.listDownloads() }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val map = mutableMapOf<String, DownloadProgress>()
        for (e in all) {
            var state = e.state
            if (state == DownloadState.DOWNLOADING) {
                state = DownloadState.PAUSED
                runCatching { store.upsertDownload(e.copy(state = state, updatedAt = now)) }
            }
            map[e.episodeId] = DownloadProgress(e.episodeId, state, e.bytesDownloaded, e.totalBytes)
        }
        _progress.value = map
    }

    /** 串行泵：一次只跑一个下载，跑完自动接下一个排队的。 */
    private fun pump() {
        val req: Request
        synchronized(lock) {
            if (activeJob?.isActive == true) return
            req = queue.removeFirstOrNull() ?: return
            activeId = req.episodeId
            activeJob = scope.launch {
                try {
                    runDownload(req)
                } finally {
                    synchronized(lock) {
                        if (activeId == req.episodeId) activeId = null
                    }
                    pump()
                }
            }
        }
    }

    /**
     * 单集下载（含断点续传）。
     *
     * Range 语义：
     * - 本地已有 N>0 字节 → 发 `Range: bytes=N-`；服务器回 206 则追加写；
     * - 回 200 说明服务器不支持/忽略 Range → 删旧文件从头写；
     * - 回 416 说明已下完（Range 起点超出文件）→ 直接记 COMPLETED。
     * - Content-Length 取不到时 totalBytes=-1，进度只显示已下载字节数。
     * 进度每 256KB 或 1 秒才写一次 Room + 发一次 flow，不逐字节写库。
     */
    private suspend fun runDownload(req: Request) {
        // 仅 Wi-Fi 门禁：开始/续传前各查一次（中途断 Wi-Fi 会在读流时失败进 FAILED，保留断点）。
        if (settingsStore.load().downloadWifiOnly && !isWifiConnected()) {
            val existing = runCatching { store.getDownload(req.episodeId) }.getOrNull()
            writeState(
                req, DownloadState.PAUSED,
                existing?.bytesDownloaded ?: 0L,
                existing?.totalBytes ?: -1L,
                existing?.createdAt ?: System.currentTimeMillis(),
                error = "仅 Wi-Fi 已开启，等待 Wi-Fi 后继续"
            )
            return
        }

        val file = DownloadFiles.fileFor(appContext, req.episodeId, req.audioUrl)
        val existing = runCatching { store.getDownload(req.episodeId) }.getOrNull()
        val createdAt = existing?.createdAt ?: System.currentTimeMillis()
        var downloaded = if (file.exists()) file.length() else 0L
        var total = existing?.totalBytes ?: -1L

        writeState(req, DownloadState.DOWNLOADING, downloaded, total, createdAt)

        try {
            val request = okhttp3.Request.Builder()
                .url(req.audioUrl)
                .header("User-Agent", Http.USER_AGENT)
                .apply { if (downloaded > 0) header("Range", "bytes=$downloaded-") }
                .build()

            Http.client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    200 -> {
                        // 服务器不支持 Range：旧文件作废，从头写。
                        if (downloaded > 0) {
                            file.delete()
                            downloaded = 0L
                        }
                    }
                    206 -> { /* 追加写，downloaded 保持 */ }
                    416 -> {
                        // Range 起点已超文件尾：视为已下完。
                        finishCompleted(req, file, createdAt)
                        return
                    }
                    else -> {
                        writeState(
                            req, DownloadState.FAILED, downloaded, total, createdAt,
                            error = "下载失败（HTTP ${resp.code}）"
                        )
                        return
                    }
                }

                val body = resp.body
                if (body == null) {
                    writeState(req, DownloadState.FAILED, downloaded, total, createdAt, error = "下载失败（空响应）")
                    return
                }
                val remaining = body.contentLength() // -1 表示服务器没给长度
                if (remaining >= 0) {
                    total = if (resp.code == 206) downloaded + remaining else remaining
                }
                val append = resp.code == 206

                body.byteStream().use { input ->
                    FileOutputStream(file, append).use { out ->
                        val buf = ByteArray(8192)
                        var lastFlush = System.currentTimeMillis()
                        var sinceFlush = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            sinceFlush += n
                            val now = System.currentTimeMillis()
                            if (sinceFlush >= 256 * 1024 || now - lastFlush >= 1000) {
                                writeState(req, DownloadState.DOWNLOADING, downloaded, total, createdAt)
                                sinceFlush = 0L
                                lastFlush = now
                            }
                        }
                        out.flush()
                    }
                }

                // 服务器提前断流也会走到这里：长度对不上就记失败、保留断点等重试。
                if (total > 0 && file.length() < total) {
                    writeState(
                        req, DownloadState.FAILED, file.length(), total, createdAt,
                        error = "下载不完整，已保留部分文件，可重试续传"
                    )
                    return
                }
                finishCompleted(req, file, createdAt)
            }
        } catch (e: CancellationException) {
            // 用户取消：停协程、关流（use 块已关），保留已下载部分，记 CANCELLED 以便续传。
            writeState(req, DownloadState.CANCELLED, file.length(), total, createdAt)
            throw e
        } catch (e: IOException) {
            writeState(req, DownloadState.FAILED, file.length(), total, createdAt, error = friendly(e))
        }
    }

    private suspend fun finishCompleted(req: Request, file: File, createdAt: Long) {
        val len = file.length()
        writeState(req, DownloadState.COMPLETED, len, len, createdAt)
        onDownloadCompleted?.invoke(req.episodeId)
    }

    /** 同步写 Room + 发进度 flow；Room 写失败不阻塞 UI 进度（只记 flow）。 */
    private suspend fun writeState(
        req: Request,
        state: String,
        downloaded: Long,
        total: Long,
        createdAt: Long,
        error: String? = null
    ) {
        val now = System.currentTimeMillis()
        val file = if (req.audioUrl.isNotBlank()) {
            DownloadFiles.fileFor(appContext, req.episodeId, req.audioUrl)
        } else {
            runCatching { store.getDownload(req.episodeId) }
                .getOrNull()?.localPath?.let { File(it) }
        }
        runCatching {
            store.upsertDownload(
                DownloadEntity(
                    episodeId = req.episodeId,
                    showId = req.showId,
                    title = req.title,
                    localPath = file?.absolutePath.orEmpty(),
                    state = state,
                    bytesDownloaded = downloaded,
                    totalBytes = total,
                    createdAt = createdAt,
                    updatedAt = now
                )
            )
        }
        _progress.update {
            it + (req.episodeId to DownloadProgress(req.episodeId, state, downloaded, total, error))
        }
    }

    private fun friendly(e: IOException): String {
        val message = e.message.orEmpty()
        return when {
            message.contains("Unable to resolve host") || message.contains("Failed to connect") ->
                "网络连不上，检查网络或代理"
            message.isBlank() -> "下载失败，请重试"
            else -> "下载失败：$message"
        }
    }

    companion object {
        /** 供 Service 判断“是否还有活要干”时用（本类内部未直接用，留给调用方）。 */
        @Suppress("unused")
        fun isActiveState(state: String): Boolean =
            state == DownloadState.DOWNLOADING || state == DownloadState.QUEUED
    }
}

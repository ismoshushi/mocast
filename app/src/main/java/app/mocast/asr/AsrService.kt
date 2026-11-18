package app.mocast.asr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import app.mocast.MainActivity
import app.mocast.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 本机 ASR 前台 Service。
 *
 * 为什么是前台 Service 而不是 WorkManager（决策记录）：
 * - 这是用户当场点的“生成字幕”，60 分钟播客要跑 20~40 分钟：WorkManager 的加急任务
 *   10 分钟配额不够、普通任务会被 Doze 推迟，用户点了却迟迟没结果；
 * - 需要实时进度 + 随时取消：前台 Service 自带通知栏进度条与取消按钮，WorkManager
 *   的进度要经过 WorkInfo 轮询，取消也要多走一层；
 * - 代价是常驻通知 + 耗电更直观，用户看得见、随时能停，符合“长任务要可见、可取消”。
 * 被系统杀掉后不自动重跑（START_NOT_STICKY）：进度已按段落盘，下次点“生成 AI 字幕”
 * 从最后一段续跑，不从头来。
 *
 * 约定（不要改接口名，ViewModel / 下载任务都按这个调）：
 * - 音频文件：context.filesDir/downloads/<episodeId>.<ext>，扩展名未知，用
 *   [resolveAudioFile] 按前缀扫描；文件不存在时不启动、不报错、不静默失败——
 *   调用方（PlayerScreen 的按钮 / 下载钩子）要给用户一句明确的中文提示。
 * - 模型与语言：按 Settings.asrModel（默认 base）与 Settings.asrWifiOnly（默认 true）。
 * - 增量落盘：每识别完一段就把“已完成全部段”全量覆盖写进 Room（AsrTranscriptStore），
 *   取消/被杀后 loadAsrTranscript 读到的就是断点，resumeFromMs = 已有段的最大 endMs。
 * - RoomStore 由谁建、谁传？Service 自己不建数据库：MoCastApp（或 AppViewModel）在
 *   首次 start 前调 [bindStore] 注入一次 RoomStore 的实现。没注入就启动时抛中文错，
 *   不要静默跑一个写不进 Room 的任务。
 */
class AsrService : Service() {

    companion object {
        const val ACTION_START = "app.mocast.asr.START"
        const val ACTION_CANCEL = "app.mocast.asr.CANCEL"
        const val EXTRA_EPISODE_ID = "episode_id"

        private const val CHANNEL_ID = "mocast_asr"
        private const val NOTIFICATION_ID = 2002
        private const val NOTIF_UPDATE_MIN_INTERVAL_MS = 1000L
        private const val WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 小时兜底，防杀不掉

        private val _state = MutableStateFlow<AsrJobState?>(null)
        /** UI 订阅这个：PlayerScreen 的进度条 / 取消按钮 / 完成刷新都看它。 */
        val state: StateFlow<AsrJobState?> = _state.asStateFlow()

        private var store: AsrTranscriptStore? = null

        /** 应用启动时注入一次（Room 任务提供 RoomStore 后由 MoCastApp 调）。 */
        fun bindStore(s: AsrTranscriptStore) {
            store = s
        }

        /** 当前是否正在给这一集跑 ASR（UI 用来决定按钮是“生成”还是“生成中…”）。 */
        fun isRunning(episodeId: String): Boolean {
            val s = _state.value ?: return false
            return s.episodeId == episodeId &&
                (s.phase == AsrPhase.RUNNING || s.phase == AsrPhase.DOWNLOADING_MODEL)
        }

        fun start(context: Context, episodeId: String) {
            val intent = Intent(context, AsrService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_EPISODE_ID, episodeId)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, AsrService::class.java).apply { action = ACTION_CANCEL }
            context.startService(intent)
        }

        /**
         * 下载完成钩子（下载任务的 onDownloadCompleted 里调这个，不要直接调 start）。
         * 只有同时满足才自动开始：设置里开了自动生成 && 这一集没有官方字幕 &&
         * 还没有 ASR 缓存 && 本地音频已存在。任一不满足返回 false，不启动、不报错。
         *
         * @param hasOfficialTranscript 这一集 transcriptUrl 是否非空（下载任务调 FeedRepository
         *   时就知道，不要在这里再去拉 RSS）。
         * @param hasCachedAsr Room 里是否已有这集的 ASR 字幕（loadAsrTranscript != null）。
         */
        fun startIfNeeded(
            context: Context,
            episodeId: String,
            settings: Settings,
            hasOfficialTranscript: Boolean,
            hasCachedAsr: Boolean
        ): Boolean {
            if (!settings.asrAuto) return false
            if (hasOfficialTranscript) return false
            if (hasCachedAsr) return false
            if (resolveAudioFile(context, episodeId) == null) return false
            if (isRunning(episodeId)) return false
            start(context, episodeId)
            return true
        }

        /**
         * 按 filesDir/downloads/<episodeId>.<ext> 找本地音频。扩展名下载时才定（mp3/m4a/…），
         * 所以按 "$episodeId." 前缀扫描；命中多个取最大的那个（防重下残留）。
         */
        fun resolveAudioFile(context: Context, episodeId: String): File? {
            val dir = File(context.filesDir, "downloads")
            if (!dir.exists()) return null
            return dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("$episodeId.") }
                ?.maxByOrNull { it.length() }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var currentEpisodeId: String? = null
    private var lastNotifUpdate = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                _state.value = _state.value?.copy(phase = AsrPhase.IDLE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID).orEmpty()
                if (episodeId.isBlank()) return START_NOT_STICKY
                // 已在跑同一集：重复点开始直接忽略，不要开第二个 recognizer 抢内存。
                if (isRunning(episodeId)) return START_NOT_STICKY
                job?.cancel()
                currentEpisodeId = episodeId
                startForegroundCompat(buildNotification("正在准备识别…", 0f, indeterminate = true))
                job = serviceScope.launch { runJob(episodeId) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runJob(episodeId: String) {
        val store = store
        if (store == null) {
            fail(episodeId, "字幕存储还没准备好，重启 App 再试一次")
            return
        }
        val audioFile = resolveAudioFile(this, episodeId)
        if (audioFile == null || !audioFile.exists()) {
            fail(episodeId, "本地音频还没下载好，先下载这一集再生成字幕")
            return
        }
        // 设置每次现读（用户可能在跑的过程中去设置里换了模型档位，以开工瞬间为准）。
        val settings = app.mocast.data.SettingsStore(this).load()
        val model = AsrModel.from(settings.asrModel)
        val modelManager = AsrModelManager(this)
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoCast:Asr")
            .apply { acquire(WAKELOCK_TIMEOUT_MS) }
        this.wakeLock = wakeLock
        try {
            _state.value = AsrJobState(episodeId, AsrPhase.DOWNLOADING_MODEL, downloadProgress = 0f)
            val modelFiles = modelManager.ensureModel(model, settings.asrWifiOnly) { p ->
                // p == -1f 表示下载完成、正在解压；UI 用“正在解压模型…”文案。
                _state.value = AsrJobState(
                    episodeId, AsrPhase.DOWNLOADING_MODEL, downloadProgress = p.coerceIn(0f, 1f)
                )
                updateNotificationThrottled(
                    if (p < 0f || p >= 1f) "正在解压模型…" else "正在下载模型 ${(p * 100).toInt()}%",
                    p.coerceIn(0f, 1f)
                )
            }
            // 断点：已有段的最大 endMs；没有缓存就是从头跑。
            val cached = runCatching { store.loadAsrTranscript(episodeId) }.getOrNull().orEmpty()
            val resumeFromMs = cached.maxOfOrNull { it.endMs } ?: 0L
            val doneCues = cached.sortedBy { it.startMs }.toMutableList()
            _state.value = AsrJobState(episodeId, AsrPhase.RUNNING, doneMs = resumeFromMs)
            val engine = SherpaAsrEngine(modelFiles)
            try {
                engine.transcribe(
                    audioFile = audioFile,
                    language = "auto",
                    resumeFromMs = resumeFromMs,
                    onProgress = { doneMs, totalMs ->
                        _state.value = AsrJobState(episodeId, AsrPhase.RUNNING, doneMs, totalMs)
                        val progress = if (totalMs > 0) doneMs.toFloat() / totalMs else 0f
                        updateNotificationThrottled("正在生成字幕…", progress)
                    },
                    onSegment = { cue ->
                        doneCues.add(cue)
                        // 增量落盘：全量覆盖写，幂等；下一段来之前崩了，已有的段都在。
                        runCatching {
                            store.saveAsrTranscript(episodeId, model.key, "auto", doneCues.toList())
                        }
                    }
                )
            } finally {
                engine.release()
            }
            if (doneCues.isEmpty()) {
                fail(episodeId, "这一集没识别出有效语音（可能全是音乐或静音）")
                return
            }
            runCatching { store.saveAsrTranscript(episodeId, model.key, "auto", doneCues.toList()) }
            val totalMs = doneCues.maxOf { it.endMs }
            _state.value = AsrJobState(episodeId, AsrPhase.DONE, doneMs = totalMs, totalMs = totalMs)
            updateNotification("字幕生成完了", 1f)
        } catch (e: CancellationException) {
            // 用户点了取消：不算错，状态回 IDLE，Service 停掉；已落盘的段就是下次的断点。
            _state.value = _state.value?.copy(phase = AsrPhase.IDLE)
            throw e
        } catch (e: AsrException) {
            fail(episodeId, e.message ?: "识别失败，请重试")
        } catch (e: Exception) {
            fail(episodeId, "识别失败：${e.message ?: "未知错误"}，请重试")
        } finally {
            runCatching { wakeLock.release() }
            this.wakeLock = null
            stopSelf()
        }
    }

    private fun fail(episodeId: String, message: String) {
        _state.value = AsrJobState(episodeId, AsrPhase.ERROR, error = message)
        updateNotification("字幕生成失败", 0f)
        stopSelf()
    }

    // ---- 通知栏 ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, "AI 字幕", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "本机语音识别生成字幕的进度" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, AsrService::class.java).apply { action = ACTION_CANCEL }
        return PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(text: String, progress: Float, indeterminate: Boolean = false): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(app.mocast.R.drawable.ic_stat_mocast)
            .setContentTitle("MoCast · AI 字幕")
            .setContentText(text)
            .setContentIntent(contentPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "取消", cancelPendingIntent())
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        }
        return builder.build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, progress: Float) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text, progress))
        }
    }

    /** 通知栏 1 秒最多刷一次：识别进度回调很密，不节流会拖慢主线程。 */
    private fun updateNotificationThrottled(text: String, progress: Float) {
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate < NOTIF_UPDATE_MIN_INTERVAL_MS) return
        lastNotifUpdate = now
        updateNotification(text, progress)
    }

    override fun onDestroy() {
        job?.cancel()
        serviceScope.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }
}

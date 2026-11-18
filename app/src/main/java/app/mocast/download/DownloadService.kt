package app.mocast.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.mocast.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 下载前台 Service（0.4.0）。
 *
 * 只负责“把进度画进通知栏 + 保活下载进程”：
 * - 收到 ACTION_START 后 5 秒内必须 startForeground，否则系统会杀掉服务；
 * - 进度来自 DownloadManager.progressFlow，本类不持有任何下载逻辑；
 * - 队列为空且无进行中下载时自动 stopSelf；
 * - 通知上的“取消”按钮直接发 ACTION_CANCEL 回本服务。
 *
 * 为什么不用 WorkManager：下载是大文件、长耗时、要精确进度 + 断点续传 +
 * 用户随时取消；WorkManager 更适合系统调度的后台小任务，进度回调与
 * 取消粒度都弱于前台 Service，且 doze 下的重试语义不适合播客音频。
 * 代价：下载期间常驻一条通知（Android 的要求），且应用被划掉后
 * Service 随进程走，断点由 Room + 本地文件保证，下次进应用可续传。
 */
class DownloadService : Service() {

    private lateinit var manager: DownloadManager
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    /** 通知标题用的单集标题（进程被杀后重建时拿不到，就回落成“正在下载”）。 */
    private val titles = mutableMapOf<String, String>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        manager = (application as app.mocast.MoCastApp).downloadManager
        serviceScope.launch {
            // seenActive 守卫：只在“曾经见过进行中下载、随后变空”时才停服务。
            // 否则 onCreate 后、onStartCommand 入队前的首次空 map 会把刚起来的服务误停。
            var seenActive = false
            manager.progressFlow.collect { map ->
                val active = map.values.firstOrNull { it.isActive }
                if (active == null) {
                    if (seenActive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@collect
                }
                seenActive = true
                val title = titles[active.episodeId] ?: "正在下载"
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(active, title))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: episodeId
                titles[episodeId] = title
                // 先占位通知、再入队：startForeground 必须在收到 intent 后尽快调。
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        DownloadProgress(episodeId, DownloadState.QUEUED),
                        title
                    )
                )
                manager.enqueue(
                    episodeId,
                    intent.getStringExtra(EXTRA_SHOW_ID).orEmpty(),
                    title,
                    intent.getStringExtra(EXTRA_AUDIO_URL).orEmpty()
                )
            }
            ACTION_CANCEL -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return START_NOT_STICKY
                manager.cancel(episodeId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "单集下载进度" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(progress: DownloadProgress, title: String): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            progress.episodeId.hashCode(),
            cancelIntent(this, progress.episodeId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            progress.state == DownloadState.QUEUED -> "排队中…"
            progress.totalBytes > 0 ->
                "已下载 ${formatBytes(progress.bytesDownloaded)} / ${formatBytes(progress.totalBytes)}"
            else -> "已下载 ${formatBytes(progress.bytesDownloaded)}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mocast)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                100,
                progress.percent.coerceAtLeast(0),
                progress.totalBytes <= 0
            )
            .addAction(0, "取消", cancelIntent)
            .build()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    companion object {
        const val ACTION_START = "app.mocast.download.START"
        const val ACTION_CANCEL = "app.mocast.download.CANCEL"
        const val EXTRA_EPISODE_ID = "episodeId"
        const val EXTRA_SHOW_ID = "showId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUDIO_URL = "audioUrl"

        const val CHANNEL_ID = "mocast_downloads"
        const val NOTIFICATION_ID = 2001

        fun startIntent(
            context: Context,
            episodeId: String,
            showId: String,
            title: String,
            audioUrl: String
        ): Intent = Intent(context, DownloadService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_EPISODE_ID, episodeId)
            putExtra(EXTRA_SHOW_ID, showId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_AUDIO_URL, audioUrl)
        }

        fun cancelIntent(context: Context, episodeId: String): Intent =
            Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_EPISODE_ID, episodeId)
            }
    }
}

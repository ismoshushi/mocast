package app.mocast.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.mocast.MoCastApp
import app.mocast.data.FeedRepository
import app.mocast.data.SettingsStore
import app.mocast.data.room.RoomStore

/**
 * 自动刷新 Worker（0.4.0 补项）：后台刷新全部订阅。
 *
 * 约束（见 RefreshScheduler）：
 * - 周期 6 小时，唯一任务，网络可用时才跑；开了“仅 Wi-Fi”时约束为 UNMETERED。
 * - 失败策略：任何异常都不抛、不重试——返回 Result.success()，静默结束，
 *   下一周期再试。用户在前台手动刷新才是报错的出口，后台任务绝不弹错。
 * - 本 Worker 不写 UI、不发通知、不碰播放器；只调 FeedRepository.refreshAll()。
 * - Wi-Fi 门禁在 Worker 里再查一次：约束可能在设置改后、任务重建前有窗口期，
 *   以这次检查为准（用流量刷新是设置明确关掉“仅 Wi-Fi”时才允许）。
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val context = applicationContext
            val settings = SettingsStore(context).load()
            if (!settings.autoRefreshEnabled) return Result.success()
            if (settings.autoRefreshWifiOnly && !isWifi(context)) return Result.success()
            val roomStore = (context as? MoCastApp)?.roomStore ?: RoomStore(context)
            val repo = FeedRepository(roomStore, roomStore)
            repo.refreshAll()
            Result.success()
        }.getOrDefault(Result.success())
    }

    private fun isWifi(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}

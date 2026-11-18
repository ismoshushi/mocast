package app.mocast.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.mocast.data.Settings
import java.util.concurrent.TimeUnit

/**
 * 自动刷新调度（0.4.0 补项）。
 *
 * - 唯一周期任务，名字固定 "mocast-auto-refresh"，周期 6 小时（WorkManager
 *   周期任务的最小间隔是 15 分钟，6 小时远高于下限；实际触发时间由系统
 *   根据 Doze/省电策略推迟，不保证整点——见 README 风险说明）。
 * - 约束：必须联网；开了“仅 Wi-Fi”时要求 UNMETERED（不计量网络）。
 * - 策略 ExistingPeriodicWorkPolicy.UPDATE：设置改了就重建任务，旧任务不残留。
 * - 关掉自动刷新时取消唯一任务；不要用 cancelAllWork()，那会误杀别的任务。
 * - 调用点：MoCastApp.onCreate()（启动即按保存的设置排一次），
 *   AppViewModel.saveSettings()（设置改了立即重排）。
 */
object RefreshScheduler {

    const val UNIQUE_NAME = "mocast-auto-refresh"
    const val INTERVAL_HOURS = 6L

    fun schedule(context: Context, settings: Settings) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.autoRefreshEnabled) {
            workManager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.autoRefreshWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_NAME)
    }
}

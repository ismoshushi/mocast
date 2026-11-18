package app.mocast

import android.app.Application
import app.mocast.asr.AsrService
import app.mocast.data.SettingsStore
import app.mocast.data.room.DownloadEntity
import app.mocast.data.room.RoomStore
import app.mocast.download.DownloadManager
import app.mocast.download.DownloadStore
import app.mocast.net.Http
import app.mocast.net.ProxyConfig
import app.mocast.work.RefreshScheduler

class MoCastApp : Application() {

    /** 应用级单例：AppViewModel 与 DownloadService 共用同一个 DownloadManager。 */
    lateinit var downloadManager: DownloadManager
        private set

    /** Room 单例，供下载/ASR 接线用（首次 get() 会跑一次性旧数据迁移）。 */
    lateinit var roomStore: RoomStore
        private set

    override fun onCreate() {
        super.onCreate()
        // 启动即按保存的设置配好代理；设置页保存后由 AppViewModel 重新 configure。
        Http.configure(ProxyConfig.fromSettings(SettingsStore(this).load()))
        roomStore = RoomStore(this)
        // 自动刷新（0.4.0 补项）：按保存的设置排一次周期任务，关了就取消。
        RefreshScheduler.schedule(this, SettingsStore(this).load())
        AsrService.bindStore(roomStore)
        downloadManager = DownloadManager(this, RoomDownloadStore(roomStore))
        // 下载完成钩子：只对无官方字幕、无 ASR 缓存、且开了自动生成的集触发识别。
        downloadManager.onDownloadCompleted = { episodeId ->
            val settings = SettingsStore(this).load()
            val episode = runCatching { roomStore.getEpisode(episodeId) }.getOrNull()
            val hasOfficial = episode?.transcriptUrl?.isNotBlank() == true
            val hasCachedAsr = runCatching { roomStore.loadAsrTranscript(episodeId) }.getOrNull() != null
            AsrService.startIfNeeded(this, episodeId, settings, hasOfficial, hasCachedAsr)
        }
    }
}

/** 薄适配器：把 RoomStore 适配成 DownloadManager 要的 DownloadStore 接口。 */
private class RoomDownloadStore(private val store: RoomStore) : DownloadStore {
    override suspend fun upsertDownload(e: DownloadEntity) = store.upsertDownload(e)
    override suspend fun getDownload(id: String) = store.getDownload(id)
    override suspend fun listDownloads() = store.listDownloads()
    override suspend fun removeDownload(id: String) = store.removeDownload(id)
}

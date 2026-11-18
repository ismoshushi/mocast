package app.mocast.download

import app.mocast.data.room.DownloadEntity

/**
 * Room 层要提供的下载记录接口（0.4.0，Room 任务实现）。
 *
 * 本文件只定接口、不碰 Room 实体定义。DownloadEntity 由 Room 任务在
 * app.mocast.data 下新建，字段约定为：
 * episodeId(@PrimaryKey)、showId、title、localPath、state、
 * bytesDownloaded、totalBytes、createdAt、updatedAt，
 * state 取值 QUEUED / DOWNLOADING / PAUSED / COMPLETED / FAILED / CANCELLED。
 *
 * Room 任务只需补一个薄适配器（方法名与签名按下述照抄即可）：
 * ```
 * class RoomDownloadStore(private val store: RoomStore) : DownloadStore {
 *     override suspend fun upsertDownload(e: DownloadEntity) = store.upsertDownload(e)
 *     override suspend fun getDownload(id: String): DownloadEntity? = store.getDownload(id)
 *     override suspend fun listDownloads(): List<DownloadEntity> = store.listDownloads()
 *     override suspend fun removeDownload(id: String) = store.removeDownload(id)
 * }
 * ```
 * 若 Room 报告里命名不同，以 coordinator 最终统一的命名为准，
 * 本接口与 DownloadManager 里对这四个方法的调用随之改名即可，逻辑不变。
 */
interface DownloadStore {
    suspend fun upsertDownload(entity: DownloadEntity)
    suspend fun getDownload(episodeId: String): DownloadEntity?
    suspend fun listDownloads(): List<DownloadEntity>
    suspend fun removeDownload(episodeId: String)
}

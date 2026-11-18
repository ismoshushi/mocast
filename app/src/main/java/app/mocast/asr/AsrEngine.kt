package app.mocast.asr

import app.mocast.data.Cue
import java.io.File

/**
 * 本机 ASR 引擎接口。调用方（AsrService）只依赖这个接口，不直接碰 Sherpa 类，
 * 以后换引擎（例如换成 Zipformer / Parakeet）不用改 Service 与 UI。
 *
 * 约定：
 * - [transcribe] 是耗时操作（60 分钟播客可能要跑十几到几十分钟），必须在后台线程调用，
 *   不要在主线程调用。
 * - 语言用 Whisper 的语言码：中文 "zh"、英文 "en"。传 "auto" 表示先自动检测后锁定
 *   （SherpaAsrEngine 的实现方式，见该文件注释）。
 * - [onProgress] 的 doneMs 是“已识别完的音频位置”，不是已播放位置；totalMs 为 0
 *   表示总时长未知（解码失败时才可能出现）。
 * - 取消靠协程取消：调用方 cancel 掉运行 [transcribe] 的 Job 即可，引擎内部用
 *   ensureActive / isActive 检查点响应取消，不要吞掉 CancellationException。
 */
interface AsrEngine {

    suspend fun transcribe(
        audioFile: File,
        language: String,
        resumeFromMs: Long = 0L,
        onProgress: (doneMs: Long, totalMs: Long) -> Unit = { _, _ -> },
        /** 每识别完一段（已过幻觉过滤）就回调一次；Service 靠它增量落盘，不要等全集跑完。 */
        onSegment: (Cue) -> Unit = {}
    ): List<Cue>

    /** 释放 recognizer / VAD 等 native 资源。Service 结束或引擎换模型时必须调用。 */
    fun release()
}

/** ASR 侧所有可预期失败的统一异常，[userMessage] 直接上屏，不再转译。 */
class AsrException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 模型档位。tar.bz2 体积是“约数”，以 Releases 页实际文件为准。 */
enum class AsrModel(
    val key: String,
    val label: String,
    /** Releases asr-models tag 下的包名（不含 .tar.bz2）。 */
    val packageName: String,
    val approxSizeMb: Int
) {
    TINY("tiny", "tiny · 最快最省", "sherpa-onnx-whisper-tiny", 75),
    BASE("base", "base · 默认", "sherpa-onnx-whisper-base", 140),
    SMALL("small", "small · 更准更慢", "sherpa-onnx-whisper-small", 490);

    companion object {
        fun from(key: String?): AsrModel =
            entries.firstOrNull { it.key == key } ?: BASE
    }
}

/** 字幕来源：官方字幕优先，其次本机 ASR，最后是没有。 */
enum class TranscriptSource { OFFICIAL, ASR, NONE }

/** ASR 任务在前台 Service 里的阶段，供 ViewModel / PlayerScreen 展示。 */
enum class AsrPhase { IDLE, DOWNLOADING_MODEL, RUNNING, DONE, ERROR }

data class AsrJobState(
    val episodeId: String,
    val phase: AsrPhase,
    /** 已识别完的音频位置（毫秒）。 */
    val doneMs: Long = 0L,
    val totalMs: Long = 0L,
    /** 模型下载进度 0..1，仅 DOWNLOADING_MODEL 阶段有意义。 */
    val downloadProgress: Float = 0f,
    val error: String? = null
)

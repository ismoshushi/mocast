package app.mocast.asr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * 把 mp3 / m4a 等音频解码成 16kHz 单声道 FloatArray，分块流式交给调用方。
 *
 * 关键约束（不要改）：
 * - 绝不整集读进内存：60 分钟 16kHz 单声道 float PCM 约 230MB（60*60*16000*4 字节），
 *   必须按 [chunkMs]（默认 10 秒 ≈ 640KB）一块一块吐出去，调用方处理完一块再要下一块。
 * - MediaExtractor / MediaCodec 必须在 finally 里 release，否则连续识别两集必崩。
 * - 输入采样率不一定是 16k（播客常见 44100 / 48000），声道不一定是单声道，
 *   这里用线性插值重采样 + 双声道取平均。线性插值音质略逊于 sinc，但 Whisper 对此不敏感；
 *   不要为这点精度再引一个重采样库。
 * - 解码是阻塞式循环，必须在 Dispatchers.Default/IO 调用，内部用 ensureActive 响应取消。
 */
class AudioDecoder {

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
        /** 默认每块 10 秒音频：160000 个 float，约 640KB。 */
        const val DEFAULT_CHUNK_MS = 10_000L
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }

    /**
     * 流式解码。
     *
     * @param audioFile 本地音频文件（下载任务落盘的 filesDir/downloads/<episodeId>.<ext>）。
     * @param chunkMs 每块音频时长毫秒；最后一块可能不足一块。
     * @param onChunk 每解出一块就回调一次；samples 是 16kHz 单声道 float（-1..1），
     *   startMs 是这块在整集里的起始位置。回调里不要做重活（识别在调用方另起流程），
     *   但可以把 samples 喂给 VAD。
     * @return 整集总时长毫秒（取 MediaExtractor 的 track duration；取不到时按实际解出的
     *   采样数推算）。
     * @throws AsrException 音频不存在 / 没有音频轨 / 解码器建不起来时抛中文异常。
     */
    suspend fun decodeStreaming(
        audioFile: File,
        chunkMs: Long = DEFAULT_CHUNK_MS,
        onChunk: suspend (samples: FloatArray, startMs: Long) -> Unit
    ): Long {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            throw AsrException("本地音频文件不存在或为空，先下载这一集再生成字幕")
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(audioFile.path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("audio/")
            } ?: throw AsrException("这个音频文件里没有音频轨，换一集试试")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw AsrException("读不到音频格式，换一集试试")
            val durationUs = format.getLongOrNull(MediaFormat.KEY_DURATION) ?: -1L

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val chunkSamples = (TARGET_SAMPLE_RATE * chunkMs / 1000).toInt()
            val pending = FloatArray(chunkSamples)
            var pendingLen = 0
            var emittedSamples = 0L // 已交出去的采样数，用于算 startMs
            var totalSamples = 0L
            var inputEos = false
            var outputEos = false
            val bufferInfo = MediaCodec.BufferInfo()

            suspend fun emitChunk(upTo: Int) {
                if (upTo <= 0) return
                onChunk(pending.copyOf(upTo), emittedSamples * 1000 / TARGET_SAMPLE_RATE)
                emittedSamples += upTo
            }

            while (!outputEos) {
                coroutineContext.ensureActive()
                if (!inputEos) {
                    val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (bufferInfo.size > 0) {
                            val outFormat = codec.outputFormat
                            val srcRate = outFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
                                ?: format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
                                ?: TARGET_SAMPLE_RATE
                            val channels = outFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                                ?: format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
                                ?: 1
                            val mono16k = convertTo16kMono(outBuf, bufferInfo, srcRate, channels)
                            totalSamples += mono16k.size
                            var offset = 0
                            while (offset < mono16k.size) {
                                val room = chunkSamples - pendingLen
                                val take = minOf(room, mono16k.size - offset)
                                mono16k.copyInto(pending, pendingLen, offset, offset + take)
                                pendingLen += take
                                offset += take
                                if (pendingLen == chunkSamples) {
                                    emitChunk(pendingLen)
                                    pendingLen = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 输出格式变了（采样率/声道数），下一块按新 format 读即可，不用特殊处理。
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (inputEos) {
                            // 输入已喂完还拿不到输出，等一小会儿再试，避免空转烧 CPU。
                            SystemClock.sleep(5)
                        }
                    }
                }
            }
            if (pendingLen > 0) emitChunk(pendingLen)
            val fromDuration = if (durationUs > 0) durationUs / 1000 else -1L
            val fromSamples = totalSamples * 1000 / TARGET_SAMPLE_RATE
            // 两个口径不一致时以实际解出的为准（有的文件 duration 元数据不准）。
            return if (fromDuration > 0 && kotlin.math.abs(fromDuration - fromSamples) < 2000) {
                fromDuration
            } else {
                fromSamples
            }
        } catch (e: AsrException) {
            throw e
        } catch (e: Exception) {
            throw AsrException("音频解码失败：${e.message ?: "未知错误"}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 只读总时长（不解码），供 Service 在开工前填 totalMs。读不到返回 0。 */
    fun probeDurationMs(audioFile: File): Long {
        if (!audioFile.exists()) return 0L
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(audioFile.path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    return format.getLongOrNull(MediaFormat.KEY_DURATION)?.div(1000) ?: 0L
                }
            }
            0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * 一块解码器输出（16-bit PCM，小端）→ 16kHz 单声道 float。
     * 先按声道取平均变单声道，再线性插值重采样。输出长度 ≈ 输入 * 16000 / srcRate。
     */
    private fun convertTo16kMono(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        srcRate: Int,
        channels: Int
    ): FloatArray {
        val dup = buffer.duplicate()
        dup.position(info.offset)
        dup.limit(info.offset + info.size)
        dup.order(ByteOrder.LITTLE_ENDIAN)
        val shorts = dup.asShortBuffer()
        val frames = info.size / 2 / channels.coerceAtLeast(1)
        if (frames <= 0) return FloatArray(0)
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += shorts.get(f * channels + c) / 32768f
            }
            mono[f] = sum / channels
        }
        if (srcRate == TARGET_SAMPLE_RATE) return mono
        val outLen = (frames.toLong() * TARGET_SAMPLE_RATE / srcRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i.toDouble() * srcRate / TARGET_SAMPLE_RATE
            val idx = pos.toInt().coerceIn(0, frames - 1)
            val frac = (pos - idx).coerceIn(0.0, 1.0).toFloat()
            val next = mono[(idx + 1).coerceAtMost(frames - 1)]
            out[i] = mono[idx] * (1 - frac) + next * frac
        }
        return out
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        runCatching { if (containsKey(key)) getInteger(key) else null }.getOrNull()

    private fun MediaFormat.getLongOrNull(key: String): Long? =
        runCatching { if (containsKey(key)) getLong(key) else null }.getOrNull()
}

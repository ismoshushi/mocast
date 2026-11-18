package app.mocast.asr

import app.mocast.data.Cue
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Sherpa-ONNX + Whisper 的本机识别实现。
 *
 * 依赖的 Sherpa Java API（2026-11-18 已按官方 java-api-examples 核实，均真实存在，
 * 不是编的；见 README.md 的“API 核实清单”）：
 * - OfflineWhisperModelConfig.builder().setEncoder().setDecoder().setLanguage().setTask()
 * - OfflineModelConfig.builder().setWhisper().setTokens().setNumThreads().setDebug()
 * - OfflineRecognizerConfig.builder().setOfflineModelConfig().setDecodingMethod("greedy_search")
 * - OfflineRecognizer(config) / createStream() / decode(stream) / getResult(stream).text
 *   / release()；OfflineStream.acceptWaveform(samples, sampleRate) / release()
 * - SileroVadModelConfig.builder().setModel().setThreshold().setMinSilenceDuration()
 *   .setMinSpeechDuration().setWindowSize()
 * - VadModelConfig.builder().setSileroVadModelConfig().setSampleRate().setNumThreads()
 *   .setDebug().setProvider("cpu")
 * - Vad.acceptWaveform(samples) / isSpeechDetected() / empty() / front() / pop() / release()
 * - SpeechSegment.getStart()（起始采样点）/ getSamples()
 *
 * 流程：解码（10 秒一块）→ VAD 喂 512 采样窗 → 攒成 10~30 秒段 → Whisper 逐段识别
 * → 幻觉过滤 → 返回 Cue 列表。Cue 的时间戳取 VAD 段边界，不依赖 Whisper 的词级时间戳
 * （Java API 是否暴露词级 timestamps 在该版本未核实，不要假设它存在）。
 *
 * 语言锁定：[language] 传 "auto"（默认）时，只用前 30 秒内的第一段有效语音探一次语言，
 * 探完锁定整集，不再切换；显式传 "zh"/"en" 时跳过探测。探测做法：language 传空字符串
 * 让 Whisper 自动检测（sherpa-onnx C++ 层约定：空 = 自动检测），看识别文本里中文
 * 字多还是拉丁字母多定语言，定完重建带锁定语言的 recognizer 再解这一段。
 * ⚠️ 空字符串自动检测是 C++ 层行为，Java 绑定只是透传——首跑验证点：若该版本抛错，
 * 改成首段 zh/en 各解一次、取脚本更干净者（见 README.md）。
 *
 * 线程：Sherpa 的 decode 是阻塞调用，numThreads=2 时 base 模型在中端机上约 0.3~0.5 倍
 * 速（60 分钟约 20~30 分钟，仅为经验估计，真机首跑实测为准）。不要在主线程建 recognizer，
 * 模型加载本身也要 1~2 秒。
 */
class SherpaAsrEngine(
    private val modelFiles: AsrModelManager.ModelFiles,
    private val numThreads: Int = 2
) : AsrEngine {

    companion object {
        private const val SAMPLE_RATE = 16000
        /** VAD 窗口：512 采样 = 32ms，Silero VAD 官方示例值，不要改。 */
        private const val VAD_WINDOW = 512
        /** 合并段下限 10 秒：太短则 Whisper 上下文不够，错字多。 */
        private const val MIN_SEGMENT_MS = 10_000L
        /** 合并段上限 30 秒：Whisper 单次解码超过 30 秒会截断/变慢。 */
        private const val MAX_SEGMENT_MS = 30_000L
        /** 两段语音之间停顿超过 1.5 秒就断开，不硬拼成一段。 */
        private const val MAX_GAP_MS = 1_500L
        /** 语言探测只看前 30 秒音频，超时还没探到就默认中文。 */
        private const val LANG_PROBE_LIMIT_MS = 30_000L
        /** 收尾时喂 1 秒静音，把 VAD 里残留的尾段逼出来（Java API 没有公开 flush）。 */
        private const val TRAILING_SILENCE_MS = 1000L
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var released = false

    override suspend fun transcribe(
        audioFile: File,
        language: String,
        resumeFromMs: Long,
        onProgress: (doneMs: Long, totalMs: Long) -> Unit,
        onSegment: (Cue) -> Unit
    ): List<Cue> = withContext(Dispatchers.Default) {
        val totalMs = AudioDecoder().probeDurationMs(audioFile)
        onProgress(resumeFromMs, totalMs)
        val decoder = AudioDecoder()
        val filterState = HallucinationFilter.State()
        val cues = ArrayList<Cue>()
        // 续跑：把已落盘的段先读回来由调用方拼好，这里只负责“新段”；
        // endMs <= resumeFromMs 的段解出来也不识别，直接跳过。
        var lockedLanguage: String? = if (language == "auto") null else language
        var probeDone = language != "auto"

        try {
            vad = createVad()
            val pending = SegmentBuffer()
            // 每解完一段就识别并回调，Service 据此增量落盘。
            suspend fun flushPending(force: Boolean) {
                val seg = pending.takeIfReady(force) ?: return
                coroutineContext.ensureActive()
                if (seg.endMs <= resumeFromMs) return // 续跑：已识别过的段跳过
                if (!probeDone) {
                    lockedLanguage = probeLanguage(seg) ?: "zh"
                    probeDone = true
                }
                val lang = lockedLanguage ?: "zh"
                val text = decodeSegment(seg.samples, lang)
                val cue = Cue(seg.startMs, seg.endMs, text.trim())
                if (!filterState.shouldDrop(cue)) {
                    cues.add(cue)
                    onSegment(cue)
                }
                onProgress(seg.endMs, totalMs)
            }

            decoder.decodeStreaming(audioFile) { samples, chunkStartMs ->
                coroutineContext.ensureActive()
                feedVad(samples)
                drainVadInto(pending)
                // 攒够一段就识别掉，不要等整集解完（内存 + 续跑都受益）。
                flushPending(force = false)
                // 语言探测有 30 秒上限：超过还没探到（前 30 秒全是音乐/静音），直接锁中文。
                if (!probeDone && chunkStartMs >= LANG_PROBE_LIMIT_MS) {
                    lockedLanguage = "zh"
                    probeDone = true
                }
            }
            // 收尾：喂 1 秒静音逼出 VAD 残留，再把攒着的最后一段强制识别。
            feedVad(FloatArray(SAMPLE_RATE * TRAILING_SILENCE_MS / 1000))
            drainVadInto(pending)
            flushPending(force = true)
            cues.sortedBy { it.startMs }
        } finally {
            release()
        }
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { recognizer?.release() }
        runCatching { vad?.release() }
        recognizer = null
        vad = null
    }

    // ---- Sherpa 对象创建（builder 写法按官方 Java 示例） ----

    private fun createVad(): Vad {
        val silero = SileroVadModelConfig.builder()
            .setModel(modelFiles.vad.absolutePath)
            .setThreshold(0.5f)
            .setMinSilenceDuration(0.25f)
            .setMinSpeechDuration(0.5f)
            .setWindowSize(VAD_WINDOW)
            .build()
        val config = VadModelConfig.builder()
            .setSileroVadModelConfig(silero)
            .setSampleRate(SAMPLE_RATE)
            .setNumThreads(1)
            .setDebug(false)
            .setProvider("cpu")
            .build()
        return Vad(config)
    }

    private fun createRecognizer(language: String): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig.builder()
            .setEncoder(modelFiles.encoder.absolutePath)
            .setDecoder(modelFiles.decoder.absolutePath)
            .setLanguage(language)
            .setTask("transcribe")
            .build()
        val modelConfig = OfflineModelConfig.builder()
            .setWhisper(whisper)
            .setTokens(modelFiles.tokens.absolutePath)
            .setNumThreads(numThreads)
            .setDebug(false)
            .build()
        val config = OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(modelConfig)
            .setDecodingMethod("greedy_search")
            .build()
        return OfflineRecognizer(config)
    }

    /** 取当前语言的 recognizer，没有就建一个；语言锁定后重建一次（旧的先 release）。 */
    private fun recognizerFor(language: String): OfflineRecognizer {
        val current = recognizer
        if (current != null) return current
        return createRecognizer(language).also { recognizer = it }
    }

    /** 语言切换时重建 recognizer（探测段解完后调一次，全集只重建最多两次）。 */
    private fun rebuildRecognizer(language: String) {
        runCatching { recognizer?.release() }
        recognizer = createRecognizer(language)
    }

    private fun decodeSegment(samples: FloatArray, language: String): String {
        val rec = recognizerFor(language)
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            return rec.getResult(stream).text
        } finally {
            runCatching { stream.release() }
        }
    }

    /**
     * 语言探测：用 language=""（自动检测）解这一段，看文本脚本定语言。
     * 返回 "zh"/"en"，探不到（空文本/纯标点）返回 null，调用方默认 "zh"。
     * 探测成功后重建锁定语言的 recognizer，探测段本身用锁定语言重解一次
     * （自动检测的结果只用来定语言，不直接当字幕用，保证全集语言一致）。
     */
    private fun probeLanguage(segment: MergedSegment): String? {
        val probeText = try {
            val probeRec = createRecognizer("")
            try {
                val stream = probeRec.createStream()
                try {
                    stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                    probeRec.decode(stream)
                    probeRec.getResult(stream).text
                } finally {
                    runCatching { stream.release() }
                }
            } finally {
                runCatching { probeRec.release() }
            }
        } catch (_: Exception) {
            // 空字符串在该版本不被接受：退回默认中文，不要让整集失败。
            return "zh"
        }
        val cjk = probeText.count { it in '\u4E00'..'\u9FFF' }
        val latin = probeText.count { it in 'a'..'z' || it in 'A'..'Z' }
        val lang = when {
            probeText.isBlank() -> null
            cjk > 0 && cjk >= latin -> "zh"
            latin >= 10 -> "en"
            cjk > latin -> "zh"
            else -> null
        }
        if (lang != null) rebuildRecognizer(lang)
        return lang
    }

    // ---- VAD 喂数与取段 ----

    private fun feedVad(samples: FloatArray) {
        val v = vad ?: return
        var offset = 0
        val window = FloatArray(VAD_WINDOW)
        while (offset < samples.size) {
            val take = minOf(VAD_WINDOW, samples.size - offset)
            samples.copyInto(window, 0, offset, offset + take)
            if (take < VAD_WINDOW) window.fill(0f, take, VAD_WINDOW)
            v.acceptWaveform(window)
            offset += take
        }
    }

    private fun drainVadInto(buffer: SegmentBuffer) {
        val v = vad ?: return
        while (!v.empty()) {
            val segment: SpeechSegment = v.front()
            buffer.add(segment)
            v.pop()
        }
    }

    // ---- 段合并 ----

    /** 一段 VAD 语音 + 起止采样点。 */
    private data class MergedSegment(
        val samples: FloatArray,
        val startMs: Long,
        val endMs: Long
    )

    /**
     * 把 VAD 吐出的短段（通常 1~5 秒）拼成 10~30 秒再识别。
     * 拼段规则：下段与上段间隔 <= 1.5 秒就拼；拼到 >= 10 秒且下一段间隔大就出段；
     * 硬上限 30 秒，超了直接出段（Whisper 单次解码不建议超过 30 秒）。
     */
    private inner class SegmentBuffer {
        private val parts = ArrayList<SpeechSegment>()
        private var startSample = 0L
        private var endSample = 0L

        private var gapBreak = false

        fun add(segment: SpeechSegment) {
            val segStart = segment.getStart().toLong()
            val segEnd = segStart + segment.getSamples().size
            if (parts.isNotEmpty()) {
                val gapMs = (segStart - endSample) * 1000 / SAMPLE_RATE
                val bufferedMs = (endSample - startSample) * 1000 / SAMPLE_RATE
                // 停顿超过 1.5 秒且已攒够 10 秒：下一段进来前先把这段标为可出段。
                if (gapMs > MAX_GAP_MS && bufferedMs >= MIN_SEGMENT_MS) gapBreak = true
            } else {
                startSample = segStart
            }
            parts.add(segment)
            endSample = segEnd
        }

        /** 够一段就取走并清空；不够返回 null。force=true 时有剩就全取（收尾用）。 */
        fun takeIfReady(force: Boolean): MergedSegment? {
            if (parts.isEmpty()) return null
            val durationMs = (endSample - startSample) * 1000 / SAMPLE_RATE
            if (!force && !gapBreak && durationMs < MAX_SEGMENT_MS) return null
            // 出段条件（三者满足其一）：停顿断句（gapBreak）、攒到 30 秒硬上限、强制收尾。
            val total = parts.sumOf { it.getSamples().size }
            val merged = FloatArray(total)
            var offset = 0
            for (part in parts) {
                val s = part.getSamples()
                s.copyInto(merged, offset)
                offset += s.size
            }
            val out = MergedSegment(
                samples = merged,
                startMs = startSample * 1000 / SAMPLE_RATE,
                endMs = endSample * 1000 / SAMPLE_RATE
            )
            parts.clear()
            gapBreak = false
            return out
        }
    }
}

# 本机 ASR（0.4.0 第 5 项）

Sherpa-ONNX + Whisper，音频不出本机。只做“没有官方字幕时补一条 AI 字幕”，
官方字幕优先，有官方字幕的集不跑 ASR。

## 版本钉死（2026-11-18 网上核实）

| 东西 | 钉死的版本 | 来源 |
|---|---|---|
| sherpa-onnx | **1.13.8**（1.x 最后一个） | k2-fsa/sherpa-onnx Releases 页当日最新是 v2.0.1，但那是刚发的大版本，Java API 是否有 breaking change 未核；1.13.8 是经过多方验证的 1.x 末版，ONNX Runtime 1.28.2。**不要直接升 2.0.1**，升级前先对下文 API 清单。 |
| Sherpa AAR | `sherpa-onnx-1.13.8.aar`（GitHub Releases 附件） | **Sherpa 不在 Maven Central**，也没有可用的 Maven 坐标；网上流传的 `com.github.k2-fsa:sherpa-onnx-android:1.10.0`（JitPack）是 2024 年的旧版，不要用。正确接法见 `app/build.gradle.kts` 的 patch（`app/libs/` + `files()`，或下载任务 + SHA 校验）。 |
| Whisper 模型 | base（默认）/ tiny / small，多语言版 tar.bz2 | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-<tiny\|base\|small>.tar.bz2`，另加 `silero_vad.onnx`（同 tag 下单个文件）。 |
| commons-compress | 1.26.2 | 只用来解 tar.bz2，约 1MB。Android 没有内置 bzip2，不要手写解码。 |

## SHA-256（首跑前必核，不要直接信）

`AsrModelManager.EXPECTED_SHA256` 里的三个值是 2026-11-18 从第三方转载的
sherpa-onnx NOTICE 文件抄来的快照（tiny `c4611699…`、base `911b2083…`、
small `486a46af…`），asr-models tag 是滚动更新的，可能已变。
集成第一步：打开 https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
核对，改值，不要改校验逻辑。VAD 的 `silero_vad.onnx` 没找到可信 SHA，
代码里只校验“文件存在且非空”，加载失败时删掉重下。

## Sherpa Java API 核实清单（都真实存在，非编造）

按官方 `java-api-examples/VadFromMicWithNonStreamingWhisper.java` 核实：

- `OfflineWhisperModelConfig.builder().setEncoder().setDecoder().setLanguage().setTask()`
  （默认值：`language="en"`，`task="transcribe"`）
- `OfflineModelConfig.builder().setWhisper().setTokens().setNumThreads().setDebug()`
- `OfflineRecognizerConfig.builder().setOfflineModelConfig().setDecodingMethod("greedy_search")`
- `OfflineRecognizer(config)`、`createStream()`、`decode(stream)`、
  `getResult(stream).getText()`、`release()`；`OfflineStream.acceptWaveform(samples, 16000)`、`release()`
- `SileroVadModelConfig.builder().setModel().setThreshold(0.5f).setMinSilenceDuration(0.25f)`
  `.setMinSpeechDuration(0.5f).setWindowSize(512)`
- `VadModelConfig.builder().setSileroVadModelConfig().setSampleRate(16000).setNumThreads(1)`
  `.setDebug().setProvider("cpu")`
- `Vad.acceptWaveform(samples)`、`isSpeechDetected()`、`empty()`、`front()`、`pop()`、`release()`
- `SpeechSegment.getStart()`（起始采样点）、`getSamples()`

未核实、代码里已绕开的点：

1. **空字符串自动检测**：`setLanguage("")` 触发 Whisper 自动检测是 sherpa-onnx
   C++ 层约定，Java 绑定只是透传。首跑验证：若该版本抛错，`probeLanguage()` 已有
   try/catch 退回中文；彻底修法是首段 zh/en 各解一次取脚本更干净者。
2. **词级时间戳**：Java API 是否暴露没核实，所以 Cue 时间戳取 VAD 段边界，不依赖它。
3. **Vad.flush()**：Java API 里没见到，收尾用“喂 1 秒静音”逼出残留段代替。
4. **包内文件名**：按 `*-encoder.int8.onnx` / `*-decoder.int8.onnx` / `*-tokens.txt`
   扫描，不写死全名；上游改名也不怕。

## 文件一览

| 文件 | 干什么 |
|---|---|
| `AsrEngine.kt` | 接口 + `AsrModel` 档位 + `AsrPhase`/`AsrJobState`/`TranscriptSource` |
| `AudioDecoder.kt` | MediaExtractor + MediaCodec 解码成 16kHz 单声道，10 秒一块流式吐 |
| `SherpaAsrEngine.kt` | OfflineRecognizer + Silero VAD，语言探测锁定，10~30 秒拼段识别 |
| `HallucinationFilter.kt` | 空段 / 连续重复 / 段内词语循环过滤 |
| `AsrModelManager.kt` | 模型按需下载、Range 断点续传、SHA-256、tar.bz2 解压、损坏重下 |
| `AsrService.kt` | 前台 Service（dataSync），通知栏进度 + 取消，逐段落盘、断点续跑 |
| `AsrTranscriptStore.kt` | RoomStore 要实现的接口 + `AsrCuesJson` 编解码（Room 任务照此实现） |

## 接线清单（主仓共享文件怎么改，见交回报告的 patch 节）

1. `app/build.gradle.kts`：放 AAR 到 `app/libs/` + `files()` 依赖；加
   `org.apache.commons:commons-compress:1.26.2`；`ndk.abiFilters` 只留 `arm64-v8a`
   （debug 可另加 `x86_64` 跑模拟器，见 patch 注释）。
2. `AndroidManifest.xml`：加 `FOREGROUND_SERVICE_DATA_SYNC` 权限 + AsrService 声明
  （`foregroundServiceType="dataSync"`）。
3. `data/Settings.kt`：加 `asrModel` / `asrWifiOnly` / `asrAuto` + 三个 key。
4. `data/FeedRepository.kt`：`transcriptOf()` 改返回 `TranscriptResult(cues, source)`，
   官方优先 → Room ASR 缓存 → 空。
5. `ui/AppViewModel.kt`：UiState 加 `asrStatus/asrProgress/asrError/transcriptSource`；
   `loadTranscript()` 走新 `transcriptOf()`；加 `startAsr()` / `cancelAsr()`，
   订阅 `AsrService.state`；`MoCastApp.onCreate` 里调 `AsrService.bindStore(roomStore)`。
6. `ui/PlayerScreen.kt` + `ui/AppRoot.kt`：空状态加“生成 AI 字幕”按钮与
   “AI 字幕·可能有错”标注，生成中显示进度与取消。
7. 下载任务的 `onDownloadCompleted(episodeId)` 里调
   `AsrService.startIfNeeded(context, episodeId, settings, hasOfficialTranscript, hasCachedAsr)`。
8. `ui/SettingsScreen.kt`：加“AI 字幕”分组（模型档位 / 仅 Wi-Fi 下载 / 下载后自动生成）。

## 未验证项（本地无 JDK / Android SDK，编不过；真机首跑必做）

- `./gradlew assembleDebug` 通过；AAR 里的 `.so` 正常加载（`System.loadLibrary` 不报错）。
- 首跑核对：AAR SHA、三个模型 SHA、包内实际文件名、空字符串语言探测是否生效。
- 60 分钟播客实测：总耗时、base/tiny 准确率对比、发热、峰值内存、续跑断点是否真续上。
- x86_64 模拟器：只留 arm64-v8a 后模拟器会崩，确认用 arm64 模拟器镜像或真机。
- Android 14+：`FOREGROUND_SERVICE_DATA_SYNC` 权限 + 前台服务 5 秒内 `startForeground`
  是否合规；杀掉重进后续跑是否正常。

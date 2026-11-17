# MoCast

一个把「订阅播客 → 播放 → 双语字幕 → AI 精读」跑在手机上的安卓客户端。

## 0.02 能做什么



* 双语字幕：官方字幕一键翻译成目标语言，三种模式切换 —— 原文 / 译文 / 双语

* 翻译服务商：OpenAI 兼容接口，Base URL / API Key / 模型 / 目标语言可配；内置 OpenAI、DeepSeek、硅基流动、智谱 GLM、Ollama 快捷填充

* 翻译缓存：按集按语言落本地，重进不重复请求；12 条一批，中断保留已完成部分

* 取消订阅：节目详情右上删除，二次确认，节目与字幕缓存一并清掉（播放进度保留）

* 播完自动下一集：同节目列表里顺延到下一个有音频的集

* 0.01 的全部：订阅、节目详情、后台播放、倍速 / ±15 秒、断点续播、官方字幕点句跳转

## 0.01 能做什么



* 订阅：RSS 直链、Apple Podcasts、YouTube 频道 / 播放列表、小宇宙

* 节目详情与单集列表（封面、日期、时长、是否带字幕）

* 后台播放（Media3 + MediaSessionService，通知栏 / 锁屏 / 耳机键）

* 倍速 1.0× / 1.25× / 1.5× / 2.0×，前后 15 秒，断点续播

* 官方字幕：`podcast:transcript` 的 VTT / SRT / JSON，点字幕跳到对应时间

## 技术方案

单 Activity + Jetpack Compose（Material 3）架构，Kotlin 协程与 Flow 驱动状态；Media3 负责后台播放，OkHttp 加系统 XML / JSON 解析器负责订阅抓取与字幕解析，0.01 用 SharedPreferences + JSON 文件做本地存储。后续按版本逐步引入 Room、ASR 与大模型能力。

完整设计（技术栈、分层、接口与验收口径）见 [docs / 技术方案.md](docs/技术方案.md)。

## 致敬 SubCast

本项目受 [SubCast（声渡）](https://github.com/joeseesun/subcast) 启发 —— 它把「播客订阅 → 播放 → 双语字幕 → AI 精读」串成一条完整工作流，证明了这条链路在桌面端可以做得足够顺手。MoCast 想做的是把这套体验装进口袋。感谢 SubCast 的开源，以及它对 AGPL 的坚持 —— 这也是 MoCast 选择同样协议的直接原因。

## 版本路径



| 版本   | 主题       | 关键点                               |
| ---- | -------- | --------------------------------- |
| 0.01 | 跑通主线     | 订阅・播放・官方字幕                        |
| 0.02 | 双语字幕闭环   | LLM 翻译・翻译设置与缓存・取消订阅・自动下一集         |
| 0.03 | 数据层与云端能力 | Room・YouTube 视频・下载・代理・本机 ASR 补齐字幕 |
| 0.04 | 智能与生态    | AI 精读问答・云 ASR・多服务商・海报・统计          |
| 0.05 | 精读闭环     | TTS・精读源・共享字幕・自动更新・多语言             |

## 构建

需要 JDK 17、Android SDK 35。



```
$env:JAVA_HOME = "D:\Program Files\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

SDK 路径写在 `local.properties`（不入库）：



```
sdk.dir=D:\\Android\\Sdk
```

## 许可证

MoCast 整体代码采用 [GNU AGPL-3.0](LICENSE) 协议开源。
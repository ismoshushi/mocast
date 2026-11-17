# MoCast

SubCast（声渡）的安卓客户端。对标 `joeseesun/subcast`，目标是「订阅播客 → 播放 → 双语字幕 → AI 精读」在手机上跑通。

当前版本 **0.01**，只做主线四件事：订阅、单集列表、后台播放、官方字幕。

## 0.01 能做什么

- 订阅：RSS 直链、Apple Podcasts、YouTube 频道 / 播放列表、小宇宙
- 节目详情与单集列表（封面、日期、时长、是否带字幕）
- 后台播放（Media3 + MediaSessionService，通知栏 / 锁屏 / 耳机键）
- 倍速 1.0× / 1.25× / 1.5× / 2.0×，前后 15 秒，断点续播
- 官方字幕：`podcast:transcript` 的 VTT / SRT / JSON，点字幕跳到对应时间

## 还不能做

- 双语翻译、AI 导读 / 问答
- 本机 ASR（SenseVoice / whisper.cpp）、云端 ASR
- YouTube 视频流播放（先只订阅列表）
- 下载离线、海报、统计、OPML、代理设置

## 构建

需要 JDK 17、Android SDK 35。

```powershell
$env:JAVA_HOME = "D:\Program Files\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

SDK 路径写在 `local.properties`（不入库）：

```
sdk.dir=D:\\Android\\Sdk
```

## 结构

```
app/src/main/java/app/mocast/
├── data/   数据模型、本地存储、Repository
├── net/    链接解析、RSS/Atom 解析、字幕解析
├── play/   Media3 播放服务
└── ui/     Compose 界面与 ViewModel
```

详细方案见 [docs/技术方案.md](docs/技术方案.md)。

## 路线

| 版本 | 内容 |
|---|---|
| 0.02 | Room、翻译、YouTube 音视频、下载、代理 |
| 0.03 | 本机 ASR、无字幕集补齐、AI 导读与问答 |
| 0.04 | 多云 ASR、模型服务商、海报、统计 |
| 0.05 | TTS、精读源、共享字幕、自动更新、多语言 |

## 许可证

代码从零编写；若后续引入 SubCast（AGPL-3.0）的实现或资源，需整体按 AGPL-3.0 开源。

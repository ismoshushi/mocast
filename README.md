# MoCast

一个把播客听得更明白的 Android 客户端 —— 订阅、播放、字幕、翻译，装在一个应用里。

订阅只要一条链接：RSS 直链、Apple Podcasts、YouTube 频道或播放列表、小宇宙。节目和单集一次铺开，封面、日期、时长、带不带字幕，扫一眼就清楚，点开就播。

播放是按「通勤路上真的会用」做的：切到别的应用、锁屏、拔耳机都不停；四档倍速、前后 15 秒、断点续播、播完自动接下一集。带字幕的节目可以直接对着原文听，外文内容还能整集翻成你的语言，原文、译文、双语三种视图随手切，点一句字幕就跳回那一段。翻好的留在本机，下次进来不用再等一遍。

当前版本 0.4.0，数据层与云端能力已经补齐。0.5.0 起往 AI 精读走，路线写在下面。

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

## 技术实现

单 Activity + Jetpack Compose（Material 3），状态靠 Kotlin 协程与 StateFlow 自上而下推。播放交给 Media3 的 MediaSessionService 常驻前台，页面只拿一个 `MediaController` 发指令，所以切后台、锁屏、被杀进程都不影响出声。抓取与解析走 OkHttp 加系统自带的 XmlPullParser /org.json，没引第三方 RSS 库。

**技术栈**



| 项目   | 选型                                               |
| ---- | ------------------------------------------------ |
| 语言   | Kotlin 2.0.21                                    |
| 构建工具 | Gradle 8.11.1（KTS）                               |
| 构建插件 | Android Gradle Plugin 8.7.3                      |
| 运行环境 | JDK 17                                           |
| SDK  | compileSdk 35 / minSdk 26                        |
| UI   | Jetpack Compose BOM 2024.10.01 + Material 3      |
| 封面图  | Coil 2.7.0                                       |
| 播放   | Media3 ExoPlayer + MediaSessionService 1.4.1     |
| 网络   | OkHttp 4.12.0                                    |
| 解析   | XmlPullParser + org.json（系统内置）                   |
| 存储   | Room 2.6.1（KSP 2.0.21-1.0.25），0.4.0 起；旧 SharedPreferences + JSON 一次性迁移 |
| 下载   | 前台 Service（dataSync）+ OkHttp Range 断点续传，文件在 filesDir/downloads/ |
| YouTube 视频 | NewPipe Extractor v0.27.1（JitPack），合并流 ≤720p 优先 |
| 本机 ASR | Sherpa-ONNX 1.13.8（AAR 手动放 app/libs/，见该目录 README）+ Whisper base/tiny/small 按需下载 |
| 模型解压 | commons-compress 1.26.2（解 tar.bz2 模型包） |
| 翻译   | OpenAI 兼容 Chat Completions，常规 12 条 / 关键词档 20 条一批 |

> 注：Sherpa-ONNX 1.13.8 的 AAR 不在 Maven Central，须手动下载到 `app/libs/`（见 `app/libs/README.md`，先核对 Releases 页附件名与 SHA-256）；ASR 模型三个档位的 SHA-256 快照值见 `app/src/main/java/app/mocast/asr/AsrModelManager.kt`，首次跑通前须到 `https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models` 核对，滚动更新后值会变。

**结构**



```
Compose UI（首页 / 节目详情 / 播放页 / 设置 / 关于）
        │  UiState (StateFlow)
AppViewModel ── MediaController ──► PlaybackService（MediaSessionService + ExoPlayer）
        │                                    └─ 通知栏 / 锁屏 / 耳机键
        ▼
FeedRepository
   ├─ FeedResolver      链接 → feed 地址（Apple / YouTube / 小宇宙 / RSS）
   ├─ RssParser         RSS 2.0 / Atom → Show + Episode[]
   ├─ TranscriptParser  VTT / SRT / JSON → Cue[]
   ├─ TranslationClient OpenAI 兼容接口，分批翻译 + 结果合并
   └─ Store / TranslationCache  SharedPreferences + files/*.json
```



| 包        | 职责                                 |
| -------- | ---------------------------------- |
| `data`   | 数据模型、FeedRepository、设置、本地存储、翻译缓存   |
| `net`    | 链接解析、HTTP、RSS 与字幕解析、翻译客户端          |
| `play`   | 前台播放服务与播放控制                        |
| `ui`     | Compose 页面、AppViewModel、主题与格式化     |
| `update` | update.json 解析，检查 / 下载 / 校验 / 拉起安装 |

几条贯穿全项目的约束：解析器写成纯函数（输入字符串、输出模型），好测也好换源；播放状态只从 MediaController 反推，UI 不持有播放器；本地读写一律包在 `runCatching` 里，读到坏数据就丢掉重来，不让它把首页卡死。

## 版本功能

**0.1.0**

订阅（RSS / Apple Podcasts / YouTube / 小宇宙）、节目与单集列表、后台播放、倍速与断点续播、官方字幕。

**0.2.0**

双语字幕、翻译服务商与缓存、取消订阅、播完自动下一集。

**0.3.0**

- 翻译改成分段流式：从当前播放位置起翻，翻好一段立刻显示，随时可以停，播到段尾自动续下一段
- 关键词模式：只标超出所选词汇层级（初中 / 高中 / 四级 / 六级 / 考研 / 专八）的生词，带词性和中文释义，不翻整句
- 设置分组与三档主题：跟随系统 / 白日 / 夜色
- 关于页：隐私说明、复制版本信息、源码地址
- 版本更新：填 update.json 地址，检查 / 下载 / SHA-256 校验 / 拉起系统安装

**0.4.0**（当前）

- Room 数据层：订阅、单集、播放进度、翻译缓存迁入 Room；旧版 SharedPreferences + JSON 文件一次性迁移，迁移失败回退到旧数据、不丢订阅
- YouTube 视频播放：YouTube 频道 / 播放列表的单集可直接播视频流（NewPipe Extractor 解析），无直接音频的单集不再只报“没有直接音频”
- 下载与仅 Wi-Fi：单集可下载到本机离线听，默认仅 Wi-Fi 下下载，可在设置里改；下载走 WorkManager 或前台 Service（以后台任务为准，见技术栈注）
- 代理：设置里可配 HTTP(S) 代理，订阅抓取、翻译、下载共用同一代理配置
- 本机 ASR 补字幕：没有官方字幕的单集，可在本机用 Sherpa-ONNX 离线识别补一条字幕，音频不出本机
- 迷你播放器 + 播放队列：首页与节目页底部常驻播放条，点开回播放页；单集可“稍后听”进队列，队列存在本机、播完先播队列
- 已听状态 + 自动刷新：进度到 95% 或播完自动标已听，可手动切换；首页节目卡显示未听数，节目页可只看未听；每 6 小时后台自动刷新全部订阅（默认仅 Wi-Fi）
- 睡眠定时 + 倍速记忆：15/30/45/60 分钟倒计时或播完本集停，到点自动暂停；倍速扩到 0.75–2.5 共 7 档，选中档下次打开沿用
- OPML 导入 / 导出：设置页可导入 .opml/.xml（已订阅的跳过，导完报成功/跳过/失败数），也可把现有订阅导出成标准 OPML 2.0 文件
- 推荐订阅：首页无订阅时显示 7 档推荐（英语学习 3 档 + 科技访谈 4 档，RSS 均在 2026-11-18 核实过），不自动订阅，点一个订阅一个
- 翻译连接测试 + 费用预估：翻译设置页可点“测试连接”（只发一句 Hello 试探）；播放页翻译前显示整集 token 与费用预估（内置单价表为 2026-11-18 快照，仅供参考，未知模型可在设置里填每 1k token 单价）

## 路线

| 版本   | 主题       | 关键点                               | 状态 |
| ---- | -------- | --------------------------------- | ---- |
| 0.4.0 | 数据层与云端能力 | Room・YouTube 视频・下载・代理・本机 ASR 补齐字幕 | 已完成（0.4.0） |
| 0.5.0 | 智能与生态    | AI 精读问答・云 ASR・多服务商・海报・统计          |      |
| 0.6.0 | 精读闭环     | TTS・精读源・共享字幕・多语言                  |      |

## 致谢

本项目受 [SubCast（声渡）](https://github.com/joeseesun/subcast) 启发 —— 它把「播客订阅 → 播放 → 双语字幕 → AI 精读」串成一条完整的工作流，也证明了这条链路在桌面端可以做得足够顺手。MoCast 想做的，只是把这套已经被人验证过的体验装进口袋，让通勤、跑步、做饭这些碎时间里也能听完一期、顺便看懂它在说什么。

感谢 SubCast 的作者把它开源出来，以及对 AGPL 的坚持 —— 这也是 MoCast 选择同一份协议的直接原因：既然站在别人的成果上往前走，那往前走出来的东西也应该让别人站得上来。

也感谢一路上被用到的那些开源项目，以及每一个把字幕老老实实写进 feed 里的节目制作方 —— 没有这些，双语这一层根本无从谈起。

## 许可证

MoCast 整体代码采用 [GNU AGPL-3.0](LICENSE) 协议开源。

简单说：你可以随意阅读、修改、分发这份代码，也可以拿它做商业用途；但如果修改后通过网络向他人提供服务，需要把修改后的源码同样以 AGPL-3.0 公开出来，并保留原有的版权声明与免责声明。软件按「现状」提供，作者不对使用后果承担任何担保或责任 —— 完整条款以 [LICENSE](LICENSE) 的原文为准。

Copyright (c) MoCast contributors.
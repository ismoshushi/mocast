package app.mocast.ui

import app.mocast.data.AudioLang

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import app.mocast.asr.AsrPhase
import app.mocast.asr.TranscriptSource
import app.mocast.data.DisplayMode
import app.mocast.data.TranslationMode
import app.mocast.net.KeywordParser
import app.mocast.download.DownloadState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    state: UiState,
    player: Player?,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onTranslate: () -> Unit,
    onMode: (DisplayMode) -> Unit,
    onToggleVideoMode: () -> Unit,
    onSelectAudioLanguage: (String) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onStartAsr: () -> Unit,
    onCancelAsr: () -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onSleepEndOfEpisode: () -> Unit,
    onCancelSleep: () -> Unit,
    /** 生词本：点词片收/取消（word, pos, ipa, gloss, cueIndex） */
    onToggleWord: (String, String, String, String, Int) -> Unit = { _, _, _, _, _ -> },
    onCollectAllWords: () -> Unit = {},
    onDismissWordHint: () -> Unit = {}
) {
    val episode = state.currentEpisode ?: return
    val showTitle = state.currentShow?.title.orEmpty()
    val total = if (state.durationMs > 0) state.durationMs else 1L
    var scrub by remember(state.currentEpisode?.id) { mutableStateOf<Float?>(null) }
    // 倍速 / 睡眠选项弹窗：浏览器式的“点一下才弹”，不占页面高度。
    var speedSheet by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var autoFollow by remember(state.currentEpisode?.id) { mutableStateOf(true) }

    // 播放页保持常亮：进入时加 FLAG_KEEP_SCREEN_ON，退出时撤掉（只影响播放页，别的页面照常息屏）。
    val keepOnView = LocalView.current
    DisposableEffect(Unit) {
        val window = keepOnView.context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(showTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cover(episode.artwork.ifBlank { state.currentShow?.artwork.orEmpty() }, 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        showTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 0.4.0 YouTube 视频区：只有解析出视频流且在视频模式时才显示。
            // useController = false：用 App 自己的播放条，不叠加 ExoPlayer 自带控制条。
            if (state.videoMode && state.resolvedVideoUrl.isNotBlank()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = false
                        }
                    },
                    update = { view -> view.player = player },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                )
                Spacer(Modifier.height(8.dp))
            }

            // ---- 播放控制区（0.4.1 重做：从四排芯片压成两排）----
            // 进度条 + 时间
            // 细进度条：用默认 M3 滑块（竖条状拖柄 + 细轨道），只把整体高度压到 26dp，不再自绘（自绘圆点会歪）。
            // 时长未知（YouTube 解析中/尚未 prepared）时不画可拖的滑块，否则 value 会被夹到末端、看起来“跑偏”。
            if (state.durationMs > 0) {
            Slider(
                value = (scrub ?: state.positionMs.toFloat()).coerceIn(0f, total.toFloat()),
                valueRange = 0f..total.toFloat(),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { onSeek(it.toLong()) }
                    scrub = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatTime(scrub?.toLong() ?: state.positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatTime(state.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- 播放控制区：后退/播放/前进居中，倍速+睡眠靠左，音视频+下载靠右 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolIcon(
                        icon = Icons.Outlined.Speed,
                        label = formatSpeedLabel(state.speed),
                        description = "倍速 ${formatSpeedLabel(state.speed)}",
                        active = state.speed != 1f,
                        iconSize = 22.dp,
                        onClick = { speedSheet = true }
                    )
                    ToolIcon(
                        icon = Icons.Outlined.Bedtime,
                        label = when {
                            state.sleepEndOfEpisode -> "本集"
                            state.sleepRemainingMs != null -> formatSleepRemaining(state.sleepRemainingMs)
                            else -> "睡眠"
                        },
                        description = "睡眠定时",
                        active = state.sleepRemainingMs != null || state.sleepEndOfEpisode,
                        iconSize = 22.dp,
                        onClick = { sleepSheet = true }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onSeekBy(-15_000) }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.FastRewind,
                            contentDescription = "后退 15 秒",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledIconButton(
                        onClick = onTogglePlay,
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(onClick = { onSeekBy(15_000) }, modifier = Modifier.size(44.dp)) {
                        Icon(
                            Icons.Default.FastForward,
                            contentDescription = "前进 15 秒",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                        // 图标表示“点一下会切到哪个模式”：正在放视频就显示耳机+音频，反之显示摄像机+视频。
                        if (state.resolvedVideoUrl.isNotBlank()) {
                            ToolIcon(
                                icon = if (state.videoMode) Icons.Outlined.Headphones else Icons.Outlined.Videocam,
                                label = if (state.videoMode) "音频" else "视频",
                                description = if (state.videoMode) "切到音频" else "切到视频",
                                active = false,
                                iconSize = 22.dp,
                                onClick = onToggleVideoMode
                            )
                        }
                    // 多音轨：设置里选好的两种语言，只在这两种之间切；只有一条音轨时不显示。
                    val langA = state.settings.audioLangA
                    val langB = state.settings.audioLangB
                    val hasA = state.audioLanguages.any { it.startsWith(langA) }
                    val hasB = state.audioLanguages.any { it.startsWith(langB) }
                    if (hasA && hasB) {
                        val onB = state.currentAudioLanguage.startsWith(langB)
                        ToolIcon(
                            icon = Icons.Outlined.Translate,
                            label = if (onB) AudioLang.shortOf(langB) else AudioLang.shortOf(langA),
                            description = if (onB) "切到${AudioLang.from(langA).label}" else "切到${AudioLang.from(langB).label}",
                            active = onB,
                            iconSize = 22.dp,
                            onClick = { onSelectAudioLanguage(if (onB) langA else langB) }
                        )
                    }
                    if (episode.audioUrl.isNotBlank()) {
                        val dl = state.downloads[episode.id]
                        val dlProgress = state.downloadProgress[episode.id]
                        val downloading = dlProgress?.isActive == true
                        val done = dl?.state == DownloadState.COMPLETED
                        ToolIcon(
                            icon = if (done) Icons.Outlined.DownloadDone else Icons.Outlined.Download,
                            label = when {
                                downloading ->
                                    dlProgress?.percent?.takeIf { it >= 0 }?.let { "$it%" } ?: ""
                                done -> "已下"
                                dl?.state == DownloadState.FAILED -> "重试"
                                dl != null -> "继续"
                                else -> "下载"
                            },
                            description = when {
                                downloading -> "取消下载"
                                done -> "删除下载"
                                else -> "下载"
                            },
                            active = done || downloading,
                            iconSize = 22.dp,
                            onClick = {
                                when {
                                    downloading -> onCancelDownload()
                                    done -> onDeleteDownload()
                                    else -> onDownload()
                                }
                            }
                        )
                    }
                }
            }

            // 倍速弹窗：7 档，点一下选，选完自动关。
            if (speedSheet) {
                AlertDialog(
                    onDismissRequest = { speedSheet = false },
                    title = { Text("播放倍速") },
                    text = {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { speed ->
                                FilterChip(
                                    selected = state.speed == speed,
                                    onClick = {
                                        onSpeed(speed)
                                        speedSheet = false
                                    },
                                    label = { Text("${speed}×") }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { speedSheet = false }) { Text("取消") }
                    }
                )
            }

            // 睡眠弹窗：四档倒计时 + 播完本集停 + 关闭。
            if (sleepSheet) {
                AlertDialog(
                    onDismissRequest = { sleepSheet = false },
                    title = { Text("睡眠定时") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟")
                                    .forEach { (minutes, label) ->
                                        FilterChip(
                                            selected = state.sleepMinutes == minutes,
                                            onClick = {
                                                onSleepMinutes(minutes)
                                                sleepSheet = false
                                            },
                                            label = { Text(label) }
                                        )
                                    }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = state.sleepEndOfEpisode,
                                    onClick = {
                                        onSleepEndOfEpisode()
                                        sleepSheet = false
                                    },
                                    label = { Text("播完本集停") }
                                )
                                if (state.sleepRemainingMs != null || state.sleepEndOfEpisode) {
                                    TextButton(onClick = {
                                        onCancelSleep()
                                        sleepSheet = false
                                    }) { Text("关闭定时") }
                                }
                            }
                            if (state.sleepRemainingMs != null) {
                                Text(
                                    "还剩 ${formatSleepRemaining(state.sleepRemainingMs)}，到点自动暂停",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { sleepSheet = false }) { Text("取消") }
                    }
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字幕", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (state.transcriptStatus != TranscriptStatus.Ready) {
                            when (state.transcriptStatus) {
                                TranscriptStatus.Loading -> "加载中…"
                                TranscriptStatus.Empty -> "本集没有官方字幕"
                                TranscriptStatus.Error -> "字幕加载失败"
                                else -> ""
                            }
                        } else {
                            when (state.translationStatus) {
                                TranslationStatus.Loading ->
                                    "本段 ${state.translationDone}/${state.translationTotal}"
                                TranslationStatus.Ready -> "本段已译 ${state.translationDone} 条"
                                TranslationStatus.NoKey -> "未配置 Key"
                                TranslationStatus.Error -> "翻译失败"
                                TranslationStatus.Idle -> "未翻译"
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.transcriptStatus == TranscriptStatus.Ready) {
                        // 0.4.0：ASR 字幕明确标明可能有错。
                        if (state.transcriptSource == TranscriptSource.ASR) {
                            Text(
                                if (!state.asrComplete && state.asrTotalMs > 0)
                                    "AI 字幕·只识别到 ${formatTime(state.asrAnalyzedMs)}"
                                else "AI 字幕·可能有错",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (!state.asrComplete) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        if (!autoFollow) {
                            TextButton(onClick = { autoFollow = true }) {
                                Text("回到当前句", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        TextButton(
                            enabled = true,
                            onClick = onTranslate
                        ) {
                            Text(
                                if (state.translationStatus == TranslationStatus.Loading) "停止"
                                else if (state.settings.translationMode == TranslationMode.KEYWORD)
                                    "关键词·${state.settings.keywordLevel.label}"
                                else "翻译"
                            )
                        }
                        // 生词本：翻完才出现，点一下才收，不自动收。
                        if (state.settings.translationMode == TranslationMode.KEYWORD &&
                            state.translated.any { it.isNotBlank() }
                        ) {
                            TextButton(onClick = onCollectAllWords) {
                                Text("收藏本集生词", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // 半截字幕提示：上次识别被打断（取消/被杀），后面还有没转写的部分，可以接着跑。
            if (state.transcriptStatus == TranscriptStatus.Ready &&
                state.transcriptSource == TranscriptSource.ASR &&
                !state.asrComplete && state.asrTotalMs > 0 &&
                state.asrPhase != AsrPhase.RUNNING
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "上次只识别到 ${formatTime(state.asrAnalyzedMs)}，" +
                                "后面 ${formatTime(state.asrTotalMs - state.asrAnalyzedMs)} 还没转写",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onStartAsr) {
                            Text("接着生成", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // 0.4.0 P0 收尾：整集翻译费用预估（只是估算，单价可能已变）。
            val estimate = state.translationEstimate
            if (state.transcriptStatus == TranscriptStatus.Ready && estimate != null) {
                Text(
                    if (estimate.unknownPrice) {
                        "预估：整集约 ${estimate.totalTokens} tokens · ${estimate.priceNote}"
                    } else {
                        val cost = estimate.costUsd ?: 0.0
                        val costText = if (cost < 0.01) "约 \$${"%.4f".format(cost)}" else "约 \$${"%.2f".format(cost)}"
                        "预估：整集约 ${estimate.totalTokens} tokens · $costText（${estimate.model}）· ${estimate.priceNote}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                )
            }

            // 生词本：收词结果提示（点一下关掉）
            state.wordCollectHint?.let { hint ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clickable { onDismissWordHint() }
                ) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            if (state.transcriptStatus == TranscriptStatus.Ready) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        DisplayMode.ORIGINAL to "原文",
                        DisplayMode.TRANSLATED to
                            if (state.settings.translationMode == TranslationMode.KEYWORD) "生词" else "译文",
                        DisplayMode.BILINGUAL to "双语"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = state.settings.displayMode == mode,
                            onClick = { onMode(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            when (state.transcriptStatus) {
                TranscriptStatus.Empty, TranscriptStatus.Error, TranscriptStatus.Idle -> {
                    // 注意：Column 里的子项不能用 fillMaxSize（会按整屏高度排，超出屏幕叠到控制条上）。
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (state.transcriptStatus == TranscriptStatus.Empty)
                                    "这一集没有提供官方字幕。"
                                else if (state.transcriptStatus == TranscriptStatus.Error)
                                    "字幕加载失败，点返回重进可重试。"
                                else "…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 0.4.0 本机 ASR 入口：无官方字幕时可现场生成，识别在前台 Service 里跑。
                            if (state.transcriptStatus == TranscriptStatus.Empty) {
                                Spacer(Modifier.height(16.dp))
                                when (state.asrPhase) {
                                    AsrPhase.RUNNING -> {
                                        Text(
                                            "正在生成 AI 字幕… ${(state.asrProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        TextButton(onClick = onCancelAsr) { Text("取消生成") }
                                    }
                                    AsrPhase.DOWNLOADING_MODEL -> {
                                        Text(
                                            "正在下载识别模型… ${(state.asrProgress * 100).toInt()}%",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        TextButton(onClick = onCancelAsr) { Text("取消下载") }
                                    }
                                    AsrPhase.ERROR -> {
                                        Text(
                                            "AI 字幕生成失败：${state.asrError ?: "未知错误"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Button(onClick = onStartAsr) { Text("重新生成 AI 字幕") }
                                    }
                                    else -> {
                                        Button(onClick = onStartAsr) { Text("生成 AI 字幕") }
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "AI 字幕·可能有错",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                TranscriptStatus.Loading -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("正在拉取字幕…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                TranscriptStatus.Ready -> {
                    val activeIndex = state.cues.indexOfLast { it.startMs <= state.positionMs }
                    var programmatic by remember { mutableStateOf(false) }

                    // 播放推进到新句时把当前句滚进视野；用户手动滑动后停止跟随，点「回到当前句」恢复
                    LaunchedEffect(activeIndex, autoFollow) {
                        if (!autoFollow || activeIndex < 0 || state.cues.isEmpty()) return@LaunchedEffect
                        val target = (activeIndex - 1).coerceAtLeast(0)
                        if (target != listState.firstVisibleItemIndex) {
                            programmatic = true
                            listState.scrollToItem(target)
                            programmatic = false
                        }
                    }
                    LaunchedEffect(listState.isScrollInProgress) {
                        if (listState.isScrollInProgress && !programmatic) autoFollow = false
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(state.cues) { index, cue ->
                            val active = index == activeIndex
                            Surface(
                                onClick = { onSeek(cue.startMs) },
                                color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.background,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        formatTime(cue.startMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(56.dp)
                                    )
                                    val translation = state.translated.getOrNull(index).orEmpty()
                                    val mode = state.settings.displayMode
                                    Column(modifier = Modifier.weight(1f)) {
                                        val keywordMode = state.settings.translationMode == TranslationMode.KEYWORD
                                        // 生词模式下原句永远保留：生词离开句子就没法跟读了。
                                        if (mode != DisplayMode.TRANSLATED || translation.isBlank() || keywordMode) {
                                            Text(
                                                cue.text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                                            )
                                        }
                                        if (translation.isNotBlank() && mode != DisplayMode.ORIGINAL) {
                                            Spacer(Modifier.height(2.dp))
                                            if (state.settings.translationMode == TranslationMode.KEYWORD) {
                                                // 关键词模式：每条生词做成可点的词片，点一下收/取消。
                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    KeywordParser.parseLine(translation).forEach { w ->
                                                        val collected = state.collectedWords
                                                            .contains(w.word.lowercase())
                                                        val tint = if (collected)
                                                            MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.secondary
                                                        Text(
                                                            text = buildString {
                                                                append(w.word)
                                                                if (w.ipa.isNotBlank()) append(' ').append(w.ipa)
                                                                append("  ").append(w.gloss)
                                                            },
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = tint,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(6.dp))
                                                                .clickable {
                                                                    onToggleWord(w.word, w.pos, w.ipa, w.gloss, index)
                                                                }
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            } else {
                                                Text(
                                                    translation,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

/** 倍速显示：整数去掉小数点（2×），其余保留两位（1.25×）。 */
private fun formatSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "${speed}×"

/** 控制行里的小工具按钮：图标 + 下面一行小字（无内容时留空占位，保证高度一致）。 */
@Composable
private fun ToolIcon(
    icon: ImageVector,
    label: String,
    description: String,
    active: Boolean,
    iconSize: Dp = 20.dp,
    onClick: () -> Unit
) {
    val tint = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .width(46.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(iconSize), tint = tint)
        Text(
            text = label.ifBlank { " " },
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}

/** 下载行用的短字节数（KB/MB 一位小数），与 UpdateScreen 的 formatBytes 同款口径。 */
private fun formatBytesShort(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}


/** 从 Compose 的 Context（常被 ContextWrapper 包着）里找到宿主 Activity。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}
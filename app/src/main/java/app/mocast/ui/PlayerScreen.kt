package app.mocast.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import app.mocast.asr.AsrPhase
import app.mocast.asr.TranscriptSource
import app.mocast.data.DisplayMode
import app.mocast.data.TranslationMode
import app.mocast.download.DownloadState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
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
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onStartAsr: () -> Unit,
    onCancelAsr: () -> Unit,
    onSleepMinutes: (Int) -> Unit,
    onSleepEndOfEpisode: () -> Unit,
    onCancelSleep: () -> Unit
) {
    val episode = state.currentEpisode ?: return
    val showTitle = state.currentShow?.title.orEmpty()
    val total = if (state.durationMs > 0) state.durationMs else 1L
    var scrub by remember(state.currentEpisode?.id) { mutableStateOf<Float?>(null) }
    val listState = rememberLazyListState()
    var autoFollow by remember(state.currentEpisode?.id) { mutableStateOf(true) }

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
            Row {
                Cover(episode.artwork.ifBlank { state.currentShow?.artwork.orEmpty() }, 68.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        showTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

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

            Slider(
                value = (scrub ?: state.positionMs.toFloat()).coerceIn(0f, total.toFloat()),
                valueRange = 0f..total.toFloat(),
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { onSeek(it.toLong()) }
                    scrub = null
                }
            )
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { onSeekBy(-15_000) }) { Text("−15s") }
                Spacer(Modifier.width(18.dp))
                FilledIconButton(
                    onClick = onTogglePlay,
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(Modifier.width(18.dp))
                TextButton(onClick = { onSeekBy(15_000) }) { Text("+15s") }
            }

            // 0.4.0 补项：7 档倍速（0.75–2.5），横向滚动放得下；选中档存进设置，下次沿用。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { speed ->
                    FilterChip(
                        selected = state.speed == speed,
                        onClick = { onSpeed(speed) },
                        label = { Text("${speed}×") }
                    )
                }
            }

            // 0.4.0 补项：睡眠定时（倒计时到点暂停 / 播完本集停），可取消。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "睡眠",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(15 to "15分", 30 to "30分", 45 to "45分", 60 to "60分").forEach { (minutes, label) ->
                    FilterChip(
                        selected = state.sleepMinutes == minutes,
                        onClick = { onSleepMinutes(minutes) },
                        label = { Text(label) }
                    )
                }
                FilterChip(
                    selected = state.sleepEndOfEpisode,
                    onClick = onSleepEndOfEpisode,
                    label = { Text("播完本集停") }
                )
                if (state.sleepRemainingMs != null || state.sleepEndOfEpisode) {
                    TextButton(onClick = onCancelSleep) { Text("取消") }
                }
            }
            if (state.sleepRemainingMs != null) {
                Text(
                    "睡眠定时还剩 ${formatSleepRemaining(state.sleepRemainingMs)}，到点自动暂停",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }

            // 0.4.0 YouTube 音频/视频切换：只有解析出视频流的单集才出现。
            if (state.resolvedVideoUrl.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    FilterChip(
                        selected = !state.videoMode,
                        onClick = { if (state.videoMode) onToggleVideoMode() },
                        label = { Text("音频") }
                    )
                    FilterChip(
                        selected = state.videoMode,
                        onClick = { if (!state.videoMode) onToggleVideoMode() },
                        label = { Text("视频") }
                    )
                }
            }

            // 0.4.0 下载行：YouTube 单集没有直接音频地址，不支持下载，不显示。
            if (episode.audioUrl.isNotBlank()) {
                val dl = state.downloads[episode.id]
                val dlProgress = state.downloadProgress[episode.id]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dlText = when {
                        dl?.state == DownloadState.COMPLETED -> "已下载 · 离线可播"
                        dlProgress?.isActive == true ->
                            if (dlProgress.percent >= 0) "下载中 ${dlProgress.percent}%"
                            else "下载中 ${formatBytesShort(dlProgress.bytesDownloaded)}"
                        dl?.state == DownloadState.FAILED -> "下载失败，点重试可断点续传"
                        dl != null -> "已下载 ${formatBytesShort(dl.bytesDownloaded)}（未完成）"
                        else -> "未下载"
                    }
                    Text(
                        dlText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when {
                        dl?.state == DownloadState.COMPLETED ->
                            TextButton(onClick = onDeleteDownload) { Text("删除") }
                        dlProgress?.isActive == true ->
                            TextButton(onClick = onCancelDownload) { Text("取消") }
                        dl != null ->
                            TextButton(onClick = onDownload) { Text("继续下载") }
                        else ->
                            TextButton(onClick = onDownload) { Text("下载本集") }
                    }
                }
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
                                TranslationStatus.Idle -> if (state.settings.translationMode == TranslationMode.KEYWORD)
                                    "未翻译 · 关键词" else "未翻译 · 完整"
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.transcriptStatus == TranscriptStatus.Ready) {
                        // 0.4.0：ASR 字幕明确标明可能有错。
                        if (state.transcriptSource == TranscriptSource.ASR) {
                            Text(
                                "AI 字幕·可能有错",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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

                    Box(Modifier.fillMaxSize()) {
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
                                        if (mode != DisplayMode.TRANSLATED || translation.isBlank()) {
                                            Text(
                                                cue.text,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal
                                            )
                                        }
                                        if (translation.isNotBlank() && mode != DisplayMode.ORIGINAL) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                translation,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (state.settings.translationMode == TranslationMode.KEYWORD)
                                                    MaterialTheme.colorScheme.secondary
                                                else MaterialTheme.colorScheme.primary
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

/** 下载行用的短字节数（KB/MB 一位小数），与 UpdateScreen 的 formatBytes 同款口径。 */
private fun formatBytesShort(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

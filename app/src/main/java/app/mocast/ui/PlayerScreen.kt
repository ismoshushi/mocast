package app.mocast.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import app.mocast.data.DisplayMode
import app.mocast.data.TranslationMode
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: UiState,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onTranslate: () -> Unit,
    onMode: (DisplayMode) -> Unit
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                listOf(1f, 1.25f, 1.5f, 2f).forEach { speed ->
                    FilterChip(
                        selected = state.speed == speed,
                        onClick = { onSpeed(speed) },
                        label = { Text("${speed}×") }
                    )
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
                        Text(
                            if (state.transcriptStatus == TranscriptStatus.Empty)
                                "这一集没有提供官方字幕。\n0.03 会用本机 ASR 补一条。"
                            else if (state.transcriptStatus == TranscriptStatus.Error)
                                "字幕加载失败，点返回重进可重试。"
                            else "…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

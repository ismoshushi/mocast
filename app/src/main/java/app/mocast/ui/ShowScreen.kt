package app.mocast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mocast.data.Episode
import app.mocast.data.room.DownloadEntity
import app.mocast.download.DownloadProgress
import app.mocast.download.DownloadState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowScreen(
    state: UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenEpisode: (Episode) -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onDownload: (Episode) -> Unit,
    onCancelDownload: (Episode) -> Unit,
    onDeleteDownload: (Episode) -> Unit,
    onEnqueue: (Episode) -> Unit,
    onTogglePlayed: (Episode) -> Unit,
    onToggleUnplayedOnly: () -> Unit,
    showMiniPlayer: Boolean = false
) {
    val show = state.currentShow ?: return
    // 0.4.0 补项：“只看未听”只过滤展示，不动 state.episodes（自动接下一集仍按全表走）。
    val unplayedCount = state.episodes.count { !it.played }
    val visibleEpisodes = if (state.showUnplayedOnly) state.episodes.filter { !it.played } else state.episodes
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(show.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(
                            Modifier
                                .padding(12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "取消订阅")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = if (showMiniPlayer) 96.dp else 24.dp)
        ) {
            item {
                Row(modifier = Modifier.padding(16.dp)) {
                    Cover(show.artwork, 92.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(show.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            show.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (show.description.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                show.description,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "共 ${state.episodes.size} 集 · $unplayedCount 未听",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    // 0.4.0 补项：只看未听（未听为 0 时不显示开关）。
                    if (unplayedCount > 0 && state.episodes.size > unplayedCount) {
                        FilterChip(
                            selected = state.showUnplayedOnly,
                            onClick = onToggleUnplayedOnly,
                            label = { Text("只看未听") }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            if (visibleEpisodes.isEmpty()) {
                item {
                    Text(
                        if (state.episodes.isEmpty()) "还没有单集。点右上角刷新，或确认这个 Feed 是否带音频。"
                        else "这一档没有未听的单集了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            items(visibleEpisodes, key = { it.id }) { episode ->
                EpisodeRow(
                    episode = episode,
                    playing = state.currentEpisode?.id == episode.id,
                    download = state.downloads[episode.id],
                    progress = state.downloadProgress[episode.id],
                    inQueue = state.queue.any { it.id == episode.id },
                    onClick = { onOpenEpisode(episode) },
                    onDownload = { onDownload(episode) },
                    onCancelDownload = { onCancelDownload(episode) },
                    onDeleteDownload = { onDeleteDownload(episode) },
                    onEnqueue = { onEnqueue(episode) },
                    onTogglePlayed = { onTogglePlayed(episode) }
                )
            }
        }
    }

    if (state.confirmDeleteShowId != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("取消订阅") },
            text = { Text("会删掉这个节目和已经缓存的单集，播放进度保留。") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    playing: Boolean,
    download: DownloadEntity?,
    progress: DownloadProgress?,
    inQueue: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onEnqueue: () -> Unit,
    onTogglePlayed: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (playing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 0.4.0 补项：已听的整行变淡（正在播的那集不淡，播放态优先）。
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                episode.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (episode.played && !playing) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val date = formatDate(episode.publishedAt)
                if (date.isNotEmpty()) {
                    Text(
                        date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val dur = formatDuration(episode.durationSec)
                if (dur.isNotEmpty()) {
                    Text(
                        dur,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (episode.played) {
                    Text(
                        "✓ 已听",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (episode.transcriptUrl.isNotBlank()) {
                    Text(
                        "字幕",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (episode.audioUrl.isBlank()) {
                    Text(
                        "视频",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 0.4.0 下载状态：已下载 / 下载中 / 失败，未下载不占位。
                if (episode.audioUrl.isNotBlank()) {
                    when {
                        download?.state == DownloadState.COMPLETED -> Text(
                            "已下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        progress?.isActive == true -> Text(
                            if (progress.percent >= 0) "下载中 ${progress.percent}%"
                            else "下载中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        download?.state == DownloadState.FAILED -> Text(
                            "下载失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        download != null -> Text(
                            "下载未完成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            // 0.4.0 下载操作：YouTube 单集没有直接音频地址，不提供下载。
            if (episode.audioUrl.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    when {
                        download?.state == DownloadState.COMPLETED ->
                            TextButton(onClick = onDeleteDownload) { Text("删除下载", style = MaterialTheme.typography.labelSmall) }
                        progress?.isActive == true ->
                            TextButton(onClick = onCancelDownload) { Text("取消下载", style = MaterialTheme.typography.labelSmall) }
                        download != null ->
                            TextButton(onClick = onDownload) { Text("继续下载", style = MaterialTheme.typography.labelSmall) }
                        else ->
                            TextButton(onClick = onDownload) { Text("下载", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            // 0.4.0 补项：稍后听 + 标已听/未听（与下载行分开，不抢下载按钮的位置）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEnqueue, enabled = !inQueue) {
                    Text(if (inQueue) "已在队列" else "稍后听", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onTogglePlayed) {
                    Text(if (episode.played) "标为未听" else "标为已听", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
}

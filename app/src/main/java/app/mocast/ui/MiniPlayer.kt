package app.mocast.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mocast.data.Episode

/**
 * 迷你播放条（0.4.0 补项）：首页与节目详情页底部常驻。
 *
 * - 只在有当前单集且不在播放页时显示（AppRoot 里判断），播放页本身不重复显示。
 * - 封面 + 标题 + 播放/暂停 + 进度条，点整条进播放页；队列图标点开队列弹窗。
 * - 进度用 positionMs/durationMs 算，不另起轮询（AppViewModel 的 ticker
 *   本来就 500ms 推一次位置）。
 */
@Composable
fun MiniPlayer(
    state: UiState,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val episode = state.currentEpisode ?: return
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenPlayer)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Cover(episode.artwork.ifBlank { state.currentShow?.artwork.orEmpty() }, 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        episode.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        state.currentShow?.title.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.Default.QueueMusic, contentDescription = "播放队列")
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放"
                    )
                }
            }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 队列弹窗（0.4.0 补项）：列表、上移/下移、移除、清空、点播。
 * 队列存在 Room 里，弹窗只是读 UiState.queue；改动走 ViewModel 的队列函数。
 */
@Composable
fun QueueDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onPlay: (Episode) -> Unit,
    onMoveUp: (Episode) -> Unit,
    onMoveDown: (Episode) -> Unit,
    onRemove: (Episode) -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放队列（${state.queue.size}）") },
        text = {
            if (state.queue.isEmpty()) {
                Text(
                    "队列是空的。在单集行点“稍后听”，播完当前这集会先播队列里的。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(state.queue, key = { _, episode -> episode.id }) { index, episode ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onPlay(episode) }
                            ) {
                                Text(
                                    episode.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    formatDuration(episode.durationSec),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onMoveUp(episode) },
                                enabled = index > 0
                            ) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = "上移")
                            }
                            IconButton(
                                onClick = { onMoveDown(episode) },
                                enabled = index < state.queue.size - 1
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "下移")
                            }
                            IconButton(onClick = { onRemove(episode) }) {
                                Icon(Icons.Default.Close, contentDescription = "移出队列")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            Row {
                if (state.queue.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清空")
                    }
                }
            }
        }
    )
}

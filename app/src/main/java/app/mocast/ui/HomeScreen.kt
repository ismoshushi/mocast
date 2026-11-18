package app.mocast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mocast.data.RecommendedShows
import app.mocast.data.Show
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    onAddClick: () -> Unit,
    onDismissAdd: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenShow: (String) -> Unit,
    onSettings: () -> Unit,
    /** 迷你播放条出现时：列表底部留空、订阅按钮上移，避免被播放条盖住。 */
    showMiniPlayer: Boolean = false,
    /** 0.4.0 P0 收尾：推荐订阅（只在无订阅时展示，点一个订阅一个）。 */
    onSubscribeRecommended: (String) -> Unit = {},
    subscribingFeedUrl: String? = null,
    subscribedFeedUrls: Set<String> = emptySet()
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MoCast", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${app.mocast.BuildConfig.VERSION_NAME} · 双语字幕 · 订阅 · 播放",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddClick,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("订阅") },
                modifier = if (showMiniPlayer) Modifier.padding(bottom = 72.dp) else Modifier
            )
        }
    ) { padding ->
        if (state.shows.isEmpty()) {
            // 0.4.0 P0 收尾：无订阅时给推荐区，不自动订阅任何节目。
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, if (showMiniPlayer) 88.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { EmptyHome() }
                item {
                    Text(
                        "推荐订阅 · 点一个订阅一个",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(RecommendedShows.all, key = { it.feedUrl }) { show ->
                    RecommendedCard(
                        show = show,
                        subscribed = show.feedUrl.trim() in subscribedFeedUrls,
                        subscribing = subscribingFeedUrl == show.feedUrl,
                        onSubscribe = { onSubscribeRecommended(show.feedUrl) }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, if (showMiniPlayer) 88.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.shows, key = { it.id }) { show ->
                    ShowCard(show, state.unplayedCounts[show.id] ?: 0) { onOpenShow(show.id) }
                }
            }
        }
    }

    if (state.addDialog) {
        AddSubscriptionDialog(
            busy = state.busy,
            onDismiss = onDismissAdd,
            onSubmit = onSubmit
        )
    }
}

@Composable
private fun EmptyHome() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 16.dp)
    ) {
        Text("还没有订阅", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "支持 RSS 直链、Apple Podcasts、YouTube 频道/播放列表、小宇宙。\n点右下角「订阅」粘贴链接即可，也可以先从下面推荐里挑一个。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RecommendedCard(
    show: app.mocast.data.RecommendedShow,
    subscribed: Boolean,
    subscribing: Boolean,
    onSubscribe: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    show.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        show.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                show.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "RSS 来源：${show.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    enabled = !subscribed && !subscribing,
                    onClick = onSubscribe
                ) {
                    Text(
                        when {
                            subscribed -> "已订阅"
                            subscribing -> "订阅中…"
                            else -> "订阅"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowCard(show: Show, unplayedCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Cover(show.artwork, 66.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    show.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    show.author.ifBlank { show.source },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${show.episodeCount} 集 · ${formatDate(show.lastUpdated)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 0.4.0 补项：未听数角标（0 不显示，不占位）。
                    if (unplayedCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "${unplayedCount} 未听",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Cover(url: String, size: androidx.compose.ui.unit.Dp) {
    val modifier = Modifier
        .size(size)
        .clip(RoundedCornerShape(12.dp))
    if (url.startsWith("http")) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Surface(
            modifier = modifier,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("M", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("添加订阅") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("链接") },
                    placeholder = { Text("https://…") },
                    minLines = 2,
                    maxLines = 4,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Apple Podcasts：podcasts.apple.com/…\nYouTube：youtube.com/@频道名\n小宇宙：xiaoyuzhoufm.com/…\n或任意 RSS 地址",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && input.isNotBlank(),
                onClick = { onSubmit(input) }
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("添加")
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
        }
    )
}

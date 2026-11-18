package app.mocast.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.ui.collectAsStateWithLifecycle()

    // 0.4.0 P0 收尾：OPML 导入（系统文件选择器）与导出（SAF 保存文件）。
    // 导入的 Uri 直接交给 ViewModel 读；导出先由 ViewModel 备好文本，再弹保存框。
    val importOpmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.importOpmlUri(uri) }
    val exportOpmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/xml")
    ) { uri ->
        if (uri != null) vm.writeOpmlExport(uri) else vm.consumeOpmlExport()
    }
    LaunchedEffect(state.opmlExportText) {
        if (state.opmlExportText != null) {
            exportOpmlLauncher.launch("mocast-subscriptions.opml")
        }
    }

    MoCastTheme(state.settings.themeMode) {
        BackHandler(enabled = state.screen != Screen.Home) { vm.back() }

        Box(Modifier.fillMaxSize()) {
            // 迷你播放条（0.4.0 补项）：有当前单集且不在播放页时常驻底部。
            val showMiniPlayer = state.currentEpisode != null && state.screen != Screen.Player
            when (val screen = state.screen) {
                Screen.Home -> HomeScreen(
                    state = state,
                    onAddClick = vm::openAddDialog,
                    onDismissAdd = vm::dismissAddDialog,
                    onSubmit = vm::addSubscription,
                    onOpenShow = vm::openShow,
                    onSettings = vm::openSettings,
                    showMiniPlayer = showMiniPlayer,
                    onSubscribeRecommended = vm::subscribeRecommended,
                    subscribingFeedUrl = state.subscribingFeedUrl,
                    subscribedFeedUrls = state.shows.map { it.feedUrl.trim() }.toSet()
                )

                Screen.Settings -> SettingsScreen(
                    state = state,
                    onBack = vm::back,
                    onOpenTranslation = vm::openTranslation,
                    onOpenAbout = vm::openAbout,
                    onThemeMode = vm::setThemeMode,
                    onSaveSettings = vm::saveSettings,
                    onImportOpml = { importOpmlLauncher.launch("*/*") },
                    onExportOpml = vm::prepareOpmlExport,
                    opmlImportSummary = state.opmlImportSummary,
                    onDismissOpmlSummary = vm::dismissOpmlSummary
                )

                Screen.Translation -> TranslationScreen(
                    settings = state.settings,
                    onBack = vm::back,
                    onSave = vm::saveSettings,
                    onMode = vm::setTranslationMode,
                    onLevel = vm::setKeywordLevel,
                    onAutoExtend = vm::setAutoExtend,
                    onTestConnection = vm::testConnection,
                    testRunning = state.testRunning,
                    testResult = state.testResult,
                    testOk = state.testOk,
                    onClearTestResult = vm::clearTestResult
                )

                Screen.About -> AboutScreen(
                    state = state,
                    onBack = vm::back,
                    onCheck = vm::checkUpdate,
                    onDownload = vm::downloadUpdate,
                    onInstall = vm::installUpdate,
                    canInstall = vm::canInstallUpdate
                )

                is Screen.ShowDetail -> ShowScreen(
                    state = state,
                    onBack = vm::back,
                    onRefresh = { vm.refresh(screen.showId) },
                    onOpenEpisode = vm::openEpisode,
                    onDelete = { vm.requestDelete(screen.showId) },
                    onConfirmDelete = vm::confirmDelete,
                    onCancelDelete = vm::cancelDelete,
                    onDownload = vm::downloadEpisode,
                    onCancelDownload = { episode -> vm.cancelDownload(episode.id) },
                    onDeleteDownload = { episode -> vm.deleteDownload(episode.id) },
                    onEnqueue = vm::enqueueEpisode,
                    onTogglePlayed = vm::togglePlayed,
                    onToggleUnplayedOnly = vm::toggleUnplayedOnly,
                    showMiniPlayer = showMiniPlayer
                )

                is Screen.Player -> PlayerScreen(
                    state = state,
                    player = vm.player,
                    onBack = vm::back,
                    onTogglePlay = vm::togglePlay,
                    onSeek = vm::seekTo,
                    onSeekBy = vm::seekBy,
                    onSpeed = vm::setSpeed,
                    onTranslate = vm::translateCurrent,
                    onMode = vm::setDisplayMode,
                    onToggleVideoMode = vm::toggleVideoMode,
                    onDownload = { state.currentEpisode?.let { vm.downloadEpisode(it) } },
                    onCancelDownload = { state.currentEpisode?.let { vm.cancelDownload(it.id) } },
                    onDeleteDownload = { state.currentEpisode?.let { vm.deleteDownload(it.id) } },
                    onStartAsr = vm::startAsr,
                    onCancelAsr = vm::cancelAsr,
                    onSleepMinutes = vm::setSleepMinutes,
                    onSleepEndOfEpisode = vm::setSleepEndOfEpisode,
                    onCancelSleep = vm::cancelSleep
                )
            }

            // 底部常驻区：迷你播放条在上，出错提示在下（两者同时出现时不重叠）。
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                if (showMiniPlayer) {
                    MiniPlayer(
                        state = state,
                        onOpenPlayer = vm::openPlayer,
                        onTogglePlay = vm::togglePlay,
                        onOpenQueue = vm::openQueueDialog
                    )
                }
                if (state.queueDialog) {
                    QueueDialog(
                        state = state,
                        onDismiss = vm::dismissQueueDialog,
                        onPlay = { episode ->
                            vm.dismissQueueDialog()
                            vm.openEpisode(episode)
                        },
                        onMoveUp = { episode -> vm.moveQueue(episode.id, up = true) },
                        onMoveDown = { episode -> vm.moveQueue(episode.id, up = false) },
                        onRemove = { episode -> vm.removeFromQueue(episode.id) },
                        onClear = vm::clearQueue
                    )
                }
            }

            state.error?.let { message ->
                LaunchedEffect(message) {
                    delay(4000)
                    vm.consumeError()
                }
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        // 迷你播放条在时把提示往上顶 72dp，避免盖住播放条。
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = if (showMiniPlayer) 84.dp else 20.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = vm::consumeError) { Text("知道了") }
                    }
                }
            }
        }
    }
}

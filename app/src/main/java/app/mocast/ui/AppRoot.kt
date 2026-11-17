package app.mocast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
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

    BackHandler(enabled = state.screen != Screen.Home) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        when (val screen = state.screen) {
            Screen.Home -> HomeScreen(
                state = state,
                onAddClick = vm::openAddDialog,
                onDismissAdd = vm::dismissAddDialog,
                onSubmit = vm::addSubscription,
                onOpenShow = vm::openShow,
                onSettings = vm::openSettings
            )

            Screen.Settings -> SettingsScreen(
                settings = state.settings,
                onBack = vm::back,
                onSave = vm::saveSettings
            )

            is Screen.ShowDetail -> ShowScreen(
                state = state,
                onBack = vm::back,
                onRefresh = { vm.refresh(screen.showId) },
                onOpenEpisode = vm::openEpisode,
                onDelete = { vm.requestDelete(screen.showId) },
                onConfirmDelete = vm::confirmDelete,
                onCancelDelete = vm::cancelDelete
            )

            is Screen.Player -> PlayerScreen(
                state = state,
                onBack = vm::back,
                onTogglePlay = vm::togglePlay,
                onSeek = vm::seekTo,
                onSeekBy = vm::seekBy,
                onSpeed = vm::setSpeed,
                onTranslate = vm::translateCurrent,
                onMode = vm::setDisplayMode
            )
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
                    .padding(horizontal = 16.dp, vertical = 20.dp)
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

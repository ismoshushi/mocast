package app.mocast.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mocast.update.UpdateInfo
import app.mocast.update.UpdateState
import app.mocast.update.displayVersion

@Composable
fun UpdateCard(
    state: UiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    canInstall: () -> Boolean
) {
    val context = LocalContext.current
    val update = state.update
    val info = update.info()
    // 从系统「允许安装应用」回来时重新判断权限
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose {} }
    val canInstallNow = remember(resumed, update) { canInstall() }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "新版本 ${info?.versionName?.let { displayVersion(it) }.orEmpty()}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if ((info?.sizeBytes ?: 0) > 0) {
                    Text(
                        formatBytes(info!!.sizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (info != null && info.notes.isNotBlank() && update !is UpdateState.Installing) {
                Spacer(Modifier.height(8.dp))
                Text(
                    info.notes.lines().take(8).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            val fraction = (update as? UpdateState.Downloading)?.let {
                if (it.totalBytes > 0) it.doneBytes.toFloat() / it.totalBytes else 0f
            }
            if (fraction != null) {
                Spacer(Modifier.height(10.dp))
                Progress(fraction)
            }

            Spacer(Modifier.height(12.dp))
            when (update) {
                is UpdateState.Checking -> Text(
                    "正在检查…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is UpdateState.Available -> Button(onClick = onDownload) {
                    Text("下载并安装")
                }

                is UpdateState.Downloading -> Text(
                    "已下载 ${formatBytes(update.doneBytes)} / ${formatBytes(update.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is UpdateState.Ready -> if (canInstallNow) {
                    Button(onClick = onInstall) { Text("安装") }
                } else {
                    Button(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("去允许安装") }
                }

                is UpdateState.Installing -> Text(
                    "请在系统弹窗里点「更新」或「继续安装」",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                is UpdateState.Failed -> {
                    Text(
                        update.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = if (update.info != null) onDownload else onCheck) {
                        Text(if (update.info != null) "重试下载" else "重试")
                    }
                }

                else -> OutlinedButton(onClick = onCheck) { Text("检查更新") }
            }
        }
    }
}

@Composable
private fun Progress(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

private fun UpdateState.info(): UpdateInfo? = when (this) {
    is UpdateState.Available -> info
    is UpdateState.Downloading -> info
    is UpdateState.Ready -> info
    is UpdateState.Installing -> info
    is UpdateState.Failed -> info
    else -> null
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = 1024.0 * 1024
    return "%.1f MB".format(bytes / mb)
}

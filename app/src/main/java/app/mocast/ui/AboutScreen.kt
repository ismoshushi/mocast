package app.mocast.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mocast.BuildConfig
import app.mocast.update.UpdateState
import app.mocast.update.displayVersion

private const val REPO_URL = "https://github.com/ismoshushi/mocast"

private val HIGHLIGHTS = listOf(
    "一条链接订阅：RSS、Apple Podcasts、YouTube 频道或播放列表、小宇宙",
    "后台播放交给系统前台服务：通知栏、锁屏、耳机键都能控制",
    "倍速 1.0× – 2.0×，前后 15 秒，断点续播，播完自动下一集",
    "官方字幕 VTT / SRT / JSON，点一句就跳到对应时间",
    "双语字幕：接任意 OpenAI 兼容接口，原文 / 译文 / 双语三种模式",
    "译文按集按语言落在本地，重进不重复请求，不重复花钱"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    state: UiState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    canInstall: () -> Boolean
) {
    val context = LocalContext.current
    val version = displayVersion(BuildConfig.VERSION_NAME)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("关于 MoCast") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .shadow(
                            12.dp,
                            RoundedCornerShape(20.dp),
                            ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        )
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "M",
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "MoCast",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "把播客装进口袋，听懂每一集。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "版本 $version",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    if (state.update is UpdateState.Checking) {
                        Text(
                            "检查中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    } else {
                        Text(
                            "检查更新",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onCheck)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                when (val update = state.update) {
                    is UpdateState.UpToDate -> Text(
                        "已是最新",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is UpdateState.Failed -> if (update.info == null) {
                        Text(
                            update.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                    else -> Unit
                }
            }

            val update = state.update
            val showCard = update is UpdateState.Available ||
                update is UpdateState.Downloading ||
                update is UpdateState.Ready ||
                update is UpdateState.Installing ||
                (update is UpdateState.Failed && update.info != null)
            if (showCard) {
                UpdateCard(
                    state = state,
                    onCheck = onCheck,
                    onDownload = onDownload,
                    onInstall = onInstall,
                    canInstall = canInstall
                )
            }

            AboutCard("MoCast 是什么") {
                Text(
                    "一条链接订阅，节目、单集、字幕就都出来；播放交给系统前台服务，锁屏和通勤路上都不停。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "没有账号，没有云端，订阅、进度和译文都只在这台手机上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AboutCard("主要能做什么") {
                HIGHLIGHTS.forEachIndexed { index, line ->
                    if (index > 0) Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            AboutCard("隐私") {
                Text(
                    "API Key 只写在系统偏好里，翻译请求直连你填的 Base URL；MoCast 没有服务器，也拿不到你的 Key。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "订阅、字幕缓存、播放进度都在应用私有目录，卸载即清；没有统计埋点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AboutCard("许可证") {
                Text(
                    "以 AGPL-3.0 发布：可以读、改、商用；改完通过网络向别人提供服务，需要同样以 AGPL-3.0 公开源码。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                LinkRow("源代码", REPO_URL)
            }

            AboutCard("问题反馈") {
                Text(
                    "遇到闪退、字幕解析或翻译不对，复制下面这段版本信息发给开发。只有版本、机型和系统，不含 Key 与订阅内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    val text = diagnostic()
                    context.copyToClipboard(text)
                }) { Text("复制版本信息") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkRow(title: String, url: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onLongClick = {
                    clipboard.setText(AnnotatedString(url))
                    Toast.makeText(context, "已复制链接", Toast.LENGTH_SHORT).show()
                }
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                url.removePrefix("https://"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "点一下用浏览器打开，长按复制链接",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun diagnostic(): String {
    val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    return "MoCast ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})\n" +
        "$brand ${Build.MODEL}\n" +
        "Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}"
}

private fun Context.copyToClipboard(text: String) {
    val manager = getSystemService(android.content.ClipboardManager::class.java)
    manager?.setPrimaryClip(android.content.ClipData.newPlainText("MoCast", text))
    Toast.makeText(this, "已复制版本信息", Toast.LENGTH_SHORT).show()
}

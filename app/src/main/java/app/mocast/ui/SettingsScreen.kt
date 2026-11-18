package app.mocast.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import app.mocast.BuildConfig
import app.mocast.asr.AsrModel
import app.mocast.data.Settings
import app.mocast.data.ThemeMode
import app.mocast.net.OpmlImportSummary
import app.mocast.update.displayVersion
import app.mocast.update.updateSummary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onOpenTranslation: () -> Unit,
    onOpenAbout: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onSaveSettings: (Settings) -> Unit,
    /** 0.4.0 P0 收尾：OPML 导入/导出（入口放设置页，见“订阅”组）。 */
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
    opmlImportSummary: OpmlImportSummary? = null,
    onDismissOpmlSummary: () -> Unit = {}
) {
    val settings = state.settings
    val version = displayVersion(BuildConfig.VERSION_NAME)
    val (updateLine, fresh) = updateSummary(version, state.update)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            SectionLabel("翻译")
            Group {
                SettingRow(
                    icon = Icons.Default.Translate,
                    title = "翻译服务",
                    value = if (settings.ready) settings.model else "未配置 Key"
                ) { onOpenTranslation() }
            }

            SectionLabel("订阅")
            Group {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onImportOpml, modifier = Modifier.weight(1f)) { Text("导入 OPML") }
                        Button(onClick = onExportOpml, modifier = Modifier.weight(1f)) { Text("导出 OPML") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "导入：选一个 .opml/.xml 文件，已订阅的自动跳过，导完会报成功/跳过/失败数。\n导出：把现有订阅存成标准 OPML 2.0 文件，你自己选保存位置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionLabel("代理")
            Group {
                var proxyEnabled by remember(settings.proxyEnabled) { mutableStateOf(settings.proxyEnabled) }
                var proxyType by remember(settings.proxyType) { mutableStateOf(settings.proxyType) }
                var proxyHost by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
                var proxyPort by remember(settings.proxyPort) { mutableStateOf(settings.proxyPort.toString()) }
                var proxyUser by remember(settings.proxyUsername) { mutableStateOf(settings.proxyUsername) }
                var proxyPass by remember(settings.proxyPassword) { mutableStateOf(settings.proxyPassword) }
                SwitchRow("启用代理", "RSS 抓取、音频下载、翻译请求都走这个代理", proxyEnabled) { proxyEnabled = it }
                if (proxyEnabled) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("HTTP", "SOCKS").forEach { type ->
                                FilterChip(
                                    selected = proxyType == type,
                                    onClick = { proxyType = type },
                                    label = { Text(type) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = proxyHost,
                            onValueChange = { proxyHost = it },
                            label = { Text("地址（例如 127.0.0.1）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = proxyPort,
                            onValueChange = { proxyPort = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = proxyUser,
                            onValueChange = { proxyUser = it },
                            label = { Text("账号（可选）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = proxyPass,
                            onValueChange = { proxyPass = it },
                            label = { Text("密码（可选）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "SOCKS5 代理填了账号密码也不会生效（OkHttp 4.12 不支持 SOCKS 认证）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                onSaveSettings(
                                    settings.copy(
                                        proxyEnabled = true,
                                        proxyType = proxyType,
                                        proxyHost = proxyHost.trim(),
                                        proxyPort = proxyPort.toIntOrNull() ?: 8080,
                                        proxyUsername = proxyUser.trim(),
                                        proxyPassword = proxyPass
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("保存代理设置") }
                        Spacer(Modifier.height(4.dp))
                    }
                } else {
                    // 关掉代理也要立即保存，否则开关只是摆设。
                    Button(
                        onClick = { onSaveSettings(settings.copy(proxyEnabled = false)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) { Text("保存（关闭代理）") }
                }
            }

            SectionLabel("下载")
            Group {
                SwitchRow("仅在 Wi-Fi 下下载", "关掉后用流量也可以下载", settings.downloadWifiOnly) {
                    onSaveSettings(settings.copy(downloadWifiOnly = it))
                }
                Text(
                    "单集下载到应用私有目录，不进公开空间；支持断点续传。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 16.dp, bottom = 12.dp)
                )
            }

            SectionLabel("播放")
            Group {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 14.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Glyph(Icons.Default.PlayArrow)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "默认倍速",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${settings.playbackSpeed}×",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "在播放页切倍速会自动记住，下次打开沿用，不再回到 1 倍。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 16.dp, bottom = 12.dp)
                )
            }

            SectionLabel("自动刷新")
            Group {
                SwitchRow("自动刷新订阅", "每 6 小时在后台刷新全部订阅，失败静默不打扰", settings.autoRefreshEnabled) {
                    onSaveSettings(settings.copy(autoRefreshEnabled = it))
                }
                SwitchRow("仅在 Wi-Fi 下自动刷新", "关掉后用流量也可以自动刷新", settings.autoRefreshWifiOnly) {
                    onSaveSettings(settings.copy(autoRefreshWifiOnly = it))
                }
                Text(
                    "实际触发时间由系统按省电策略推迟，不保证整点；想立刻刷新去节目页点右上角。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 14.dp, end = 16.dp, bottom = 12.dp)
                )
            }

            SectionLabel("AI 字幕（本机识别）")
            Group {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AsrModel.entries.forEach { model ->
                            FilterChip(
                                selected = settings.asrModel == model.key,
                                onClick = { onSaveSettings(settings.copy(asrModel = model.key)) },
                                label = { Text("${model.key} · 约${model.approxSizeMb}MB") }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "模型在第一次生成字幕时下载，不打进安装包；base 默认，small 更准但更慢更占空间。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SwitchRow("模型下载仅限 Wi-Fi", null, settings.asrWifiOnly) {
                    onSaveSettings(settings.copy(asrWifiOnly = it))
                }
                SwitchRow("下载完成后自动生成 AI 字幕", "只对没有官方字幕的单集", settings.asrAuto) {
                    onSaveSettings(settings.copy(asrAuto = it))
                }
                Spacer(Modifier.height(8.dp))
            }

            SectionLabel("外观")
            Group {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 14.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Glyph(Icons.Default.Palette)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "主题",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    ThemeSwitcher(settings.themeMode, onThemeMode)
                }
                Text(
                    "夜色用纯黑底、深灰卡片，OLED 更省电；白日是暖白纸面。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 58.dp, end = 16.dp, bottom = 12.dp)
                )
            }

            SectionLabel("通用")
            Group {
                SettingRow(
                    icon = Icons.Default.Info,
                    title = "关于",
                    value = updateLine,
                    highlight = fresh
                ) { onOpenAbout() }
            }

            Text(
                "MoCast · AGPL-3.0 · 订阅与进度只存在本机",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 24.dp)
            )
        }
    }

    // 0.4.0 P0 收尾：OPML 导入结果弹窗。
    if (opmlImportSummary != null) {
        val summary = opmlImportSummary
        AlertDialog(
            onDismissRequest = onDismissOpmlSummary,
            title = { Text("导入完成") },
            text = {
                Column {
                    Text("成功 ${summary.added} 个 · 跳过 ${summary.skipped} 个（已订阅）· 失败 ${summary.failed} 个")
                    if (summary.failures.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        summary.failures.forEach { failure ->
                            Text(
                                failure,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissOpmlSummary) { Text("知道了") }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Glyph(icon)
        Spacer(Modifier.width(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (highlight) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Glyph(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun ThemeSwitcher(mode: ThemeMode, onMode: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to "自动",
        ThemeMode.LIGHT to "白日",
        ThemeMode.DARK to "夜色"
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(2.dp)
    ) {
        options.forEach { (value, label) ->
            val on = value == mode
            val bg by animateColorAsState(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                label = "themeSegment"
            )
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .clickable { onMode(value) }
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    sub: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

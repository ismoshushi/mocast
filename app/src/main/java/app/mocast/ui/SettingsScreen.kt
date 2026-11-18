package app.mocast.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mocast.BuildConfig
import app.mocast.asr.AsrModel
import app.mocast.data.AudioLang
import app.mocast.data.ListBadgeMode
import app.mocast.data.Settings
import app.mocast.data.ThemeMode
import app.mocast.net.OpmlImportSummary
import app.mocast.update.displayVersion
import app.mocast.update.updateSummary

/**
 * 设置页（0.4.1，参照 Mika 的结构重排）。
 *
 * Mika 的骨架：顶部一张总状态卡 → 分组标题（组标题在卡片外）→ 每组一张圆角卡片，
 * 卡片里一行一条：左边线性图标 + 标题 + 一句说明，右边值或开关，能进下一页的带 ›。
 * 不把一整段说明文字铺在页面上，需要细调的（倍速、模型、代理）都收进弹窗或折叠区。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: UiState,
    onBack: () -> Unit,
    onOpenTranslation: () -> Unit,
    onOpenAbout: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onSaveSettings: (Settings) -> Unit,
    /** 默认倍速：与播放页共用 setSpeed，改完自动记住。 */
    onSpeed: (Float) -> Unit = {},
    /** 模型提前下载：设置页里下好，生成字幕时不用等。 */
    onDownloadModel: (String) -> Unit = {},
    onDeleteModel: (String) -> Unit = {},
    onCancelModelDownload: () -> Unit = {},
    /** 0.4.0 P0 收尾：OPML 导入/导出（入口放设置页，见“订阅”组）。 */
    onImportOpml: () -> Unit = {},
    onExportOpml: () -> Unit = {},
    opmlImportSummary: OpmlImportSummary? = null,
    onDismissOpmlSummary: () -> Unit = {},
    /** 推荐节目：单独一页，点这一行进去挑。 */
    onOpenRecommended: () -> Unit = {}
) {
    val settings = state.settings
    val version = displayVersion(BuildConfig.VERSION_NAME)
    val (updateLine, fresh) = updateSummary(version, state.update)

    var speedDialog by remember { mutableStateOf(false) }
    var badgeDialog by remember { mutableStateOf(false) }
    var audioLangDialog by remember { mutableStateOf(false) }
    var modelDialog by remember { mutableStateOf(false) }

    val currentModel = AsrModel.from(settings.asrModel) ?: AsrModel.BASE
    val currentModelReady = currentModel.key in state.asrReadyModels
    val currentModelSize = state.asrModelBytes[currentModel.key] ?: 0L

    val totalUnplayed = state.unplayedCounts.values.sum()

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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            // 总状态卡：Mika 也把「现在什么状态」放最上面，比一上来就是开关更容易扫。
            StatusCard(
                shows = state.shows.size,
                unplayed = totalUnplayed,
                asrReady = currentModelReady,
                modelLabel = currentModel.key,
                translated = settings.ready
            )

            GroupLabel("订阅")
            GroupCard {
                ListRow(
                    icon = Icons.Default.FileDownload,
                    title = if (state.importRunning) "正在导入 ${state.importDone}/${state.importTotal}" else "导入 OPML",
                    sub = if (state.importRunning) "拉到一个显示一个，不用等全部跑完"
                    else "从别的播客 App 迁过来，一次批量订阅",
                    value = null,
                    first = true
                ) { if (!state.importRunning) onImportOpml() }
                ListRow(
                    icon = Icons.Default.FileUpload,
                    title = "导出 OPML",
                    sub = "把当前订阅存成 .opml 文件备份",
                    value = null
                ) { onExportOpml() }
                ListRow(
                    icon = Icons.Default.PlayArrow,
                    title = "推荐节目",
                    sub = "手工挑的 7 个节目，进去再决定订不订",
                    value = "7 个"
                ) { onOpenRecommended() }
            }

            GroupLabel("翻译与字幕")
            GroupCard {
                ListRow(
                    icon = Icons.Default.Translate,
                    title = "翻译服务",
                    sub = "OpenAI 兼容接口，Key 只存本机",
                    value = if (settings.ready) settings.model else "未配置",
                    first = true
                ) { onOpenTranslation() }
                ListRow(
                    icon = Icons.Default.Subtitles,
                    title = "AI 字幕模型",
                    sub = "没官方字幕时离线转写，可提前下载",
                    value = if (currentModelReady) "已下载" else "未下载"
                ) { modelDialog = true }
                SwitchRow(
                    title = "下载后自动生成字幕",
                    sub = "只对没有官方字幕的单集生效",
                    checked = settings.asrAuto
                ) { onSaveSettings(settings.copy(asrAuto = it)) }
            }

            GroupLabel("收听")
            GroupCard {
                ListRow(
                    icon = Icons.Default.Speed,
                    title = "默认倍速",
                    sub = "新开一集时的初始倍速，播放页还能临时改",
                    value = "${settings.playbackSpeed}×",
                    first = true
                ) { speedDialog = true }
                ListRow(
                    icon = Icons.Default.Translate,
                    title = "音轨切换",
                    sub = "多音轨的 YouTube 集在播放页切换这两种语言",
                    value = "${AudioLang.shortOf(settings.audioLangA)} ⇄ ${AudioLang.shortOf(settings.audioLangB)}"
                ) { audioLangDialog = true }
            }

            GroupLabel("下载与刷新")
            GroupCard {
                SwitchRow(
                    title = "仅 Wi-Fi 下载",
                    sub = "关掉后移动数据也能下，注意流量",
                    checked = settings.downloadWifiOnly,
                    first = true
                ) { onSaveSettings(settings.copy(downloadWifiOnly = it)) }
                SwitchRow(
                    title = "自动刷新订阅",
                    sub = "每 6 小时后台拉一次，失败不打扰",
                    checked = settings.autoRefreshEnabled
                ) { onSaveSettings(settings.copy(autoRefreshEnabled = it)) }
                SwitchRow(
                    title = "刷新也只用 Wi-Fi",
                    sub = "后台刷新不烧流量",
                    checked = settings.autoRefreshWifiOnly
                ) { onSaveSettings(settings.copy(autoRefreshWifiOnly = it)) }
            }

            GroupLabel("外观")
            GroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .padding(start = 16.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RowIcon(Icons.Default.Palette)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "主题",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "跟随系统，或手动固定",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ThemeSwitcher(settings.themeMode, onThemeMode)
                }
                ListRow(
                    icon = Icons.Default.Label,
                    title = "列表角标",
                    sub = "首页角标显示未听数、已听数或都显示",
                    value = settings.listBadge.label
                ) { badgeDialog = true }
            }

            GroupLabel("关于")
            GroupCard {
                ListRow(
                    icon = Icons.Default.Info,
                    title = "关于 MoCast",
                    sub = "版本、隐私、开源协议与反馈",
                    value = updateLine,
                    highlight = fresh,
                    first = true
                ) { onOpenAbout() }
            }

            // 网络代理先藏起来（设置入口暂不暴露），代码保留，需要时再放回来。
            Spacer(Modifier.height(18.dp))
        }
    }

    // 列表角标：未听数 / 已听数 / 都显示，选完即关。
    if (badgeDialog) {
        AlertDialog(
            onDismissRequest = { badgeDialog = false },
            title = { Text("列表角标") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListBadgeMode.entries.forEach { mode ->
                        val selected = settings.listBadge == mode
                        Surface(
                            onClick = {
                                onSaveSettings(settings.copy(listBadge = mode))
                                badgeDialog = false
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when (mode) {
                                            ListBadgeMode.UNPLAYED -> "右边只显示还有多少集没听"
                                            ListBadgeMode.PLAYED -> "右边只显示已经听了多少集"
                                            ListBadgeMode.BOTH -> "未听数在上，已听数在下"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { badgeDialog = false }) { Text("关闭") }
            }
        )
    }

    // 音轨切换：选两种语言，A 默认、B 点一下切过去（播放页只在这两种之间切）。
    if (audioLangDialog) {
        AlertDialog(
            onDismissRequest = { audioLangDialog = false },
            title = { Text("音轨切换") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "默认播放",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioLang.entries.forEach { lang ->
                            FilterChip(
                                selected = settings.audioLangA == lang.code,
                                onClick = { onSaveSettings(settings.copy(audioLangA = lang.code)) },
                                label = { Text(lang.label) }
                            )
                        }
                    }
                    Text(
                        "点一下切到",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioLang.entries.filter { it.code != settings.audioLangA }.forEach { lang ->
                            FilterChip(
                                selected = settings.audioLangB == lang.code,
                                onClick = { onSaveSettings(settings.copy(audioLangB = lang.code)) },
                                label = { Text(lang.label) }
                            )
                        }
                    }
                    Text(
                        "只有一条音轨时（普通播客、没挂配音的 YouTube 集），播放页不显示这个切换。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { audioLangDialog = false }) { Text("完成") }
            }
        )
    }

    // 默认倍速：点行弹窗，选完即关。
    if (speedDialog) {
        AlertDialog(
            onDismissRequest = { speedDialog = false },
            title = { Text("默认倍速") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { speed ->
                        FilterChip(
                            selected = settings.playbackSpeed == speed,
                            onClick = {
                                onSpeed(speed)
                                speedDialog = false
                            },
                            label = { Text("${speed}×") }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { speedDialog = false }) { Text("取消") }
            }
        )
    }

    // 模型管理：提前下载 / 删除 / 选档位，都在这里，不用等生成字幕时再下。
    if (modelDialog) {
        AlertDialog(
            onDismissRequest = { modelDialog = false },
            title = { Text("AI 字幕模型") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "下载后离线可用，生成字幕时不再等下载。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    AsrModel.entries.forEach { model ->
                        val ready = model.key in state.asrReadyModels
                        val size = state.asrModelBytes[model.key] ?: 0L
                        val downloading = state.asrDownloadingModel == model.key
                        val selected = settings.asrModel == model.key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSaveSettings(settings.copy(asrModel = model.key)) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    model.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    if (downloading) {
                                        if (state.asrModelProgress < 0f) "正在解压…"
                                        else "下载中 ${(state.asrModelProgress * 100).toInt()}%"
                                    } else modelStatusText(ready, size, model.approxSizeMb),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            when {
                                downloading -> TextButton(onClick = onCancelModelDownload) {
                                    Text("取消", style = MaterialTheme.typography.labelMedium)
                                }
                                ready -> TextButton(onClick = { onDeleteModel(model.key) }) {
                                    Text("删除", style = MaterialTheme.typography.labelMedium)
                                }
                                else -> TextButton(onClick = { onDownloadModel(model.key) }) {
                                    Text("下载", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (downloading) {
                            LinearProgressIndicator(
                                progress = { state.asrModelProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                            )
                        }
                        if (model != AsrModel.entries.last()) RowDivider()
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow(
                        title = "模型下载仅限 Wi-Fi",
                        sub = null,
                        checked = settings.asrWifiOnly
                    ) { onSaveSettings(settings.copy(asrWifiOnly = it)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { modelDialog = false }) { Text("完成") }
            }
        )
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

/** 模型状态：已下载显示实际体积，未下载显示包大小。 */
private fun modelStatusText(ready: Boolean, bytes: Long, approxMb: Int): String =
    if (ready && bytes > 0) "已下载 ${"%.0f".format(bytes / 1024.0 / 1024.0)} MB"
    else if (ready) "已下载"
    else "未下载 · 约 ${approxMb} MB"

/** 顶部总状态卡：订阅数 / 未听 / 字幕模型 / 翻译，一眼知道这台机器现在什么状态。 */
@Composable
private fun StatusCard(
    shows: Int,
    unplayed: Int,
    asrReady: Boolean,
    modelLabel: String,
    translated: Boolean
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                if (shows == 0) "还没订阅节目" else "$shows 个节目 · $unplayed 集未听",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                StatusDot(
                    text = if (asrReady) "字幕模型 $modelLabel 已就绪" else "字幕模型未下载",
                    good = asrReady
                )
                StatusDot(text = if (translated) "翻译已配置" else "翻译未配置", good = translated)
            }
        }
    }
}

@Composable
private fun StatusDot(text: String, good: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(
                    if (good) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProxySection(settings: Settings, onSaveSettings: (Settings) -> Unit, first: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    var enabled by remember(settings.proxyEnabled) { mutableStateOf(settings.proxyEnabled) }
    var type by remember(settings.proxyType) { mutableStateOf(settings.proxyType) }
    var host by remember(settings.proxyHost) { mutableStateOf(settings.proxyHost) }
    var port by remember(settings.proxyPort.toString()) { mutableStateOf(settings.proxyPort.toString()) }
    var user by remember(settings.proxyUsername) { mutableStateOf(settings.proxyUsername) }
    var pass by remember(settings.proxyPassword) { mutableStateOf(settings.proxyPassword) }

    val summary = if (!settings.proxyEnabled) "未启用"
    else "${settings.proxyType} · ${settings.proxyHost}:${settings.proxyPort}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable { expanded = !expanded }
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(Icons.Default.SettingsEthernet)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "网络代理",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "抓取、下载、翻译走代理；改一次就不用再动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            summary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (expanded) "˄" else "˅",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
    }

    if (expanded) {
        RowDivider()
        SwitchRow(
            title = "启用代理",
            sub = "抓取、下载、翻译都走它",
            checked = enabled
        ) { enabled = it }
        if (enabled) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("HTTP", "SOCKS").forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option) }
                        )
                    }
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("地址") },
                    placeholder = { Text("127.0.0.1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("账号（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("密码（可选）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "SOCKS5 填了账号密码也不生效（OkHttp 4.12 不支持）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(
            onClick = {
                onSaveSettings(
                    settings.copy(
                        proxyEnabled = enabled,
                        proxyType = type,
                        proxyHost = host.trim(),
                        proxyPort = port.toIntOrNull() ?: 8080,
                        proxyUsername = user.trim(),
                        proxyPassword = pass
                    )
                )
                expanded = false
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
        ) { Text(if (enabled) "保存代理" else "保存（关闭代理）") }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 6.dp, top = 10.dp)
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
        modifier = Modifier.padding(start = 62.dp)
    )
}

/** Mika 式的一行：图标 + 标题 + 一句说明，右侧值（可选）+ ›。 */
@Composable
private fun ListRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    value: String?,
    first: Boolean = false,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    if (!first) RowDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 图标保持原来的彩色圆角底块，只换布局不动图标。 */
@Composable
private fun RowIcon(icon: ImageVector) {
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
    first: Boolean = false,
    onCheckedChange: (Boolean) -> Unit
) {
    if (!first) RowDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (sub != null) {
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

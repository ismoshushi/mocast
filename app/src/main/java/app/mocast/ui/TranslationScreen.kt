package app.mocast.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mocast.data.KeywordLevel
import app.mocast.data.Settings
import app.mocast.data.TranslationMode

private const val SEGMENT_HINT = 45

/**
 * 翻译服务（0.4.1 重排）。
 *
 * 之前是一长条输入框 + 大段说明，像说明书；现在按“填什么 → 测一下 → 怎么翻”分段：
 * 服务商快捷芯片 / 连接信息 / 翻译方式 / 费用（折叠）。每段一张卡片，说明只留一行。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TranslationScreen(
    settings: Settings,
    onBack: () -> Unit,
    onSave: (Settings) -> Unit,
    onMode: (TranslationMode) -> Unit,
    onLevel: (KeywordLevel) -> Unit,
    onAutoExtend: (Boolean) -> Unit,
    /** 0.4.0 P0 收尾：连接测试（测正在编辑、还没保存的值）。 */
    onTestConnection: (Settings) -> Unit = {},
    testRunning: Boolean = false,
    testResult: String? = null,
    testOk: Boolean? = null,
    onClearTestResult: () -> Unit = {}
) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var lang by remember { mutableStateOf(settings.targetLang) }
    var customPrice by remember {
        mutableStateOf(settings.customPricePer1kTokens.takeIf { it > 0f }?.toString() ?: "")
    }
    var priceDialog by remember { mutableStateOf(false) }
    var langDialog by remember { mutableStateOf(false) }

    /** 当前正在编辑的完整设置（保存与测试都用它）。 */
    fun editedSettings() = settings.copy(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        targetLang = lang.trim().ifBlank { "简体中文" },
        customPricePer1kTokens = customPrice.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("翻译服务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(editedSettings()) }) { Text("保存") }
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

            SectionTitle("服务商")
            Group {
                Column(modifier = Modifier.padding(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            baseUrl = "https://api.openai.com/v1"
                            model = "gpt-4o-mini"
                        }) { Text("OpenAI") }
                        TextButton(onClick = {
                            baseUrl = "https://api.deepseek.com/v1"
                            model = "deepseek-chat"
                        }) { Text("DeepSeek") }
                        TextButton(onClick = {
                            baseUrl = "https://api.siliconflow.cn/v1"
                            model = "Qwen/Qwen2.5-7B-Instruct"
                        }) { Text("硅基流动") }
                        TextButton(onClick = {
                            baseUrl = "https://open.bigmodel.cn/api/paas/v4"
                            model = "glm-4.7-flash"
                        }) { Text("智谱 GLM") }
                        TextButton(onClick = {
                            baseUrl = "http://192.168.1.10:11434/v1"
                            model = "qwen2.5"
                        }) { Text("Ollama") }
                    }
                    Text(
                        "点一下只填地址和模型名，Key 要自己填。Ollama 记得改成电脑的局域网 IP。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionTitle("连接信息")
            Group {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            enabled = !testRunning,
                            onClick = { onTestConnection(editedSettings()) }
                        ) { Text(if (testRunning) "测试中…" else "测试连接") }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            testResult ?: "Key 只存在本机，请求直连你填的地址",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (testOk) {
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f)
                        )
                        if (testResult != null) {
                            TextButton(onClick = onClearTestResult) { Text("清除") }
                        }
                    }
                }
            }

            SectionTitle("翻译方式")
            Group {
                Column(modifier = Modifier.padding(14.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TranslationMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.translationMode == mode,
                                onClick = { onMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (settings.translationMode == TranslationMode.FULL)
                            "逐句翻成目标语言，跟读用。"
                        else "只标超出你词汇层级的生词 + 中文释义，整句没生词就不显示。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (settings.translationMode == TranslationMode.KEYWORD) {
                        Spacer(Modifier.height(12.dp))
                        Text("我的词汇层级", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KeywordLevel.entries.forEach { level ->
                                FilterChip(
                                    selected = settings.keywordLevel == level,
                                    onClick = { onLevel(level) },
                                    label = { Text("${level.label} ${level.vocab}") }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            settings.keywordLevel.hint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                RowDividerT()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("播放时自动往后翻", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "每段 $SEGMENT_HINT 条，播到段尾接着翻下一段",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = settings.autoExtend, onCheckedChange = onAutoExtend)
                }
            }

            SectionTitle("输出")
            Group {
                SettingLine(
                    title = "目标语言",
                    value = lang.ifBlank { "简体中文" }
                ) { langDialog = true }
                RowDividerT()
                SettingLine(
                    title = "单价（可选）",
                    value = if (customPrice.isBlank()) "用内置价格表" else "\$$customPrice / 1k token"
                ) { priceDialog = true }
            }

            Text(
                "翻译按每 12 条一批发送，只翻一次并存本地；同一集重进不会重复花钱。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 28.dp)
            )
        }
    }

    if (priceDialog) {
        AlertDialog(
            onDismissRequest = { priceDialog = false },
            title = { Text("费用预估") },
            text = {
                Column {
                    OutlinedTextField(
                        value = customPrice,
                        onValueChange = {
                            customPrice = it.filter { c -> c.isDigit() || c == '.' }.take(8)
                        },
                        label = { Text("每 1k token 单价（美元）") },
                        placeholder = { Text("例如 0.01，留空用内置价格表") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "内置价格：gpt-4o-mini、deepseek-chat、glm-4.7-flash、Qwen2.5-7B 已登记；" +
                            "不在表里的模型只显示 token 数，填了单价才能算钱。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { priceDialog = false }) { Text("完成") }
            }
        )
    }

    // 目标语言：点行弹窗改，避免在长表单里再占一个输入框。
    if (langDialog) {
        AlertDialog(
            onDismissRequest = { langDialog = false },
            title = { Text("目标语言") },
            text = {
                Column {
                    OutlinedTextField(
                        value = lang,
                        onValueChange = { lang = it },
                        label = { Text("翻译成什么语言") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "一般填“简体中文”；想练英文也可以填 English。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { langDialog = false }) { Text("完成") }
            }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
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
private fun RowDividerT() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f),
        modifier = Modifier.padding(start = 14.dp)
    )
}

@Composable
private fun SettingLine(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

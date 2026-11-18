package app.mocast.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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

@OptIn(ExperimentalMaterial3Api::class)
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
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "用任意 OpenAI 兼容接口翻译字幕。Key 只存在本机，请求直连你填的地址。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = lang,
                onValueChange = { lang = it },
                label = { Text("目标语言") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))
            Text("连接测试", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Button(
                    enabled = !testRunning,
                    onClick = { onTestConnection(editedSettings()) }
                ) { Text(if (testRunning) "测试中…" else "测试连接") }
                if (testResult != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onClearTestResult) { Text("清除") }
                }
            }
            if (testResult != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    testResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (testOk == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "只发一句 Hello 做试探，不翻整集、不写缓存；先点测试再保存也行，测的是上面正在填的值。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Text("费用预估", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = customPrice,
                onValueChange = { customPrice = it.filter { c -> c.isDigit() || c == '.' }.take(8) },
                label = { Text("每 1k token 单价（美元，可选）") },
                placeholder = { Text("例如 0.01，留空用内置单价表") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "内置单价表（2026-11-18 快照，价格可能已变）：gpt-4o-mini \$0.15/\$0.60、deepseek-chat \$0.28/\$0.42、glm-4.7-flash 在 Z.ai 官方免费、Qwen2.5-7B-Instruct（硅基流动）\$0.05/\$0.05，均为每 1M token 输入/输出价；不在表里的模型只显示 token 数，填了上面这个单价就能算。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Text("翻译模式", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.translationMode == mode,
                        onClick = { onMode(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (settings.translationMode == TranslationMode.FULL)
                    "逐句翻成目标语言，跟读用。"
                else "只标注超出你词汇层级的生词 + 中文释义，整句没有生词就不显示。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (settings.translationMode == TranslationMode.KEYWORD) {
                Spacer(Modifier.height(12.dp))
                Text("我的词汇层级", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                KeywordLevel.entries.chunked(3).forEach { rowLevels ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowLevels.forEach { level ->
                            FilterChip(
                                selected = settings.keywordLevel == level,
                                onClick = { onLevel(level) },
                                label = { Text("${level.label} ${level.vocab}") }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    settings.keywordLevel.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("播放时自动往后翻", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "每段 ${SEGMENT_HINT} 条，播到段尾自动续下一段；关掉就只翻当前这一段。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = settings.autoExtend, onCheckedChange = onAutoExtend)
            }

            Spacer(Modifier.height(16.dp))
            Text("快捷填充", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    baseUrl = "https://open.bigmodel.cn/api/paas/v4"
                    model = "glm-4.7-flash"
                }) { Text("智谱 GLM") }
                TextButton(onClick = {
                    baseUrl = "http://192.168.1.10:11434/v1"
                    model = "qwen2.5"
                }) { Text("Ollama（改成本机 IP）") }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "翻译按每 12 条一批发送，只翻一次并存本地；同一集重进不会重复花钱。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

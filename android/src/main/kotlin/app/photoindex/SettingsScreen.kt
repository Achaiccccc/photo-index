package app.photoindex

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.photoindex.core.DetailLevel
import app.photoindex.core.IndexSettings
import app.photoindex.core.LONG_EDGE_CHOICES
import app.photoindex.core.ProviderCatalog
import app.photoindex.core.UPLOAD_MODE_BATCH_FILE
import app.photoindex.core.UPLOAD_MODE_REALTIME
import app.photoindex.core.defaultIndexSettings
import app.photoindex.platform.KeystoreApiKeyStore
import app.photoindex.storage.IndexSettingsStore
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设计文档第 5.1 节的设置。API Key 只进加密存储。
 * 改完立即写入索引库里的设置行，不启动上传。
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appIndex = remember { AppIndex(context) }
    val keyStore = remember { KeystoreApiKeyStore(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val alive = remember { AtomicBoolean(true) }
    val form = remember { SettingsForm() }
    var settings by remember { mutableStateOf(defaultIndexSettings()) }
    var ready by remember { mutableStateOf(false) }
    var keyStored by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            val typed = form.key.trim()
            if (typed.isNotEmpty()) {
                appIndex.access { keyStore.save(typed) }
            }
            appIndex.close()
        }
    }

    LaunchedEffect(form.key) {
        val typed = form.key.trim()
        if (typed.isEmpty()) return@LaunchedEffect
        delay(300)
        appIndex.access {
            keyStore.save(typed)
            mainHandler.post {
                if (!alive.get()) return@post
                keyStored = true
                message = "密钥已写入加密存储，没有放进索引库。"
            }
        }
    }

    LaunchedEffect(Unit) {
        appIndex.access { database ->
            val loaded = IndexSettingsStore(database).load()
            val stored = !keyStore.read().isNullOrBlank()
            mainHandler.post {
                if (!alive.get()) return@post
                settings = loaded
                form.fillFrom(loaded)
                keyStored = stored
                ready = true
            }
        }
    }

    fun persist(next: IndexSettings) {
        settings = next
        appIndex.access { database ->
            try {
                IndexSettingsStore(database).save(next)
                mainHandler.post {
                    if (!alive.get()) return@post
                    message = null
                }
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "设置没有保存"
                mainHandler.post {
                    if (!alive.get()) return@post
                    message = detail
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "设置", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text(text = "返回") }
        }
        if (!ready) {
            Text(text = "正在读取设置…")
            return@Column
        }
        message?.let { Text(text = it) }
        Text(text = "服务商", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProviderCatalog.options.forEach { option ->
                FilterChip(
                    selected = settings.provider == option.id,
                    onClick = {
                        val next = settings.applyingProvider(option.id)
                        form.endpoint = next.endpoint
                        form.model = next.model
                        form.inputPrice = next.inputPricePerMillion.toPlainString()
                        form.outputPrice = next.outputPricePerMillion.toPlainString()
                        persist(next)
                    },
                    label = { Text(text = option.label) },
                )
            }
        }
        ProviderCatalog.require(settings.provider).note?.let { Text(text = it) }
        val presets = ProviderCatalog.presetsFor(settings.provider)
        if (presets.isNotEmpty()) {
            Text(text = "预置模型")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { preset ->
                    FilterChip(
                        selected = settings.model == preset.model && preset.model.isNotEmpty(),
                        onClick = {
                            val next = settings.applyingPreset(preset)
                            form.model = next.model
                            form.inputPrice = next.inputPricePerMillion.toPlainString()
                            form.outputPrice = next.outputPricePerMillion.toPlainString()
                            persist(next)
                        },
                        label = { Text(text = preset.label) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = form.endpoint,
            onValueChange = { text ->
                form.endpoint = text
                if (text.isNotBlank()) persist(settings.copy(endpoint = text.trim()))
                else message = "接口地址不能为空"
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "接口地址") },
            singleLine = true,
        )
        OutlinedTextField(
            value = form.model,
            onValueChange = { text ->
                form.model = text
                persist(settings.copy(model = text.trim()))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = if (settings.provider == "doubao") "模型名（接入点 ID）" else "模型名") },
            singleLine = true,
        )
        Text(text = "API Key", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (keyStored && form.key.isBlank()) {
                "密钥已保存在加密存储中。留空不会清除。索引库里没有这段密钥。"
            } else {
                "只写入手机的加密存储，不写入设置表。"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = form.key,
            onValueChange = { form.key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(onClick = {
            form.key = ""
            keyStored = false
            appIndex.access {
                keyStore.clear()
                mainHandler.post {
                    if (!alive.get()) return@post
                    message = "密钥已从加密存储清除。"
                }
            }
        }) { Text(text = "清除密钥") }
        SwitchRow(
            title = "深度思考",
            detail = "默认关闭。打开后按思考上限计入输出 token。",
            checked = settings.thinkingEnabled,
            onChecked = { enabled -> persist(settings.copy(thinkingEnabled = enabled)) },
        )
        if (settings.thinkingEnabled) {
            if (settings.provider == "custom") {
                Text(text = "自定义服务商的思考字段还不能确认。这里只记下开关，这一版不会发起请求。")
            }
            NumberField(
                value = form.thinkingLimit,
                label = "思考 token 上限",
                onValueChange = { text ->
                    form.thinkingLimit = text
                    val limit = text.trim().toIntOrNull()
                    if (limit != null && limit > 0) persist(settings.copy(thinkingTokenLimit = limit))
                    else message = "思考上限必须是正整数"
                },
            )
        }
        Text(text = "压缩长边", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LONG_EDGE_CHOICES.sorted().forEach { edge ->
                FilterChip(
                    selected = settings.longEdge == edge,
                    onClick = { persist(settings.copy(longEdge = edge)) },
                    label = { Text(text = edge.toString()) },
                )
            }
            FilterChip(
                selected = settings.longEdge == null,
                onClick = { persist(settings.copy(longEdge = null)) },
                label = { Text(text = "不压缩") },
            )
        }
        NumberField(
            value = form.quality,
            label = "JPEG 质量（60–90）",
            onValueChange = { text ->
                form.quality = text
                val quality = text.trim().toIntOrNull()
                if (quality != null && quality in 60..90) persist(settings.copy(jpegQuality = quality))
                else message = "JPEG 质量要在 60 到 90 之间"
            },
        )
        Text(text = "识别详细度")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.detailLevel == DetailLevel.DETAILED,
                onClick = { persist(settings.copy(detailLevel = DetailLevel.DETAILED)) },
                label = { Text(text = "详细") },
            )
            FilterChip(
                selected = settings.detailLevel == DetailLevel.BRIEF,
                onClick = { persist(settings.copy(detailLevel = DetailLevel.BRIEF)) },
                label = { Text(text = "简要") },
            )
        }
        Text(text = "上传方式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.uploadMode == UPLOAD_MODE_BATCH_FILE,
                onClick = { persist(settings.copy(uploadMode = UPLOAD_MODE_BATCH_FILE)) },
                label = { Text(text = "批量文件") },
            )
            FilterChip(
                selected = settings.uploadMode == UPLOAD_MODE_REALTIME,
                onClick = { persist(settings.copy(uploadMode = UPLOAD_MODE_REALTIME)) },
                label = { Text(text = "实时分批") },
            )
        }
        NumberField(
            value = form.batchMegabytes,
            label = "每批最大体积（MB）",
            onValueChange = { text ->
                form.batchMegabytes = text
                val megabytes = text.trim().toLongOrNull()
                if (megabytes != null && megabytes in 1L..500L) {
                    persist(settings.copy(batchMaxBytes = megabytes * 1024L * 1024L))
                } else {
                    message = "每批体积按 MB 填写，范围 1 到 500"
                }
            },
        )
        NumberField(
            value = form.batchLines,
            label = "每批最大行数（1–10000）",
            onValueChange = { text ->
                form.batchLines = text
                val lines = text.trim().toIntOrNull()
                if (lines != null && lines in 1..10_000) persist(settings.copy(batchMaxLines = lines))
                else message = "每批行数要在 1 到 10000 之间"
            },
        )
        Text(text = "同时进行的批次数")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2).forEach { count ->
                FilterChip(
                    selected = settings.concurrentBatches == count,
                    onClick = { persist(settings.copy(concurrentBatches = count)) },
                    label = { Text(text = count.toString()) },
                )
            }
        }
        SwitchRow(
            title = "仅 Wi-Fi",
            detail = "打包可以离线进行。上传要等这个条件满足，这一页不会上传。",
            checked = settings.wifiOnly,
            onChecked = { persist(settings.copy(wifiOnly = it)) },
        )
        SwitchRow(
            title = "仅在充电时运行",
            detail = "关闭后长任务仍可能因发热变慢。",
            checked = settings.chargingOnly,
            onChecked = { persist(settings.copy(chargingOnly = it)) },
        )
        NumberField(
            value = form.inputPrice,
            label = "输入单价（元 / 百万 token）",
            keyboardType = KeyboardType.Decimal,
            onValueChange = { text ->
                form.inputPrice = text
                val price = text.trim().toDoubleOrNull()
                if (price != null && price >= 0.0) persist(settings.copy(inputPricePerMillion = price))
                else message = "输入单价要写成非负数字"
            },
        )
        NumberField(
            value = form.outputPrice,
            label = "输出单价（元 / 百万 token）",
            keyboardType = KeyboardType.Decimal,
            onValueChange = { text ->
                form.outputPrice = text
                val price = text.trim().toDoubleOrNull()
                if (price != null && price >= 0.0) persist(settings.copy(outputPricePerMillion = price))
                else message = "输出单价要写成非负数字"
            },
        )
        Text(text = "匹配方式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.matchMode == "any",
                onClick = { persist(settings.copy(matchMode = "any")) },
                label = { Text(text = "命中任意片段") },
            )
            FilterChip(
                selected = settings.matchMode == "all",
                onClick = { persist(settings.copy(matchMode = "all")) },
                label = { Text(text = "必须同时包含") },
            )
        }
        NumberField(
            value = form.alert,
            label = "金额提醒线（元，默认 5）",
            keyboardType = KeyboardType.Decimal,
            onValueChange = { text ->
                form.alert = text
                val alert = text.trim().toDoubleOrNull()
                if (alert != null && alert >= 0.0) persist(settings.copy(amountAlertYuan = alert))
                else message = "金额提醒线要写成非负数字"
            },
        )
    }
}

private class SettingsForm {
    var endpoint by mutableStateOf("")
    var model by mutableStateOf("")
    var thinkingLimit by mutableStateOf("1024")
    var quality by mutableStateOf("80")
    var batchMegabytes by mutableStateOf("400")
    var batchLines by mutableStateOf("2000")
    var inputPrice by mutableStateOf("0.15")
    var outputPrice by mutableStateOf("1.5")
    var alert by mutableStateOf("5")
    var key by mutableStateOf("")

    fun fillFrom(settings: IndexSettings) {
        endpoint = settings.endpoint
        model = settings.model
        thinkingLimit = settings.thinkingTokenLimit.toString()
        quality = settings.jpegQuality.toString()
        batchMegabytes = (settings.batchMaxBytes / (1024L * 1024L)).toString()
        batchLines = settings.batchMaxLines.toString()
        inputPrice = settings.inputPricePerMillion.toPlainString()
        outputPrice = settings.outputPricePerMillion.toPlainString()
        alert = settings.amountAlertYuan.toPlainString()
    }
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(text = label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    detail: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            Text(text = detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun Double.toPlainString(): String {
    val raw = toString()
    if (!raw.contains('E') && !raw.contains('e')) return raw
    return java.math.BigDecimal(raw).stripTrailingZeros().toPlainString()
}

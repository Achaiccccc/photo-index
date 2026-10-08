package app.photoindex

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.photoindex.core.IndexSettings
import app.photoindex.core.JobState
import app.photoindex.core.QuotePreview
import app.photoindex.core.formatYuan
import app.photoindex.core.formatYuanRange
import app.photoindex.platform.UploadGate
import app.photoindex.storage.IndexSettingsStore
import app.photoindex.storage.PhotoIndexDatabase
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 展示待处理张数和估价。确认后启动前台批量任务，并留在本页看进度。
 * 本节点的任务走假服务商，不连接百炼。
 */
@Composable
fun QuoteScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val appIndex = remember { AppIndex(context) }
    val gate = remember { UploadGate(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val alive = remember { AtomicBoolean(true) }
    val ticket = remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<QuoteSnapshot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var onWifi by remember { mutableStateOf(gate.onWifi) }
    var charging by remember { mutableStateOf(gate.charging) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { IndexingService.start(context) }

    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            appIndex.close()
        }
    }

    fun reload() {
        val mine = ticket.intValue + 1
        ticket.intValue = mine
        appIndex.access { database ->
            try {
                val loaded = readQuote(context, database)
                mainHandler.post {
                    if (!alive.get() || mine != ticket.intValue) return@post
                    snapshot = loaded
                    loading = false
                }
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "估价失败"
                mainHandler.post {
                    if (!alive.get() || mine != ticket.intValue) return@post
                    loading = false
                    message = detail
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (alive.get()) {
            reload()
            delay(1000)
        }
    }

    val current = snapshot
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
            Text(text = "估价确认", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text(text = "返回") }
        }
        Button(onClick = onOpenSettings) { Text(text = "去设置") }
        if (loading || current == null) {
            Text(text = if (message == null) "正在计算估价…" else message!!)
            return@Column
        }
        message?.let { Text(text = it) }
        val settings = current.settings
        val preview = current.preview
        Text(text = "待处理 ${preview.pendingCount} 张", style = MaterialTheme.typography.titleMedium)
        Text(text = "预计 ${preview.estimatedBatchCount} 批（按每批最多 ${preview.batchMaxLines} 行；体积达到上限会更早封口）")
        Text(text = "模型 ${settings.model.ifBlank { "（未填）" }}，长边 ${settings.longEdge?.toString() ?: "不压缩"}，${thinkingLabel(settings)}")
        Text(text = "当前计价：${preview.priceLabel}", style = MaterialTheme.typography.titleMedium)
        Text(text = "单张 ${formatYuanRange(preview.activePerImage)}")
        Text(text = "合计 ${formatYuanRange(preview.activeTotal)}", style = MaterialTheme.typography.titleMedium)
        if (preview.showBatchAndRealtime) {
            Text(text = "批量合计 ${formatYuanRange(preview.totalBatch)}")
            Text(text = "实时合计 ${formatYuanRange(preview.totalRealtime)}")
        }
        Text(text = sizeNote(settings, preview))
        Text(text = calibrationNote(preview))
        preview.providerNote?.let { Text(text = it) }
        Text(text = "上传中的批 ${current.uploadingBatches} 个")
        Text(text = "任务${jobLabel(current.progress.jobState)}")
        Text(text = "假服务商，不连接百炼。拿到任务号后会先保持运行中约 20 秒。")
        Text(text = current.progress.systemStatus)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "当作已连接 Wi-Fi", modifier = Modifier.weight(1f))
            Switch(
                checked = onWifi,
                onCheckedChange = { checked ->
                    onWifi = checked
                    gate.onWifi = checked
                    reload()
                },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "当作正在充电", modifier = Modifier.weight(1f))
            Switch(
                checked = charging,
                onCheckedChange = { checked ->
                    charging = checked
                    gate.charging = checked
                    reload()
                },
            )
        }
        Text(text = "条件不满足时可以打包，但不会上传。")
        Text(text = current.progress.notificationText, style = MaterialTheme.typography.titleMedium)
        Text(text = "数据库里已入库 ${current.progress.doneCount} 张，上传次数 ${current.progress.uploadCount}")
        current.progress.batches.forEach { batch ->
            Text(text = batchLine(batch))
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (current.progress.jobState) {
                JobState.RUNNING -> {
                    Button(onClick = { IndexingService.pause(context) }) { Text(text = "暂停") }
                    Button(onClick = { IndexingService.cancel(context) }) { Text(text = "停止并取消") }
                }
                JobState.PAUSED -> {
                    Button(onClick = { IndexingService.resume(context) }) { Text(text = "继续") }
                    Button(onClick = { IndexingService.cancel(context) }) { Text(text = "停止并取消") }
                }
                else -> if (!current.confirmed || current.preview.pendingCount > 0) {
                    Button(onClick = {
                        appIndex.access { database ->
                            try {
                                val store = IndexSettingsStore(database)
                                val blocked = indexStartBlockReason(store.load())
                                if (!store.confirmed()) store.confirm()
                                val loaded = readQuote(context, database)
                                mainHandler.post {
                                    if (!alive.get()) return@post
                                    snapshot = loaded
                                    message = blocked
                                    if (blocked == null && loaded.preview.pendingCount > 0) {
                                        startIndexing(context, notificationPermission)
                                    }
                                }
                            } catch (error: Exception) {
                                val detail = error.message?.takeIf { it.isNotBlank() } ?: "确认失败"
                                mainHandler.post {
                                    if (!alive.get()) return@post
                                    message = detail
                                }
                            }
                        }
                    }) {
                        Text(text = if (current.confirmed) "开始批量上传" else "确认估价")
                    }
                }
            }
            if (preview.repeatsAmountBesideButton) {
                Text(text = "合计 ${formatYuan(preview.activeTotal.point)} 元")
            }
        }
        Text(text = confirmationLine(current))
    }
}

private fun startIndexing(
    context: Context,
    requestPermission: androidx.activity.result.ActivityResultLauncher<String>,
) {
    val needsPermission = Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    if (needsPermission) {
        requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        IndexingService.start(context)
    }
}

private data class QuoteSnapshot(
    val settings: IndexSettings,
    val preview: QuotePreview,
    val confirmed: Boolean,
    val uploadingBatches: Int,
    val progress: IndexProgress,
)

private fun readQuote(context: Context, database: PhotoIndexDatabase): QuoteSnapshot {
    val store = IndexSettingsStore(database)
    return QuoteSnapshot(
        settings = store.load(),
        preview = store.preview(),
        confirmed = store.confirmed(),
        uploadingBatches = database.batchQueries.selectBatchesByState("uploading").executeAsList().size,
        progress = readIndexProgress(context, database, uploadAllowedNow(context, database)),
    )
}

private fun confirmationLine(snapshot: QuoteSnapshot): String = when {
    snapshot.progress.jobState == JobState.RUNNING -> "已确认，正在建库。中途不用逐张点击。"
    snapshot.progress.jobState == JobState.PAUSED -> "已暂停。已经提交的批还会入库，新的批不再封口。"
    !snapshot.confirmed -> "尚未确认，不会上传。"
    snapshot.preview.pendingCount == 0 && snapshot.progress.batches.isEmpty() -> "已确认。没有待处理的图。"
    snapshot.preview.pendingCount == 0 -> "已确认。"
    else -> "已确认。点开始批量上传后才会跑。"
}

private fun batchLine(batch: IndexBatchStatus): String {
    val task = batch.remoteBatchId ?: "还没有任务号"
    return "${batch.state} · ${batch.lineCount} 张 · 任务 $task"
}

private fun thinkingLabel(settings: IndexSettings): String =
    if (settings.thinkingEnabled) "深度思考开，上限 ${settings.thinkingTokenLimit}" else "深度思考关"

private fun sizeNote(settings: IndexSettings, preview: QuotePreview): String =
    if (preview.assumesUncompressedLongEdge) {
        "不压缩时还没有原图宽高，暂按长边 ${preview.referenceSize.widthPx} 的 4:3 估算。"
    } else {
        "还没有压缩后的真实尺寸，按长边 ${settings.longEdge} 的 4:3（${preview.referenceSize.widthPx}×${preview.referenceSize.heightPx}）估算。"
    }

private fun calibrationNote(preview: QuotePreview): String =
    if (preview.calibration.applied) {
        "校准系数：输入 ${preview.calibration.inputFactor}，输出 ${preview.calibration.outputFactor}。"
    } else {
        "校准：已有 ${preview.calibration.sampleCount} 张实际用量，不满 20 张，未修正。"
    }

private fun jobLabel(state: String): String = when (state) {
    JobState.RUNNING -> "进行中"
    JobState.PAUSED -> "已暂停"
    else -> "空闲"
}

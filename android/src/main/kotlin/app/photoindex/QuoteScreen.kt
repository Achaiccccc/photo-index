package app.photoindex

import android.os.Handler
import android.os.Looper
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
import app.photoindex.storage.IndexSettingsStore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 展示待处理张数和估价。确认只写入标记并留在本页，不启动任务，也不上传。
 */
@Composable
fun QuoteScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val appIndex = remember { AppIndex(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val alive = remember { AtomicBoolean(true) }
    val ticket = remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf<QuoteSnapshot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

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
                val loaded = readQuote(database)
                mainHandler.post {
                    if (!alive.get() || mine != ticket.intValue) return@post
                    snapshot = loaded
                    loading = false
                    message = null
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
        reload()
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
        Text(text = "任务${jobLabel(current.jobState)}")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = {
                appIndex.access { database ->
                    try {
                        IndexSettingsStore(database).confirm()
                        val loaded = readQuote(database)
                        mainHandler.post {
                            if (!alive.get()) return@post
                            snapshot = loaded
                            message = "已确认。这一步只记下确认，还没有上传。"
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
                Text(text = if (current.confirmed) "已确认" else "确认估价")
            }
            if (preview.repeatsAmountBesideButton) {
                Text(text = "合计 ${formatYuan(preview.activeTotal.point)} 元")
            }
        }
        Text(
            text = if (current.confirmed) {
                "已确认。这一步只记下确认，还没有上传。"
            } else {
                "尚未确认，不会上传。"
            },
        )
    }
}

private data class QuoteSnapshot(
    val settings: IndexSettings,
    val preview: QuotePreview,
    val confirmed: Boolean,
    val uploadingBatches: Int,
    val jobState: String,
)

private fun readQuote(database: app.photoindex.storage.PhotoIndexDatabase): QuoteSnapshot {
    val store = IndexSettingsStore(database)
    return QuoteSnapshot(
        settings = store.load(),
        preview = store.preview(),
        confirmed = store.confirmed(),
        uploadingBatches = database.batchQueries.selectBatchesByState("uploading").executeAsList().size,
        jobState = database.jobQueries.selectJob().executeAsOne().state,
    )
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

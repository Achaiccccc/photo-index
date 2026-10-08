package app.photoindex

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.photoindex.core.ModelRecord
import app.photoindex.platform.loadDisplayBitmap
import app.photoindex.storage.RecognitionDetail
import app.photoindex.storage.loadRecognitionDetail
import app.photoindex.storage.saveRecognitionEdits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 原图和模型字段。追加词、删掉模型写出的片段后，只更新搜索文本。
 * 不新建批记录，也不调用接口。
 */
@Composable
fun RecognitionDetailScreen(
    assetId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appIndex = remember { AppIndex(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val alive = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            appIndex.close()
        }
    }

    var detail by remember { mutableStateOf<RecognitionDetail?>(null) }
    var added by remember { mutableStateOf<List<String>>(emptyList()) }
    var suppressed by remember { mutableStateOf<List<String>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageNote by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            val shown = bitmap
            bitmap = null
            if (shown != null && !shown.isRecycled) shown.recycle()
        }
    }

    LaunchedEffect(assetId) {
        appIndex.access { database ->
            try {
                val loaded = database.loadRecognitionDetail(assetId)
                mainHandler.post {
                    if (!alive.get()) return@post
                    detail = loaded
                    added = loaded.addedTerms
                    suppressed = loaded.suppressedTerms
                    ready = true
                    message = null
                }
            } catch (error: Exception) {
                val detailText = error.message?.takeIf { it.isNotBlank() } ?: "读取识别结果失败"
                mainHandler.post {
                    if (!alive.get()) return@post
                    ready = true
                    message = detailText
                }
            }
        }
    }

    val place = detail
    LaunchedEffect(place?.assetId, place?.systemId) {
        val current = place ?: return@LaunchedEffect
        imageNote = null
        val loaded = try {
            withContext(Dispatchers.IO) {
                loadDisplayBitmap(context, current.kind, current.systemKey, current.systemId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (!alive.get()) {
            if (loaded != null && !loaded.isRecycled) loaded.recycle()
            return@LaunchedEffect
        }
        val previous = bitmap
        bitmap = loaded
        if (previous != null && previous !== loaded && !previous.isRecycled) previous.recycle()
        if (loaded == null) imageNote = "这张原图现在打不开。"
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
            Text(text = "识别结果", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onBack) { Text(text = "返回") }
        }
        if (!ready) {
            Text(text = "正在读取…")
            return@Column
        }
        val shown = detail
        if (shown == null) {
            message?.let { Text(text = it) }
            return@Column
        }
        val currentBitmap = bitmap
        if (currentBitmap != null && !currentBitmap.isRecycled) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = shown.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentScale = ContentScale.Fit,
            )
        }
        imageNote?.let { Text(text = it) }
        Text(text = shown.displayName, style = MaterialTheme.typography.titleMedium)
        message?.let { Text(text = it) }
        Text(text = "模型字段", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "删掉的片段不再参与搜索。模型原文仍保留，重跑时也不会再写进搜索文本。",
            style = MaterialTheme.typography.bodySmall,
        )
        modelFragments(shown.record).forEach { fragment ->
            if (fragment.text.isBlank()) {
                Text(text = "${fragment.label}：（空）")
            } else if (fragment.text in suppressed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "${fragment.label}：已屏蔽", modifier = Modifier.weight(1f))
                    Button(onClick = {
                        suppressed = suppressed.filterNot { it == fragment.text }
                    }) { Text(text = "恢复") }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${fragment.label}：${fragment.text}",
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        suppressed = (suppressed + fragment.text).distinct()
                    }) { Text(text = "删除") }
                }
            }
        }
        if (added.isNotEmpty()) {
            Text(text = "追加的词", style = MaterialTheme.typography.titleMedium)
            added.forEach { term ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = term, modifier = Modifier.weight(1f))
                    Button(onClick = { added = added.filterNot { it == term } }) {
                        Text(text = "移除")
                    }
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "追加一个词") },
            singleLine = true,
        )
        Button(onClick = {
            val term = draft.trim()
            if (term.isEmpty()) {
                message = "先写一个词。"
            } else {
                added = (added + term).distinct()
                draft = ""
                message = null
            }
        }) { Text(text = "加入") }
        Button(
            onClick = {
                saving = true
                val addedNow = added
                val suppressedNow = suppressed
                appIndex.access { database ->
                    try {
                        database.saveRecognitionEdits(assetId, addedNow, suppressedNow)
                        mainHandler.post {
                            if (!alive.get()) return@post
                            saving = false
                            message = "已更新搜索文本，没有新建批记录。"
                        }
                    } catch (error: Exception) {
                        val detailText = error.message?.takeIf { it.isNotBlank() } ?: "没有保存"
                        mainHandler.post {
                            if (!alive.get()) return@post
                            saving = false
                            message = detailText
                        }
                    }
                }
            },
            enabled = !saving,
        ) { Text(text = if (saving) "正在保存…" else "保存") }
    }
}

private data class ModelFragment(val label: String, val text: String)

private fun modelFragments(record: ModelRecord): List<ModelFragment> = buildList {
    add(ModelFragment("摘要", record.summary))
    if (record.objects.isEmpty()) add(ModelFragment("主体", ""))
    record.objects.forEach { add(ModelFragment("主体", it)) }
    if (record.scene.isEmpty()) add(ModelFragment("场景", ""))
    record.scene.forEach { add(ModelFragment("场景", it)) }
    add(ModelFragment("平台", record.platform))
    add(ModelFragment("作者", record.author))
    add(ModelFragment("时间", record.publishedAt))
    add(ModelFragment("图中文字", record.ocrText))
    if (record.tags.isEmpty()) add(ModelFragment("标签", ""))
    record.tags.forEach { add(ModelFragment("标签", it)) }
}

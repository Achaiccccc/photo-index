package app.photoindex

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import app.photoindex.storage.SEARCH_PAGE_SIZE
import app.photoindex.storage.SearchHit
import app.photoindex.storage.SearchLibraryState
import app.photoindex.storage.searchLibraryState
import app.photoindex.storage.searchPhotos
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 设计文档第 10 节：输入停约 300 毫秒再查，新输入作废上一次结果。 */
const val SEARCH_INPUT_DEBOUNCE_MS = 300L

/**
 * 本机搜索。只读已经建库的图，不上传，也不展示示例数据。
 */
@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val context = LocalContext.current
    val appIndex = remember { AppIndex(context) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val alive = remember { AtomicBoolean(true) }
    val ticket = remember { AtomicInteger(0) }
    DisposableEffect(Unit) {
        onDispose {
            alive.set(false)
            appIndex.close()
        }
    }

    var library by remember { mutableStateOf(SearchLibraryState.EMPTY_SCOPE) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var shownOffset by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val mine = ticket.incrementAndGet()
        hits = emptyList()
        hasMore = false
        shownOffset = 0
        searching = true
        failure = null
        if (query.isNotEmpty()) delay(SEARCH_INPUT_DEBOUNCE_MS)
        if (!alive.get() || mine != ticket.get()) return@LaunchedEffect
        appIndex.access { database ->
            try {
                val state = database.searchLibraryState()
                val page = if (query.isBlank() || state != SearchLibraryState.READY) {
                    emptyList()
                } else {
                    database.searchPhotos(query, offset = 0, limit = SEARCH_PAGE_SIZE)
                }
                mainHandler.post {
                    if (!alive.get() || mine != ticket.get()) return@post
                    library = state
                    hits = page
                    shownOffset = 0
                    hasMore = page.size == SEARCH_PAGE_SIZE
                    searching = false
                    failure = null
                }
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "搜索失败"
                mainHandler.post {
                    if (!alive.get() || mine != ticket.get()) return@post
                    hits = emptyList()
                    hasMore = false
                    searching = false
                    failure = detail
                }
            }
        }
    }

    fun loadMore() {
        if (!hasMore || paging || searching || query.isBlank()) return
        val mine = ticket.get()
        val next = shownOffset + SEARCH_PAGE_SIZE
        paging = true
        appIndex.access { database ->
            try {
                val page = database.searchPhotos(query, offset = next, limit = SEARCH_PAGE_SIZE)
                mainHandler.post {
                    if (!alive.get() || mine != ticket.get()) return@post
                    hits = hits + page
                    shownOffset = next
                    hasMore = page.size == SEARCH_PAGE_SIZE
                    paging = false
                }
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "搜索失败"
                mainHandler.post {
                    if (!alive.get() || mine != ticket.get()) return@post
                    paging = false
                    failure = detail
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "搜索", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpenSettings) { Text(text = "设置") }
                Button(onClick = onBack) { Text(text = "返回") }
            }
        }
        Text(
            text = "只查本机已建库的图，不会上传。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(text = "关键词") },
            singleLine = true,
        )
        failure?.let { Text(text = it) }
        val notice = failure?.let { null } ?: searchNotice(library, query, hits.size, searching)
        if (notice != null) {
            Text(text = notice)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(hits, key = { it.assetId }) { hit ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenDetail(hit.assetId) }
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = hit.displayName, style = MaterialTheme.typography.titleMedium)
                    if (hit.excerpt.isNotBlank()) {
                        Text(text = hit.excerpt)
                    }
                }
            }
            if (hasMore) {
                item(key = "more") {
                    Button(onClick = { loadMore() }, enabled = !paging) {
                        Text(text = if (paging) "正在读取下一页…" else "下一页")
                    }
                }
            }
        }
    }
}

private fun searchNotice(
    library: SearchLibraryState,
    query: String,
    hitCount: Int,
    searching: Boolean,
): String? {
    if (searching && hitCount == 0) return "正在搜索…"
    return when (library) {
        SearchLibraryState.EMPTY_SCOPE ->
            "还没有选择相册或文件夹。先勾选范围，这里不显示示例图片。"
        SearchLibraryState.NOT_INDEXED ->
            "范围内还没有建库完成的图片。识别完成之前，这里是空的。"
        SearchLibraryState.READY -> when {
            query.isBlank() -> "输入关键词。已建库的图可以断网搜索。"
            hitCount == 0 -> "没有符合这段文字的图片。"
            else -> null
        }
    }
}

package app.photoindex

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import app.photoindex.core.PictureSource
import app.photoindex.platform.AndroidPictureSources
import app.photoindex.platform.loadSystemThumbnail
import app.photoindex.storage.InScopePhoto
import app.photoindex.storage.ScopeIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val PAGE_SIZE = 36

private val thumbnailSlots = Semaphore(4)

/**
 * 选择相册或文件夹并扫描元数据。
 * 打开时没有默认勾选。扫描只把新图写成待处理，不压缩，不上传。
 */
@Composable
fun ScopeScreen(
    onOpenSettings: () -> Unit,
    onOpenQuote: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val context = LocalContext.current
    val sources = remember { AndroidPictureSources(context) }
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

    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var albums by remember { mutableStateOf<List<PictureSource>>(emptyList()) }
    var folders by remember { mutableStateOf<List<PictureSource>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingCount by remember { mutableStateOf(0L) }
    var outOfScopeCount by remember { mutableStateOf(0L) }
    var deleteResults by remember { mutableStateOf(false) }
    var photos by remember { mutableStateOf<List<InScopePhoto>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var paging by remember { mutableStateOf(false) }

    fun apply(snapshot: ScopeSnapshot, mine: Int) {
        if (!alive.get() || mine != ticket.get()) return
        albums = snapshot.albums
        folders = snapshot.folders
        selected = snapshot.selected
        pendingCount = snapshot.pending
        outOfScopeCount = snapshot.outOfScope
        deleteResults = snapshot.deleteResults
        photos = snapshot.photos
        hasMore = snapshot.hasMore
        message = snapshot.message
        loading = false
        busy = false
        paging = false
    }

    fun reload(scanEnabled: Boolean) {
        val mine = ticket.incrementAndGet()
        busy = true
        appIndex.access { database ->
            try {
                val snapshot = readScope(context, sources, ScopeIndex(database), scanEnabled)
                mainHandler.post { apply(snapshot, mine) }
            } catch (error: SecurityException) {
                mainHandler.post {
                    if (!alive.get() || mine != ticket.get()) return@post
                    loading = false
                    busy = false
                    albums = emptyList()
                    message = "需要读取照片的权限，才能列出相册。未授予时不会把照片读入待处理。"
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (!alive.get() || mine != ticket.get()) return@post
                    loading = false
                    busy = false
                    message = error.message?.takeIf { it.isNotBlank() } ?: "读取范围失败"
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh += 1 }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val mine = ticket.incrementAndGet()
        busy = true
        appIndex.access { database ->
            try {
                val folder = sources.persistFolder(uri.toString())
                val index = ScopeIndex(database)
                index.enableSource(
                    kind = folder.kind.stored,
                    systemKey = folder.systemKey,
                    displayName = folder.displayName,
                    files = sources.listFiles(folder),
                )
                val snapshot = readScope(context, sources, index, scanEnabled = false)
                mainHandler.post { apply(snapshot, mine) }
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "文件夹授权失败"
                mainHandler.post {
                    if (!alive.get() || mine != ticket.get()) return@post
                    busy = false
                    loading = false
                    message = detail
                }
            }
        }
    }

    LaunchedEffect(refresh) {
        reload(scanEnabled = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "图片索引", style = MaterialTheme.typography.headlineMedium)
        Text(text = "待处理 $pendingCount 张", style = MaterialTheme.typography.titleMedium)
        if (outOfScopeCount > 0L) {
            Text(text = "移出范围 $outOfScopeCount 张，不再待处理，记录仍保留")
        }
        if (loading || busy) {
            ScanProgressDialog(loading = loading)
        }
        message?.let { Text(text = it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "移出范围时删除识别结果")
                Text(
                    text = "默认关闭。打开后先记住这个选择，识别结果仍会保留。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = deleteResults,
                onCheckedChange = { enabled ->
                    deleteResults = enabled
                    appIndex.access { database ->
                        ScopeIndex(database).setDeleteResultsOutOfScope(enabled)
                    }
                },
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!context.canReadImages()) {
                Button(onClick = { permissionLauncher.launch(imagePermissions()) }) {
                    Text(text = "授予照片权限")
                }
            }
            Button(onClick = { folderLauncher.launch(null) }) {
                Text(text = "添加文件夹")
            }
            Button(onClick = onOpenSearch) {
                Text(text = "搜索")
            }
            Button(onClick = onOpenSettings) {
                Text(text = "设置")
            }
            Button(onClick = onOpenQuote) {
                Text(text = "估价确认")
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (albums.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(text = "相册", style = MaterialTheme.typography.titleMedium)
                }
                items(
                    items = albums,
                    key = { "album-${it.systemKey}" },
                    span = { GridItemSpan(maxLineSpan) },
                ) { album ->
                    SourceRow(
                        title = "${album.displayName} · ${album.imageCount} 张",
                        checked = selectionKey(album) in selected,
                        onChecked = { checked ->
                            busy = true
                            toggleSource(
                                source = album,
                                checked = checked,
                                context = context,
                                appIndex = appIndex,
                                sources = sources,
                                ticket = ticket,
                                alive = alive,
                                mainHandler = mainHandler,
                                apply = ::apply,
                                onError = { detail ->
                                    busy = false
                                    message = detail
                                },
                            )
                        },
                    )
                }
            }
            if (folders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(text = "文件夹", style = MaterialTheme.typography.titleMedium)
                }
                items(
                    items = folders,
                    key = { "folder-${it.systemKey}" },
                    span = { GridItemSpan(maxLineSpan) },
                ) { folder ->
                    SourceRow(
                        title = "${folder.displayName} · ${folder.imageCount} 张",
                        checked = selectionKey(folder) in selected,
                        onChecked = { checked ->
                            busy = true
                            toggleSource(
                                source = folder,
                                checked = checked,
                                context = context,
                                appIndex = appIndex,
                                sources = sources,
                                ticket = ticket,
                                alive = alive,
                                mainHandler = mainHandler,
                                apply = ::apply,
                                onError = { detail ->
                                    busy = false
                                    message = detail
                                },
                            )
                        },
                        onRemove = {
                            val mine = ticket.incrementAndGet()
                            busy = true
                            appIndex.access { database ->
                                try {
                                    val index = ScopeIndex(database)
                                    index.disableSource(folder.kind.stored, folder.systemKey)
                                    sources.releaseFolder(folder.systemKey)
                                    val snapshot = readScope(context, sources, index, scanEnabled = false)
                                    mainHandler.post { apply(snapshot, mine) }
                                } catch (error: Exception) {
                                    mainHandler.post {
                                        if (!alive.get() || mine != ticket.get()) return@post
                                        busy = false
                                        message = error.message?.takeIf { it.isNotBlank() } ?: "移除文件夹失败"
                                    }
                                }
                            }
                        },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(text = "范围内的照片", style = MaterialTheme.typography.titleMedium)
            }
            if (photos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(text = "勾选来源后，这里按页显示缩略图。")
                }
            }
            items(photos, key = { it.id }) { photo ->
                SystemThumbnail(
                    photo = photo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
            }
            if (hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                    LaunchedEffect(photos.lastOrNull()?.id) {
                        val last = photos.lastOrNull() ?: return@LaunchedEffect
                        if (paging) return@LaunchedEffect
                        paging = true
                        val mine = ticket.get()
                        appIndex.access { database ->
                            val page = ScopeIndex(database).photosAfter(last.dateModified, last.id, PAGE_SIZE)
                            mainHandler.post {
                                if (!alive.get() || mine != ticket.get()) return@post
                                photos = photos + page
                                hasMore = page.size == PAGE_SIZE
                                paging = false
                            }
                        }
                    }
                    Text(text = if (paging) "正在读取下一页…" else "继续滑动以加载下一页")
                }
            }
        }
    }
}

private data class ScopeSnapshot(
    val albums: List<PictureSource>,
    val folders: List<PictureSource>,
    val selected: Set<String>,
    val pending: Long,
    val outOfScope: Long,
    val deleteResults: Boolean,
    val photos: List<InScopePhoto>,
    val hasMore: Boolean,
    val message: String?,
)

private fun readScope(
    context: android.content.Context,
    sources: AndroidPictureSources,
    index: ScopeIndex,
    scanEnabled: Boolean,
): ScopeSnapshot {
    val allowed = context.canReadImages()
    val partial = context.hasPartialImageAccess()
    val albums = if (allowed) sources.listAlbums() else emptyList()
    val folders = sources.persistedFolders()
    if (scanEnabled) {
        val enabled = index.enabledSources().map { selectionKey(it.kind, it.systemKey) }.toSet()
        (albums + folders).forEach { source ->
            if (selectionKey(source) in enabled) {
                index.enableSource(
                    kind = source.kind.stored,
                    systemKey = source.systemKey,
                    displayName = source.displayName,
                    files = sources.listFiles(source),
                )
            }
        }
    }
    val selected = index.enabledSources().map { selectionKey(it.kind, it.systemKey) }.toSet()
    val photos = index.photosAfter(-1, "", PAGE_SIZE)
    val message = when {
        !allowed -> "需要读取照片的权限，才能列出相册。未授予时不会把照片读入待处理。"
        partial -> "当前只授权了部分照片，列表里只有这些照片。"
        selected.isEmpty() -> "还没有勾选相册或文件夹。未勾选的照片不会进入待处理。"
        else -> null
    }
    return ScopeSnapshot(
        albums = albums,
        folders = folders,
        selected = selected,
        pending = index.pendingCount(),
        outOfScope = index.countByStatus("out_of_scope"),
        deleteResults = index.deleteResultsOutOfScope(),
        photos = photos,
        hasMore = photos.size == PAGE_SIZE,
        message = message,
    )
}

private fun toggleSource(
    source: PictureSource,
    checked: Boolean,
    context: android.content.Context,
    appIndex: AppIndex,
    sources: AndroidPictureSources,
    ticket: AtomicInteger,
    alive: AtomicBoolean,
    mainHandler: Handler,
    apply: (ScopeSnapshot, Int) -> Unit,
    onError: (String) -> Unit,
) {
    val mine = ticket.incrementAndGet()
    appIndex.access { database ->
        try {
            val index = ScopeIndex(database)
            if (checked) {
                index.enableSource(
                    kind = source.kind.stored,
                    systemKey = source.systemKey,
                    displayName = source.displayName,
                    files = sources.listFiles(source),
                )
            } else {
                index.disableSource(source.kind.stored, source.systemKey)
            }
            val snapshot = readScope(context, sources, index, scanEnabled = false)
            mainHandler.post { apply(snapshot, mine) }
        } catch (error: Exception) {
            val detail = error.message?.takeIf { it.isNotBlank() } ?: "更新范围失败"
            mainHandler.post {
                if (!alive.get() || mine != ticket.get()) return@post
                onError(detail)
            }
        }
    }
}

private fun selectionKey(source: PictureSource): String = selectionKey(source.kind.stored, source.systemKey)

private fun selectionKey(kind: String, systemKey: String): String = "$kind\u0000$systemKey"

@Composable
private fun ScanProgressDialog(loading: Boolean) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(shape = MaterialTheme.shapes.medium) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(text = if (loading) "正在读取相册…" else "正在扫描选中范围…")
            }
        }
    }
}

@Composable
private fun SourceRow(
    title: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(
            text = title,
            modifier = Modifier
                .weight(1f)
                .clickable { onChecked(!checked) },
        )
        if (onRemove != null) {
            Button(onClick = onRemove) {
                Text(text = "移除")
            }
        }
    }
}

@Composable
private fun SystemThumbnail(photo: InScopePhoto, modifier: Modifier) {
    val context = LocalContext.current
    val current = remember(photo.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(photo.id) {
        thumbnailSlots.acquire()
        try {
            val signal = CancellationSignal()
            val subscription = coroutineContext[Job]?.invokeOnCompletion { signal.cancel() }
            val loaded = try {
                withContext(Dispatchers.IO) {
                    loadSystemThumbnail(context, photo.kind, photo.systemKey, photo.systemId, signal)
                }
            } finally {
                subscription?.dispose()
            }
            if (!isActive) {
                if (loaded != null && !loaded.isRecycled) loaded.recycle()
                return@LaunchedEffect
            }
            current.value = loaded
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            current.value = null
        } finally {
            thumbnailSlots.release()
        }
    }
    DisposableEffect(photo.id) {
        onDispose {
            val bitmap = current.value
            current.value = null
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        }
    }
    val bitmap = current.value
    if (bitmap != null && !bitmap.isRecycled) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = photo.displayName,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

private fun imagePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= 34) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
    } else if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private fun android.content.Context.canReadImages(): Boolean {
    if (Build.VERSION.SDK_INT >= 33) {
        return granted(Manifest.permission.READ_MEDIA_IMAGES) || hasPartialImageAccess()
    }
    return granted(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun android.content.Context.hasPartialImageAccess(): Boolean {
    return Build.VERSION.SDK_INT >= 34 &&
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
        !granted(Manifest.permission.READ_MEDIA_IMAGES)
}

private fun android.content.Context.granted(permission: String): Boolean {
    return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

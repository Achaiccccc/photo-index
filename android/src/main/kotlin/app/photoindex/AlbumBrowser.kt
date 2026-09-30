package app.photoindex

import android.Manifest
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.photoindex.core.PictureSource
import app.photoindex.platform.AndroidPictureSources
import java.util.concurrent.Executors

/**
 * T8 只展示相册和已授权文件夹的名称、张数。
 * 不画缩略图，不打开原图，也不写入待处理记录。
 */
@Composable
fun AlbumBrowser() {
    val context = LocalContext.current
    val sources = remember { AndroidPictureSources(context) }
    val worker = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(Unit) {
        onDispose { worker.shutdown() }
    }

    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var albums by remember { mutableStateOf<List<PictureSource>>(emptyList()) }
    var folders by remember { mutableStateOf<List<PictureSource>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refresh += 1 }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        worker.execute {
            try {
                sources.persistFolder(uri.toString())
                mainHandler.post { refresh += 1 }
            } catch (error: Exception) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: "文件夹授权失败"
                mainHandler.post { message = detail }
            }
        }
    }

    DisposableEffect(refresh) {
        loading = true
        message = null
        val allowed = context.canReadImages()
        val partial = context.hasPartialImageAccess()
        val generation = refresh
        worker.execute {
            try {
                val nextAlbums = if (allowed) sources.listAlbums() else emptyList()
                val nextFolders = sources.persistedFolders()
                mainHandler.post {
                    if (generation != refresh) return@post
                    albums = nextAlbums
                    folders = nextFolders
                    loading = false
                    message = when {
                        !allowed -> "需要读取照片的权限，才能列出相册名称和张数。不会打开原图。"
                        partial -> "当前只授权了部分照片，列表里只有这些照片。"
                        nextAlbums.isEmpty() && nextFolders.isEmpty() -> "没有可读的相册。"
                        else -> null
                    }
                }
            } catch (error: SecurityException) {
                mainHandler.post {
                    if (generation != refresh) return@post
                    loading = false
                    albums = emptyList()
                    message = "需要读取照片的权限，才能列出相册名称和张数。不会打开原图。"
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (generation != refresh) return@post
                    loading = false
                    message = error.message?.takeIf { it.isNotBlank() } ?: "读取相册失败"
                }
            }
        }
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "图片索引", style = MaterialTheme.typography.headlineMedium)
        Text(text = "只显示名称和张数，不打开原图。", style = MaterialTheme.typography.bodyMedium)
        if (loading) {
            Text(text = "正在读取相册…")
        }
        message?.let { Text(text = it) }
        if (!context.canReadImages()) {
            Button(
                onClick = { permissionLauncher.launch(imagePermissions()) },
            ) {
                Text(text = "授予照片权限")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { folderLauncher.launch(null) }) {
                Text(text = "添加文件夹")
            }
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (albums.isNotEmpty()) {
                item { Text(text = "相册", style = MaterialTheme.typography.titleMedium) }
                items(albums, key = { "album-${it.systemKey}" }) { album ->
                    Text(text = "${album.displayName} · ${album.imageCount} 张")
                }
            }
            if (folders.isNotEmpty()) {
                item { Text(text = "文件夹", style = MaterialTheme.typography.titleMedium) }
                items(folders, key = { "folder-${it.systemKey}" }) { folder ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "${folder.displayName} · ${folder.imageCount} 张",
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                worker.execute {
                                    sources.releaseFolder(folder.systemKey)
                                    mainHandler.post { refresh += 1 }
                                }
                            },
                        ) {
                            Text(text = "移除")
                        }
                    }
                }
            }
        }
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

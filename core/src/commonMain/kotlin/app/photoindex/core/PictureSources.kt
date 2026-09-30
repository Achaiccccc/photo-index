package app.photoindex.core

/**
 * 图片来源。Android 用相册桶和文档树，桌面端以后另写实现。
 * 这里只描述元数据，不包含原图字节。
 */
enum class PictureSourceKind(val stored: String) {
    Album("album"),
    Folder("folder"),
}

/** 一个相册或文件夹。imageCount 来自元数据条数，不是解码后的张数。 */
data class PictureSource(
    val systemKey: String,
    val kind: PictureSourceKind,
    val displayName: String,
    val imageCount: Int,
)

/** 一张图的元数据，对应 asset 里由系统读到的那些字段。 */
data class PictureFile(
    val systemId: String,
    val fileName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val dateModifiedMillis: Long,
    val dateTakenMillis: Long?,
    val sourceKey: String,
)

/**
 * 列出相册，以及用持久权限记住的文件夹。
 * 实现只能查询元数据，不能打开原图。调用方不要在主线程上扫描整个来源。
 * 系统的文件夹选择器一次返回一个目录；多次调用 [persistFolder] 就是多选。
 */
interface PictureSources {
    fun listAlbums(): List<PictureSource>

    fun persistFolder(treeUri: String): PictureSource

    fun persistedFolders(): List<PictureSource>

    fun releaseFolder(systemKey: String)

    fun listFiles(source: PictureSource): List<PictureFile>
}

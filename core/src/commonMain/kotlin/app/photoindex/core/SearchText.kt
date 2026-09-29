package app.photoindex.core

/**
 * 本机就能读到、不占用模型输出的三段文字。日期由调用方格式化后传入。
 */
data class LocalPictureText(
    val fileName: String,
    val albumName: String,
    val takenDate: String,
)

/**
 * 用户追加的词和从模型结果里删掉的片段。重跑时整份保留，不跟模型记录一起替换。
 */
data class UserTerms(
    val addedTerms: List<String> = emptyList(),
    val suppressedTerms: List<String> = emptyList(),
)

/**
 * 重跑之后要落库的三样东西：新的模型记录、原样留下的用户修改、重新拼出的搜索文本。
 */
data class MergedRecognition(
    val model: ModelRecord,
    val user: UserTerms,
    val searchText: String,
)

/**
 * 用新的模型记录替换旧记录，用户追加词和屏蔽列表保持不变，并按新记录重拼 [searchText]。
 */
fun mergeOnRerun(
    previousUser: UserTerms,
    newModel: ModelRecord,
    local: LocalPictureText,
): MergedRecognition {
    val user = UserTerms(
        addedTerms = previousUser.addedTerms.toList(),
        suppressedTerms = previousUser.suppressedTerms.toList(),
    )
    return MergedRecognition(
        model = newModel,
        user = user,
        searchText = searchText(newModel, user, local),
    )
}

/**
 * 搜索用的唯一正文。先把模型里的非空字段、用户追加词、文件名、相册名、拍摄日期按同一规则规范化，
 * 再去掉屏蔽片段，避免全角或大小写让屏蔽词漏进正文。
 */
fun searchText(
    record: ModelRecord,
    user: UserTerms,
    local: LocalPictureText,
): String {
    val pieces = buildList {
        add(record.summary)
        addAll(record.objects)
        addAll(record.scene)
        add(record.platform)
        add(record.author)
        add(record.publishedAt)
        add(record.ocrText)
        addAll(record.tags)
        addAll(user.addedTerms)
        add(local.fileName)
        add(local.albumName)
        add(local.takenDate)
    }.map { normalizeSearchPiece(it) }.filter { it.isNotEmpty() }
    val joined = pieces.joinToString(" ")
    val suppressed = user.suppressedTerms
        .map { normalizeSearchPiece(it) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
    var text = joined
    for (term in suppressed) {
        text = text.replace(term, "")
    }
    return text.replace(WHITESPACE_RUN, " ").trim()
}

/**
 * 全角 ASCII 转半角，英文字母改成小写，去掉首尾空白。
 */
internal fun normalizeSearchPiece(raw: String): String = buildString(raw.length) {
    for (ch in raw) {
        val folded = when {
            ch == '\u3000' -> ' '
            ch in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
            else -> ch
        }
        append(if (folded in 'A'..'Z') folded + ('a' - 'A') else folded)
    }
}.trim()

private val WHITESPACE_RUN = Regex("\\s+")

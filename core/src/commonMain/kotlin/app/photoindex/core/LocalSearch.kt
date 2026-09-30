package app.photoindex.core

/**
 * 设置里的匹配方式。`any` 是命中任意片段，命中段数多的排前面；`all` 是必须同时包含全部片段。
 */
enum class SearchMatchMode {
    ANY,
    ALL,
}

/**
 * 用户输入里的一段。没有空格时整段就是一个片段，用来做子串匹配。
 * [alternatives] 含这段本身，以及同义词表里和它相等的词。扩展只发生在查询时。
 */
data class SearchSegment(
    val text: String,
    val alternatives: List<String>,
)

data class SearchPlan(
    val mode: SearchMatchMode,
    val segments: List<SearchSegment>,
)

/** 第一版内置同义词。与打开索引库时写入 setting 的默认值相同。 */
const val DEFAULT_SYNONYM_TABLE = "小红书 / 红书"

/**
 * FTS5 trigram 的全文查询忽略短于 3 个 Unicode 字符的片段。
 * 更短的片段要先换成索引里真正包含它的 trigram，再交给 MATCH。
 */
const val FTS_TRIGRAM_MIN_CODE_POINTS = 3

/** 列表上一段摘录的最长字数。更长时围着命中的片段截开。 */
const val SEARCH_EXCERPT_MAX_CODE_POINTS = 48

private val SENTENCE_ENDS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n')

fun searchMatchMode(stored: String): SearchMatchMode = when (stored) {
    "any" -> SearchMatchMode.ANY
    "all" -> SearchMatchMode.ALL
    else -> throw IllegalArgumentException("未知匹配方式：$stored")
}

fun needsTrigramExpansion(text: String): Boolean =
    text.codePointCount(0, text.length) < FTS_TRIGRAM_MIN_CODE_POINTS

/**
 * 一行一组，组内用 `/` 分隔。只有至少两个词的组才参与相等扩展。
 */
fun parseSynonymTable(raw: String): List<List<String>> = raw
    .split('\n', '\r')
    .map { line ->
        line.split('/')
            .map { normalizeSearchPiece(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    .filter { it.size >= 2 }

/**
 * 按空格拆成片段，并把同义词表里相等的词放进同一段的备选。
 * 不相等的输入不会因为包含关系被扩展，例如「小红」不会变成「小红书」。
 */
fun planSearch(query: String, mode: SearchMatchMode, synonymTable: String): SearchPlan {
    val groups = parseSynonymTable(synonymTable)
    val segments = normalizeSearchPiece(query)
        .split(WHITESPACE)
        .filter { it.isNotEmpty() }
        .map { text ->
            val group = groups.firstOrNull { text in it } ?: listOf(text)
            SearchSegment(text = text, alternatives = group)
        }
    return SearchPlan(mode = mode, segments = segments)
}

/** 摘录时先找用户输入的片段，找不到再找同义词扩展出来的词。 */
fun excerptNeedles(plan: SearchPlan): List<String> {
    val primary = plan.segments.map { it.text }
    val expanded = plan.segments.flatMap { it.alternatives }.filter { it !in primary }
    return primary + expanded
}

/**
 * 把若干原文片段拼成一条 FTS5 查询。每段都加引号，避免用户输入里的 OR、AND 被当成运算符。
 * [column] 必须是索引列名，不能把用户输入放进来。
 */
fun ftsDisjunction(column: String, phrases: List<String>): String? {
    val clauses = phrases
        .filter { it.isNotEmpty() }
        .distinct()
        .map { phrase -> "$column : ${ftsPhraseLiteral(phrase)}" }
    return clauses.takeIf { it.isNotEmpty() }?.joinToString(" OR ")
}

fun ftsPhraseLiteral(phrase: String): String = "\"${phrase.replace("\"", "\"\"")}\""

/**
 * 从 [searchText] 里摘出包含查询片段的一句。句子太长时，窗口仍然包住该片段。
 */
fun searchExcerpt(
    searchText: String,
    fragmentsInPriorityOrder: List<String>,
    maxCodePoints: Int = SEARCH_EXCERPT_MAX_CODE_POINTS,
): String {
    val needle = fragmentsInPriorityOrder.firstOrNull { it.isNotEmpty() && searchText.contains(it) }
        ?: return ""
    val at = searchText.indexOf(needle)
    val before = if (at == 0) {
        -1
    } else {
        searchText.lastIndexOfAny(SENTENCE_ENDS, startIndex = at - 1)
    }
    val sentenceStart = if (before < 0) 0 else before + 1
    val after = searchText.indexOfAny(SENTENCE_ENDS, startIndex = at + needle.length)
    val sentenceEnd = if (after < 0) searchText.length else after
    var sliceStart = sentenceStart
    var sliceEnd = sentenceEnd
    if (codePointCount(searchText, sliceStart, sliceEnd) > maxCodePoints) {
        val needlePoints = codePointCount(searchText, at, at + needle.length)
        val pad = (maxCodePoints - needlePoints).coerceAtLeast(0) / 2
        sliceStart = retreatCodePoints(searchText, at, pad).coerceAtLeast(sentenceStart)
        sliceEnd = advanceCodePoints(searchText, at + needle.length, pad).coerceAtMost(sentenceEnd)
        if (sliceStart > at) sliceStart = at
        if (sliceEnd < at + needle.length) sliceEnd = at + needle.length
    }
    val prefix = if (sliceStart > 0) "…" else ""
    val suffix = if (sliceEnd < searchText.length) "…" else ""
    return prefix + searchText.substring(sliceStart, sliceEnd).trim() + suffix
}

private fun codePointCount(text: String, start: Int, end: Int): Int {
    if (end <= start) return 0
    return text.codePointCount(start, end)
}

private fun retreatCodePoints(text: String, index: Int, points: Int): Int {
    var cursor = index
    repeat(points) {
        if (cursor <= 0) return 0
        cursor = text.offsetByCodePoints(cursor, -1)
    }
    return cursor
}

private fun advanceCodePoints(text: String, index: Int, points: Int): Int {
    var cursor = index
    repeat(points) {
        if (cursor >= text.length) return text.length
        cursor = text.offsetByCodePoints(cursor, 1)
    }
    return cursor
}

private val WHITESPACE = Regex("\\s+")

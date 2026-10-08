package app.photoindex.storage

/**
 * 用户词一行一个。行尾留换行，这样带空格的整段词不会被拆开。
 * 旧数据没有换行，仍按空白拆开。
 */
internal fun joinUserTerms(terms: List<String>): String {
    val cleaned = terms.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (cleaned.isEmpty()) return ""
    return cleaned.joinToString(separator = "\n", postfix = "\n")
}

internal fun splitUserTerms(stored: String): List<String> {
    if (stored.isBlank()) return emptyList()
    val pieces = if ('\n' in stored) {
        stored.split('\n')
    } else {
        stored.split(Regex("\\s+"))
    }
    return pieces.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

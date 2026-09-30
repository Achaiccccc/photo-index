package app.photoindex.storage

import app.photoindex.core.SearchMatchMode
import app.photoindex.core.SearchPlan
import app.photoindex.core.excerptNeedles
import app.photoindex.core.ftsDisjunction
import app.photoindex.core.needsTrigramExpansion
import app.photoindex.core.planSearch
import app.photoindex.core.searchExcerpt
import app.photoindex.core.searchMatchMode

/** 设计文档里搜索结果一页几十条。调用方可以改小，用来覆盖分页。 */
const val SEARCH_PAGE_SIZE = 40

private const val FTS_TEXT_COLUMN = "searchText"
private const val FTS_CLAUSE_CHUNK = 80

data class SearchHit(
    val assetId: String,
    val displayName: String,
    val excerpt: String,
    val matchedSegments: Int,
)

/**
 * 只查 `status = done` 且来源仍启用的图。
 * 默认段与段是 OR，命中段数多的排前面；设置为「必须同时包含」时取 AND。
 * 不满 3 个字的片段不能直接 MATCH：trigram 全文查询会忽略它们。
 * 这里先从 trigram 词表找出包含该片段的词，再交给 FTS5，不用 LIKE 扫正文。
 */
fun PhotoIndexDatabase.searchPhotos(
    query: String,
    offset: Int = 0,
    limit: Int = SEARCH_PAGE_SIZE,
): List<SearchHit> {
    if (limit <= 0) return emptyList()
    val setting = settingQueries.selectSetting().executeAsOne()
    val plan = planSearch(
        query = query,
        mode = searchMatchMode(setting.matchMode),
        synonymTable = setting.synonyms,
    )
    if (plan.segments.isEmpty()) return emptyList()

    val ranked = rank(plan)
    val page = ranked.drop(offset.coerceAtLeast(0)).take(limit)
    val needles = excerptNeedles(plan)
    return page.map { rankedHit ->
        val asset = assetQueries.selectAssetById(rankedHit.assetId).executeAsOne()
        val recognition = recognitionQueries.selectRecognitionByAssetId(rankedHit.assetId).executeAsOne()
        SearchHit(
            assetId = rankedHit.assetId,
            displayName = asset.displayName,
            excerpt = searchExcerpt(recognition.searchText, needles),
            matchedSegments = rankedHit.matchedSegments,
        )
    }
}

private data class RankedHit(
    val assetId: String,
    val matchedSegments: Int,
)

private fun PhotoIndexDatabase.rank(plan: SearchPlan): List<RankedHit> {
    val hits = plan.segments.map { segment -> assetIdsMatching(segment.alternatives) }
    val ranked = when (plan.mode) {
        SearchMatchMode.ALL -> {
            if (hits.any { it.isEmpty() }) return emptyList()
            val matched = hits.reduce { left, right -> left intersect right }
            matched.map { assetId -> RankedHit(assetId, plan.segments.size) }
        }
        SearchMatchMode.ANY -> {
            val counts = linkedMapOf<String, Int>()
            hits.forEach { ids ->
                ids.forEach { assetId -> counts[assetId] = (counts[assetId] ?: 0) + 1 }
            }
            counts.map { (assetId, count) -> RankedHit(assetId, count) }
        }
    }
    return ranked.sortedWith(
        compareByDescending<RankedHit> { it.matchedSegments }.thenBy { it.assetId },
    )
}

private fun PhotoIndexDatabase.assetIdsMatching(alternatives: List<String>): Set<String> {
    val phrases = alternatives.flatMap { alternative ->
        if (needsTrigramExpansion(alternative)) {
            SearchIndex.trigramTerms(this, alternative)
                .filterNot { needsTrigramExpansion(it) }
        } else {
            listOf(alternative)
        }
    }.distinct()
    if (phrases.isEmpty()) return emptySet()
    val ids = linkedSetOf<String>()
    phrases.chunked(FTS_CLAUSE_CHUNK).forEach { chunk ->
        val expression = ftsDisjunction(FTS_TEXT_COLUMN, chunk) ?: return@forEach
        ids += searchQueries.selectSearchAssetIds(expression).executeAsList().mapNotNull { it.assetId }
    }
    return ids
}

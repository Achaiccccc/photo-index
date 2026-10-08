package app.photoindex.storage

import app.photoindex.core.LocalPictureText
import app.photoindex.core.ModelParseResult
import app.photoindex.core.ModelRecord
import app.photoindex.core.UserTerms
import app.photoindex.core.parseModelOutput
import app.photoindex.core.searchText

/**
 * 详情页要展示的一张已完成图片。模型 JSON 原样留下，用户词分开存放。
 */
data class RecognitionDetail(
    val assetId: String,
    val displayName: String,
    val kind: String,
    val systemKey: String,
    val systemId: String,
    val record: ModelRecord,
    val addedTerms: List<String>,
    val suppressedTerms: List<String>,
)

/**
 * 读取一张已完成图片的识别结果和打开原图所需的来源位置。
 * 不产生批记录，也不访问网络。
 */
fun PhotoIndexDatabase.loadRecognitionDetail(assetId: String): RecognitionDetail {
    val asset = assetQueries.selectAssetById(assetId).executeAsOneOrNull()
        ?: error("找不到这张图")
    if (asset.status != "done") error("只有已经建库的图可以查看和修改")
    val recognition = recognitionQueries.selectRecognitionByAssetId(assetId).executeAsOneOrNull()
        ?: error("这张图还没有识别结果")
    val record = when (val parsed = parseModelOutput(recognition.json)) {
        is ModelParseResult.Accepted -> parsed.record
        is ModelParseResult.Rejected -> error(parsed.reason)
    }
    val source = sourceQueries.selectSourceById(asset.sourceId).executeAsOne()
    val user = storedUserTerms(assetId)
    return RecognitionDetail(
        assetId = asset.id,
        displayName = asset.displayName,
        kind = source.kind,
        systemKey = source.systemKey,
        systemId = asset.systemId,
        record = record,
        addedTerms = user.addedTerms,
        suppressedTerms = user.suppressedTerms,
    )
}

/**
 * 把追加词和屏蔽片段写入 `user_edit`，并按同一规则重拼 `searchText`。
 * 模型 JSON 不改。不插入 `batch`，也不改任务状态。
 */
fun PhotoIndexDatabase.saveRecognitionEdits(
    assetId: String,
    addedTerms: List<String>,
    suppressedTerms: List<String>,
): String {
    val asset = assetQueries.selectAssetById(assetId).executeAsOneOrNull()
        ?: error("找不到这张图")
    if (asset.status != "done") error("只有已经建库的图可以改词")
    val recognition = recognitionQueries.selectRecognitionByAssetId(assetId).executeAsOneOrNull()
        ?: error("这张图还没有识别结果")
    val record = when (val parsed = parseModelOutput(recognition.json)) {
        is ModelParseResult.Accepted -> parsed.record
        is ModelParseResult.Rejected -> error(parsed.reason)
    }
    val user = UserTerms(
        addedTerms = splitUserTerms(joinUserTerms(addedTerms)),
        suppressedTerms = splitUserTerms(joinUserTerms(suppressedTerms)),
    )
    val text = searchText(record, user, localPictureText(assetId))
    transaction {
        userEditQueries.upsertUserEdit(
            assetId = assetId,
            addedTerms = joinUserTerms(user.addedTerms),
            suppressedTerms = joinUserTerms(user.suppressedTerms),
        )
        recognitionQueries.updateRecognitionSearchText(searchText = text, assetId = assetId)
    }
    return text
}

internal fun PhotoIndexDatabase.storedUserTerms(assetId: String): UserTerms {
    val row = userEditQueries.selectUserEditByAssetId(assetId).executeAsOneOrNull()
        ?: return UserTerms()
    return UserTerms(
        addedTerms = splitUserTerms(row.addedTerms),
        suppressedTerms = splitUserTerms(row.suppressedTerms),
    )
}

internal fun PhotoIndexDatabase.localPictureText(assetId: String): LocalPictureText {
    val asset = assetQueries.selectAssetById(assetId).executeAsOne()
    val source = sourceQueries.selectSourceById(asset.sourceId).executeAsOne()
    return LocalPictureText(
        fileName = asset.displayName,
        albumName = source.displayName,
        takenDate = asset.dateTaken?.toString().orEmpty(),
    )
}

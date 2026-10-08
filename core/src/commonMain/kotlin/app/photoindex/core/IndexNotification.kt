package app.photoindex.core

/**
 * 通知栏正文。已入库张数和总张数来自数据库，当前批按批列表的顺序从 1 计。
 * 还没有批时当前批是 0。
 */
fun indexNotificationText(
    doneCount: Int,
    totalCount: Int,
    currentBatchNumber: Int,
    paused: Boolean,
    waitingForUpload: Boolean,
): String {
    val progress = "已入库 $doneCount/$totalCount · 当前第 $currentBatchNumber 批"
    return when {
        paused -> "已暂停 · $progress"
        waitingForUpload -> "等待 Wi-Fi 或充电 · $progress"
        else -> progress
    }
}

/** 第一个还没结束的批。全部结束后用最后一批的序号。 */
fun currentBatchNumber(statesInOrder: List<String>): Int {
    if (statesInOrder.isEmpty()) return 0
    val active = statesInOrder.indexOfFirst { it !in BatchState.terminal }
    return if (active >= 0) active + 1 else statesInOrder.size
}

package app.photoindex.platform

import android.util.Log
import app.photoindex.core.BatchProvider
import app.photoindex.core.RemoteBatch

/**
 * 把千问适配器的每次上传、建任务和删除记下来。
 * 日志里只有远端 ID，没有密钥，也没有请求头。
 */
class TracingBatchProvider(
    private val delegate: BatchProvider,
    private val trace: QwenCallTrace,
) : BatchProvider {
    override fun upload(localPath: String): String {
        val id = delegate.upload(localPath)
        trace.noteUpload(id)
        Log.i(TAG, "上传完成 file=$id 累计=${trace.uploadCount()}")
        return id
    }

    override fun createTask(remoteFileId: String): String {
        val id = delegate.createTask(remoteFileId)
        trace.noteTask(id)
        Log.i(TAG, "创建任务 task=$id")
        return id
    }

    override fun query(remoteBatchId: String): RemoteBatch {
        val remote = delegate.query(remoteBatchId)
        Log.i(TAG, "查询任务 task=$remoteBatchId phase=${remote.phase}")
        return remote
    }

    override fun download(outputFileId: String, destinationPath: String) {
        delegate.download(outputFileId, destinationPath)
        Log.i(TAG, "下载结果 file=$outputFileId")
    }

    override fun cancel(remoteBatchId: String) {
        delegate.cancel(remoteBatchId)
        Log.i(TAG, "取消任务 task=$remoteBatchId")
    }

    override fun deleteRemoteFile(remoteFileId: String) {
        delegate.deleteRemoteFile(remoteFileId)
        trace.noteDelete(remoteFileId)
        Log.i(TAG, "删除远端文件 file=$remoteFileId")
    }

    private companion object {
        const val TAG = "PhotoIndex"
    }
}

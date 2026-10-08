package app.photoindex

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import app.photoindex.core.BatchIndexing
import app.photoindex.core.BatchPackLimits
import app.photoindex.core.BatchState
import app.photoindex.core.DEFAULT_BATCH_LINE_BYTES
import app.photoindex.core.FixedDetailBatchProvider
import app.photoindex.core.JobState
import app.photoindex.platform.AssetJpegCompressor
import app.photoindex.platform.FileFakeProviderLedger
import app.photoindex.platform.fakeLedgerFile
import app.photoindex.storage.DirectoryBatchFiles
import app.photoindex.storage.DirectoryBatchWorkspace
import app.photoindex.storage.IndexSettingsStore
import app.photoindex.storage.PhotoIndexBatches
import app.photoindex.storage.PhotoIndexDatabase
import app.photoindex.storage.openPhotoIndexDatabase
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 确认之后的前台建库任务。
 * 内部调用批状态机。这一节点注入假服务商，不连接百炼。
 * 假服务商拿到任务号后先保持运行中一段时间，方便划掉应用再打开核对任务号。
 * 进程被杀掉之后不会自己复活；再次打开应用时按库里的进度继续。
 */
class IndexingService : Service() {
    private val commands = LinkedBlockingQueue<String>()
    private val lock = Any()
    private var worker: Thread? = null
    private var batchNumber = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startInForeground(placeholderNotification())
        commands.offer(intent?.action ?: ACTION_RECOVER)
        ensureWorker()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        commands.offer(ACTION_STOP)
        super.onDestroy()
    }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            worker = Thread(::runLoop, "index-batch").also { it.start() }
        }
    }

    private fun runLoop() {
        val opened = openPhotoIndexDatabase(photoIndexDatabaseFile(this).absolutePath)
        val progress = openPhotoIndexDatabase(photoIndexDatabaseFile(this).absolutePath)
        val stopProgress = AtomicBoolean(false)
        val progressThread = Thread {
            while (!stopProgress.get()) {
                try {
                    publish(progress.database)
                } catch (error: Exception) {
                    Log.e(TAG, "刷新通知失败", error)
                }
                try {
                    Thread.sleep(PROGRESS_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        try {
            progressThread.start()
            val database = opened.database
            val directory = File(cacheDir, "batches")
            val indexing = BatchIndexing(
                catalog = PhotoIndexBatches(database),
                provider = fakeProvider(),
                workspace = DirectoryBatchWorkspace(directory),
                compressor = AssetJpegCompressor(this, database),
                files = DirectoryBatchFiles(directory),
                newBatchId = { "b-${System.currentTimeMillis()}-${++batchNumber}" },
                limits = limitsFrom(database),
                uploadAllowed = { uploadAllowedNow(this, database) },
            )
            while (true) {
                val action = commands.poll(POLL_SECONDS, TimeUnit.SECONDS)
                if (action == ACTION_STOP) break
                try {
                    when (action) {
                        ACTION_START -> {
                            val blocked = indexStartBlockReason(IndexSettingsStore(database).load())
                            if (blocked != null) {
                                Log.i(TAG, blocked)
                                break
                            }
                            indexing.start()
                        }
                        ACTION_PAUSE -> indexing.pause()
                        ACTION_RESUME -> {
                            PhotoIndexBatches(database).setJobState(JobState.RUNNING)
                            indexing.resume()
                        }
                        ACTION_CANCEL -> {
                            indexing.cancel()
                            break
                        }
                        else -> indexing.resume()
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "批量任务出错", error)
                }
                publish(database)
                if (shouldFinish(database)) break
            }
        } finally {
            stopProgress.set(true)
            progressThread.interrupt()
            progressThread.join(PROGRESS_INTERVAL_MILLIS)
            try {
                progress.close()
            } catch (error: Exception) {
                Log.e(TAG, "关闭进度库失败", error)
            }
            try {
                opened.close()
            } catch (error: Exception) {
                Log.e(TAG, "关闭索引库失败", error)
            }
            synchronized(lock) {
                if (Thread.currentThread() == worker) worker = null
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fakeProvider(): FixedDetailBatchProvider = FixedDetailBatchProvider(
        ledger = FileFakeProviderLedger(fakeLedgerFile(this)),
        readLines = { path -> File(path).readLines() },
        writeText = { path, text ->
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(text)
        },
        holdMillis = FAKE_HOLD_MILLIS,
        now = System::currentTimeMillis,
    )

    private fun limitsFrom(database: PhotoIndexDatabase): BatchPackLimits {
        val setting = database.settingQueries.selectSetting().executeAsOne()
        val maxFileBytes = setting.batchMaxBytes.coerceAtLeast(2L)
        return BatchPackLimits(
            maxFileBytes = maxFileBytes,
            maxLines = setting.batchMaxLines.toInt(),
            maxLineBytes = minOf(DEFAULT_BATCH_LINE_BYTES, maxFileBytes - 1),
        )
    }

    private fun shouldFinish(database: PhotoIndexDatabase): Boolean {
        val job = database.jobQueries.selectJob().executeAsOne().state
        val batches = database.batchQueries.selectAllBatches().executeAsList()
        val pending = database.assetQueries.countPending().executeAsOne()
        val active = batches.any { it.state !in BatchState.terminal }
        if (active) return false
        if (pending > 0L && (job == JobState.RUNNING || job == JobState.PAUSED)) return false
        if (job != JobState.IDLE) PhotoIndexBatches(database).setJobState(JobState.IDLE)
        return true
    }

    private fun publish(database: PhotoIndexDatabase) {
        val progress = readIndexProgress(this, database, uploadAllowedNow(this, database))
        val task = progress.batches.mapNotNull { it.remoteBatchId }.lastOrNull()
        Log.i(TAG, "${progress.notificationText} 上传次数=${progress.uploadCount} 任务=${task ?: "无"}")
        startInForeground(buildNotification(progress.notificationText, progress.jobState, task))
    }

    private fun placeholderNotification(): Notification =
        buildNotification("已入库 0/0 · 当前第 0 批", JobState.RUNNING, null)

    private fun buildNotification(text: String, job: String, taskId: String?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = if (taskId == null) text else "$text\n任务 $taskId"
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_index_status)
            .setContentTitle("图片索引")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (job == JobState.PAUSED) {
            builder.addAction(action(android.R.drawable.ic_media_play, "继续", ACTION_RESUME, 1))
        } else {
            builder.addAction(action(android.R.drawable.ic_media_pause, "暂停", ACTION_PAUSE, 2))
        }
        builder.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "停止并取消", ACTION_CANCEL, 3))
        return builder.build()
    }

    private fun action(icon: Int, title: String, action: String, requestCode: Int): Notification.Action {
        val pending = PendingIntent.getService(
            this,
            requestCode,
            Intent(this, IndexingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, icon), title, pending).build()
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "建库进度", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "app.photoindex.action.START"
        const val ACTION_RECOVER = "app.photoindex.action.RECOVER"
        const val ACTION_PAUSE = "app.photoindex.action.PAUSE"
        const val ACTION_RESUME = "app.photoindex.action.RESUME"
        const val ACTION_CANCEL = "app.photoindex.action.CANCEL"
        private const val ACTION_STOP = "app.photoindex.action.STOP"
        private const val CHANNEL_ID = "indexing"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_SECONDS = 1L
        private const val PROGRESS_INTERVAL_MILLIS = 1000L

        /** 拿到任务号之后先保持运行中，留出划掉应用的时间。 */
        const val FAKE_HOLD_MILLIS = 20_000L

        private const val TAG = "PhotoIndex"

        fun start(context: Context) = launch(context, ACTION_START)

        fun pause(context: Context) = launch(context, ACTION_PAUSE)

        fun resume(context: Context) = launch(context, ACTION_RESUME)

        fun cancel(context: Context) = launch(context, ACTION_CANCEL)

        fun recoverIfNeeded(context: Context, stillVisible: () -> Boolean) {
            val appContext = context.applicationContext
            val main = Handler(Looper.getMainLooper())
            Thread {
                val needed = try {
                    needsRecover(appContext)
                } catch (error: Exception) {
                    Log.e(TAG, "检查未完成的批失败", error)
                    false
                }
                if (!needed) return@Thread
                main.post {
                    if (stillVisible()) launch(appContext, ACTION_RECOVER)
                }
            }.start()
        }

        private fun needsRecover(context: Context): Boolean {
            val file = photoIndexDatabaseFile(context)
            if (!file.isFile) return false
            openPhotoIndexDatabase(file.absolutePath).use { opened ->
                val database = opened.database
                val state = database.jobQueries.selectJob().executeAsOne().state
                val active = database.batchQueries.selectAllBatches().executeAsList().any {
                    it.state !in BatchState.terminal
                }
                return state == JobState.RUNNING || state == JobState.PAUSED || active
            }
        }

        private fun launch(context: Context, action: String) {
            val intent = Intent(context.applicationContext, IndexingService::class.java).setAction(action)
            context.applicationContext.startForegroundService(intent)
        }
    }
}

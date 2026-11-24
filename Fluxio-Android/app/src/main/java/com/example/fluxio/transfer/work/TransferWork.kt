package com.example.fluxio.transfer.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.UUID

enum class TransferDirection { UPLOAD, DOWNLOAD }

fun interface TransferExecutor {
    suspend fun execute(localId: UUID, direction: TransferDirection, progress: suspend (Long, Long) -> Unit)
}

object TransferDependencies {
    @Volatile var executor: TransferExecutor? = null
}

class TransferScheduler(private val workManager: WorkManager) {
    fun enqueue(localId: UUID, direction: TransferDirection) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(ID to localId.toString(), DIRECTION to direction.name))
            .addTag(name(localId))
            .build()
        workManager.enqueueUniqueWork(name(localId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(localId: UUID) = workManager.cancelUniqueWork(name(localId))

    companion object {
        internal const val ID = "transferId"
        internal const val DIRECTION = "direction"
        fun name(localId: UUID) = "transfer-$localId"
    }
}

class TransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(TransferScheduler.ID)?.let(UUID::fromString) ?: return Result.failure()
        val direction = inputData.getString(TransferScheduler.DIRECTION)
            ?.let(TransferDirection::valueOf) ?: return Result.failure()
        val executor = TransferDependencies.executor ?: return Result.failure()
        setForeground(notification(id, 0, 0))
        return try {
            executor.execute(id, direction) { done, total ->
                setProgress(workDataOf("transferredBytes" to done, "totalBytes" to total))
                setForeground(notification(id, done, total))
            }
            Result.success()
        } catch (_: java.io.IOException) {
            Result.retry()
        }
    }

    private fun notification(id: UUID, done: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "File transfers", NotificationManager.IMPORTANCE_LOW))
        val percent = if (total > 0) ((done * 100) / total).coerceIn(0, 100).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Fluxio file transfer")
            .setContentText(if (total > 0) "$percent%" else "Starting…")
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, percent, total <= 0).build()
        return ForegroundInfo(id.hashCode(), notification)
    }

    companion object { private const val CHANNEL = "fluxio-transfers" }
}

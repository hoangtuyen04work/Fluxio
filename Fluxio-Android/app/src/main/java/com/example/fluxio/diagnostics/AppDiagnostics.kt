package com.example.fluxio.diagnostics

import android.content.Context
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AppDiagnostics(private val context: Context) {
    private val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        .apply { mkdirs() }
    val systemLogFile = File(directory, "android-system.log")
    val performanceLogFile = File(directory, "upload-performance.csv")

    init {
        system(
            "INFO",
            "diagnostics initialized; sdk=${Build.VERSION.SDK_INT}; device=${Build.MANUFACTURER} " +
                "${Build.MODEL}; app=${context.packageName}; files=${directory.absolutePath}"
        )
    }

    @Synchronized
    fun system(level: String, message: String, error: Throwable? = null) {
        val safeMessage = message.replace('\n', ' ').replace('\r', ' ')
        val line = buildString {
            append(Instant.now()).append(' ')
            append(level).append(' ')
            append(safeMessage)
            if (error != null) {
                append("; exception=").append(error.javaClass.name)
                append("; detail=").append(error.message.orEmpty().replace('\n', ' ').replace('\r', ' '))
            }
            appendLine()
        }
        systemLogFile.appendText(line)
        when (level) {
            "ERROR" -> Log.e(TAG, safeMessage, error)
            "WARN" -> Log.w(TAG, safeMessage, error)
            else -> Log.i(TAG, safeMessage)
        }
    }

    fun <T> recordUpload(mode: String, source: Uri, upload: () -> T): T {
        val sourceInfo = sourceInfo(source)
        val runId = UUID.randomUUID().toString()
        val startedAt = Instant.now()
        val startedNanos = System.nanoTime()
        val startedCpuMillis = Process.getElapsedCpuTime()
        val startedTxBytes = TrafficStats.getUidTxBytes(Process.myUid())
        val runtime = Runtime.getRuntime()
        val heapStart = usedHeap(runtime)
        val peakHeap = AtomicLong(heapStart)
        val sampler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "fluxio-heap-sampler").apply { isDaemon = true }
        }
        sampler.scheduleAtFixedRate(
            { peakHeap.accumulateAndGet(usedHeap(runtime), ::maxOf) },
            0,
            HEAP_SAMPLE_MILLIS,
            TimeUnit.MILLISECONDS
        )
        system("INFO", "upload started; runId=$runId; mode=$mode; file=${sourceInfo.name}; bytes=${sourceInfo.size}")

        var status = "SUCCESS"
        var resultText = ""
        var failure: Throwable? = null
        try {
            return upload().also { resultText = it.toString() }
        } catch (error: Throwable) {
            status = "FAILED"
            failure = error
            throw error
        } finally {
            sampler.shutdownNow()
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
            val cpuMillis = Process.getElapsedCpuTime() - startedCpuMillis
            val heapEnd = usedHeap(runtime)
            peakHeap.accumulateAndGet(heapEnd, ::maxOf)
            val endingTxBytes = TrafficStats.getUidTxBytes(Process.myUid())
            val transmittedBytes = counterDelta(startedTxBytes, endingTxBytes)
            val throughputMbps = if (durationMillis > 0 && sourceInfo.size > 0) {
                sourceInfo.size * 8.0 / durationMillis / 1_000.0
            } else 0.0
            val cpuPercent = if (durationMillis > 0) cpuMillis * 100.0 / durationMillis else 0.0
            appendPerformance(
                listOf(
                    startedAt.toString(), runId, mode, sourceInfo.name, sourceInfo.size.toString(),
                    durationMillis.toString(), decimal(throughputMbps), cpuMillis.toString(),
                    decimal(cpuPercent), heapStart.toString(), heapEnd.toString(),
                    peakHeap.get().toString(), transmittedBytes.toString(), status, resultText,
                    failure?.javaClass?.simpleName.orEmpty(), failure?.message.orEmpty()
                )
            )
            system(
                if (failure == null) "INFO" else "ERROR",
                "upload finished; runId=$runId; mode=$mode; status=$status; durationMs=$durationMillis; " +
                    "throughputMbps=${decimal(throughputMbps)}",
                failure
            )
        }
    }

    @Synchronized
    private fun appendPerformance(values: List<String>) {
        if (!performanceLogFile.exists() || performanceLogFile.length() == 0L) {
            performanceLogFile.appendText(COLUMNS.joinToString(",") + "\n")
        }
        performanceLogFile.appendText(values.joinToString(",", postfix = "\n", transform = ::csv))
    }

    private fun sourceInfo(source: Uri): SourceInfo {
        var name = source.lastPathSegment ?: "upload.bin"
        var size = -1L
        context.contentResolver.query(
            source,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { size = cursor.getLong(it) }
            }
        }
        return SourceInfo(name, size)
    }

    private data class SourceInfo(val name: String, val size: Long)

    private companion object {
        const val TAG = "Fluxio"
        const val HEAP_SAMPLE_MILLIS = 100L
        val COLUMNS = listOf(
            "startedAt", "runId", "mode", "fileName", "fileSizeBytes", "durationMillis",
            "throughputMbps", "processCpuMillis", "approxCpuPercent", "heapStartBytes",
            "heapEndBytes", "peakHeapBytes", "uidTxBytes", "status", "fileId",
            "errorType", "errorMessage"
        )

        fun usedHeap(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()
        fun counterDelta(start: Long, end: Long): Long =
            if (start >= 0 && end >= start) end - start else -1L
        fun decimal(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
        fun csv(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }
}

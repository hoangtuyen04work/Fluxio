package com.example.fluxio.benchmark

import java.util.Locale
import kotlin.random.Random

enum class BenchmarkMode { B0, B1, B2, B3, B4 }
enum class NetworkProfile { GOOD_WIFI, INTERRUPTED }

data class BenchmarkRun(
    val mode: BenchmarkMode,
    val profile: NetworkProfile,
    val fileSizeBytes: Long,
    val partSizeMiB: Int,
    val concurrency: Int,
    val warmup: Boolean
)

data class BenchmarkResult(
    val commit: String,
    val environment: String,
    val mode: BenchmarkMode,
    val direction: String,
    val fileSizeBytes: Long,
    val profile: NetworkProfile,
    val partSizeMiB: Int,
    val concurrency: Int,
    val startupMillis: Long,
    val durationMillis: Long,
    val throughputMbps: Double,
    val completed: Boolean,
    val peakHeapBytes: Long,
    val averageCpuPercent: Double,
    val temporaryStorageBytes: Long,
    val retryCount: Int,
    val redundantBytes: Long,
    val reconciliationMillis: Long,
    val checksumMatch: Boolean,
    val errorCode: String?
) {
    companion object {
        fun fixture(profile: NetworkProfile = NetworkProfile.GOOD_WIFI) = BenchmarkResult(
            "commit", "emulator", BenchmarkMode.B4, "UPLOAD", 100L * 1024 * 1024,
            profile, 8, 2, 100, 10_000, 80.0, true, 32L * 1024 * 1024,
            25.0, 0, 0, 0, 500, true, null)
    }
}

object BenchmarkPlan {
    fun candidateSelection(seed: Int): List<BenchmarkRun> {
        val candidates = listOf(5, 8, 16).flatMap { size ->
            listOf(1, 2, 4).map { concurrency -> size to concurrency }
        }
        return NetworkProfile.entries.flatMap { profile ->
            candidates.shuffled(Random(seed + profile.ordinal)).flatMap { (size, concurrency) ->
                listOf(true, false, false, false, false, false).map { warmup ->
                    BenchmarkRun(BenchmarkMode.B3, profile, 1L shl 30, size, concurrency, warmup)
                }
            }
        }
    }
}

class BenchmarkRunner(
    private val execute: suspend (BenchmarkRun) -> BenchmarkResult,
    private val cleanup: suspend () -> Unit
) {
    suspend fun run(plan: List<BenchmarkRun>, beforeEach: suspend (BenchmarkRun) -> Unit = {}): List<BenchmarkResult> {
        val measured = mutableListOf<BenchmarkResult>()
        for (run in plan) {
            cleanup()
            beforeEach(run)
            val result = execute(run)
            if (!run.warmup) measured += result
        }
        return measured
    }
}

data class GateResult(val metric: String, val passed: Boolean)

object BenchmarkGates {
    fun evaluate(result: BenchmarkResult): List<GateResult> = buildList {
        add(GateResult("checksum", result.checksumMatch))
        add(GateResult("completion", result.completed))
        if (result.profile == NetworkProfile.GOOD_WIFI && result.fileSizeBytes >= 100L * 1024 * 1024) {
            add(GateResult("throughput", result.throughputMbps >= 75.0))
        }
        add(GateResult("cpu", result.averageCpuPercent <= 50.0))
    }
}

object BenchmarkExporter {
    private val columns = listOf(
        "commit", "environment", "mode", "direction", "fileSizeBytes", "profile",
        "partSizeMiB", "concurrency", "startupMillis", "durationMillis", "throughputMbps",
        "completed", "peakHeapBytes", "averageCpuPercent", "temporaryStorageBytes",
        "retryCount", "redundantBytes", "reconciliationMillis", "checksumMatch", "errorCode")

    fun csv(results: List<BenchmarkResult>): String = buildString {
        appendLine(columns.joinToString(","))
        results.forEach { appendLine(values(it).joinToString(",") { escapeCsv(it) }) }
    }

    fun jsonLine(result: BenchmarkResult): String = columns.zip(values(result)).joinToString(
        prefix = "{", postfix = "}") { (name, value) ->
        val raw = value == "true" || value == "false" || value.toDoubleOrNull() != null
        "\"$name\":" + if (raw) value else "\"${escapeJson(value)}\""
    }

    private fun values(r: BenchmarkResult) = listOf(
        r.commit, r.environment, r.mode.name, r.direction, r.fileSizeBytes.toString(), r.profile.name,
        r.partSizeMiB.toString(), r.concurrency.toString(), r.startupMillis.toString(),
        r.durationMillis.toString(), String.format(Locale.ROOT, "%.3f", r.throughputMbps),
        r.completed.toString(), r.peakHeapBytes.toString(),
        String.format(Locale.ROOT, "%.3f", r.averageCpuPercent), r.temporaryStorageBytes.toString(),
        r.retryCount.toString(), r.redundantBytes.toString(), r.reconciliationMillis.toString(),
        r.checksumMatch.toString(), r.errorCode.orEmpty())

    private fun escapeCsv(value: String) = if (value.any { it == ',' || it == '"' || it == '\n' })
        "\"${value.replace("\"", "\"\"")}\"" else value
    private fun escapeJson(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

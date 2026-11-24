package com.example.fluxio.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class BenchmarkHarnessTest {
    @Test
    fun candidatePlanContainsWarmupsAndNinetyMeasuredRuns() {
        val runs = BenchmarkPlan.candidateSelection(seed = 7)
        assertEquals(108, runs.size)
        assertEquals(18, runs.count { it.warmup })
        assertEquals(90, runs.count { !it.warmup })
        assertEquals(setOf(5, 8, 16), runs.map { it.partSizeMiB }.toSet())
        assertEquals(setOf(1, 2, 4), runs.map { it.concurrency }.toSet())
    }

    @Test
    fun exporterIncludesRequiredEvidenceAndInterruptedHasNoThroughputGate() {
        val result = BenchmarkResult.fixture(profile = NetworkProfile.INTERRUPTED)
        val csv = BenchmarkExporter.csv(listOf(result))
        val json = BenchmarkExporter.jsonLine(result)

        assertTrue(csv.lines().first().contains("temporaryStorageBytes"))
        assertTrue(json.contains("\"checksumMatch\":true"))
        assertTrue(BenchmarkGates.evaluate(result).none { it.metric == "throughput" })
    }

    @Test
    fun runnerExcludesWarmupsAndCleansEveryRun() = runBlocking {
        var executions = 0
        var cleanups = 0
        val runner = BenchmarkRunner(
            execute = { BenchmarkResult.fixture(it.profile) },
            cleanup = { cleanups++ })
        val plan = listOf(
            BenchmarkRun(BenchmarkMode.B0, NetworkProfile.GOOD_WIFI, 10, 8, 1, true),
            BenchmarkRun(BenchmarkMode.B1, NetworkProfile.GOOD_WIFI, 10, 8, 1, false),
            BenchmarkRun(BenchmarkMode.B4, NetworkProfile.INTERRUPTED, 10, 8, 2, false))

        val results = runner.run(plan) { executions++ }

        assertEquals(3, executions)
        assertEquals(3, cleanups)
        assertEquals(2, results.size)
    }
}

package com.example.fluxio.transfer.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkPlannerTest {
    @Test
    fun exactBoundaryAndShortFinalPartAreOneBased() {
        assertEquals(
            listOf(PartRange(1, 0, 8), PartRange(2, 8, 8)),
            ChunkPlanner.plan(16, 8)
        )
        assertEquals(
            listOf(PartRange(1, 0, 8), PartRange(2, 8, 2)),
            ChunkPlanner.plan(10, 8)
        )
    }

    @Test
    fun supportsFiveGiBWithoutIntegerOverflow() {
        val parts = ChunkPlanner.plan(5_368_709_120L, 8L * 1024 * 1024)
        assertEquals(640, parts.size)
        assertEquals(5_360_320_512L, parts.last().offsetBytes)
        assertEquals(8_388_608L, parts.last().sizeBytes)
    }

    @Test
    fun rejectsNonPositiveInputs() {
        assertThrows(IllegalArgumentException::class.java) { ChunkPlanner.plan(0, 8) }
        assertThrows(IllegalArgumentException::class.java) { ChunkPlanner.plan(10, 0) }
    }
}

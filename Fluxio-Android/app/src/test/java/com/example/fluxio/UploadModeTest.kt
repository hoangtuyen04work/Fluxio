package com.example.fluxio

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadModeTest {
    @Test
    fun exposesDistinctNormalAndOptimizedChoices() {
        assertEquals("Upload bình thường — single PUT", UploadMode.NORMAL.label)
        assertEquals("Upload tối ưu — multipart", UploadMode.OPTIMIZED.label)
    }
}

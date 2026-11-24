package com.example.fluxio.transfer.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadRetryPolicyTest {
    private val policy = UploadRetryPolicy(maxAttempts = 5, baseDelayMillis = 500, maxDelayMillis = 8_000)

    @Test
    fun retriesOnlyTransientHttpStatusesAndHonorsRetryAfter() {
        assertTrue(policy.isRetryable(408))
        assertTrue(policy.isRetryable(429))
        assertTrue(policy.isRetryable(503))
        assertFalse(policy.isRetryable(400))
        assertFalse(policy.isRetryable(401))
        assertEquals(3_000, policy.delayMillis(attempt = 1, retryAfterMillis = 3_000))
    }

    @Test
    fun exponentialDelayIsCapped() {
        assertEquals(500, policy.delayMillis(1, null))
        assertEquals(1_000, policy.delayMillis(2, null))
        assertEquals(8_000, policy.delayMillis(10, null))
        assertFalse(policy.shouldAttempt(6))
    }
}

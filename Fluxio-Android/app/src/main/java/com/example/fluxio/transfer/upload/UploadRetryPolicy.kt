package com.example.fluxio.transfer.upload

class UploadRetryPolicy(
    private val maxAttempts: Int = 5,
    private val baseDelayMillis: Long = 500,
    private val maxDelayMillis: Long = 8_000
) {
    init {
        require(maxAttempts > 0 && baseDelayMillis > 0 && maxDelayMillis >= baseDelayMillis)
    }

    fun isRetryable(httpStatus: Int): Boolean =
        httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599

    fun shouldAttempt(attempt: Int): Boolean = attempt in 1..maxAttempts

    fun delayMillis(attempt: Int, retryAfterMillis: Long?): Long {
        require(attempt > 0)
        val shift = minOf(attempt - 1, 62)
        val exponential = if (shift >= 63 || baseDelayMillis > (Long.MAX_VALUE shr shift)) {
            maxDelayMillis
        } else {
            minOf(maxDelayMillis, baseDelayMillis shl shift)
        }
        return maxOf(exponential, retryAfterMillis ?: 0).coerceAtMost(maxDelayMillis)
    }
}

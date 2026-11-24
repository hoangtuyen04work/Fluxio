package com.example.fluxio.transfer.upload

data class PartRange(
    val partNumber: Int,
    val offsetBytes: Long,
    val sizeBytes: Long
)

object ChunkPlanner {
    fun plan(totalBytes: Long, partSizeBytes: Long): List<PartRange> {
        require(totalBytes > 0) { "totalBytes must be positive" }
        require(partSizeBytes > 0) { "partSizeBytes must be positive" }
        val partCount = (totalBytes + partSizeBytes - 1) / partSizeBytes
        require(partCount <= 10_000) { "part count exceeds storage limit" }
        return (0 until partCount).map { index ->
            val offset = index * partSizeBytes
            PartRange(
                partNumber = Math.toIntExact(index + 1),
                offsetBytes = offset,
                sizeBytes = minOf(partSizeBytes, totalBytes - offset)
            )
        }
    }
}

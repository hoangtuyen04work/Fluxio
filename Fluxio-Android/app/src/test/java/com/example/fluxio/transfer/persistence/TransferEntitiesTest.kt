package com.example.fluxio.transfer.persistence

import com.example.fluxio.transfer.upload.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferEntitiesTest {
    @Test
    fun persistedPartContainsConfirmationButNeverSignedUrl() {
        val part = TransferPartEntity(
            transferId = "local-1", partNumber = 2, offsetBytes = 8, sizeBytes = 2,
            etag = "etag-2", checksumSha256 = null, state = "CONFIRMED", attemptCount = 1,
            updatedAtEpochMillis = 100
        )
        assertEquals("etag-2", part.etag)
        assertNull(part.checksumSha256)
        assertEquals(TransferState.RUNNING.name, TransferStateConverter().fromState(TransferState.RUNNING))
    }
}

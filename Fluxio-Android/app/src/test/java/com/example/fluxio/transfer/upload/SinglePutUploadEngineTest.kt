package com.example.fluxio.transfer.upload

import com.example.fluxio.data.remote.FileMetadata
import com.example.fluxio.data.remote.SinglePutTicket
import com.example.fluxio.data.remote.SingleUploadSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SinglePutUploadEngineTest {
    @Test
    fun retriesWholePutFromZeroAndRecordsRedundantBytes() = runBlocking {
        val store = FakeSingleStore()
        val plane = FakeSinglePlane()
        var calls = 0
        val engine = SinglePutUploadEngine(store, plane) { _, progress ->
            calls++
            progress(100)
            if (calls == 1) throw SinglePutException(500)
        }

        engine.run()

        assertEquals(2, calls)
        assertEquals(listOf(0L, 0L), store.attemptOffsets)
        assertEquals(100L, store.redundantBytes)
        assertEquals(TransferState.COMPLETED, store.state)
    }

    private class FakeSingleStore : SinglePutStore {
        var state = TransferState.QUEUED
        var attempts = 0
        var redundantBytes = 0L
        val attemptOffsets = mutableListOf<Long>()
        override suspend fun work() = SinglePutWork("a", "x.bin", "application/octet-stream", 100)
        override suspend fun beginAttempt() { attempts++; attemptOffsets += 0 }
        override suspend fun recordAttemptBytes(bytes: Long, failed: Boolean) {
            if (failed) redundantBytes += bytes
        }
        override suspend fun setRemote(uploadId: String) = Unit
        override suspend fun setState(value: TransferState) { state = value }
    }

    private class FakeSinglePlane : SinglePutControlPlane {
        override suspend fun create(work: SinglePutWork) = SingleUploadSession(
            "u", "f", "ACTIVE", 100, SinglePutTicket("http://storage", "later"))
        override suspend fun refresh(uploadId: String) = SinglePutTicket("http://storage", "later")
        override suspend fun complete(uploadId: String) = FileMetadata(
            "f", "x.bin", "application/octet-stream", 100, "v", "now")
        override suspend fun abort(uploadId: String) = Unit
    }
}

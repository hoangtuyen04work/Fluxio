package com.example.fluxio.transfer.download

import com.example.fluxio.data.remote.DownloadTicket
import com.example.fluxio.data.remote.FileMetadata
import com.example.fluxio.transfer.upload.TransferState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumableDownloadEngineTest {
    @Test
    fun resumesFromPersistedOffsetWhenObjectVersionMatches() = runBlocking {
        val store = MemoryStore(DownloadWork("file-1", 10, 4, "v1"))
        val downloader = RecordingDownloader(6)

        ResumableDownloadEngine(store, { ticket("v1") }, downloader).run(UUID.randomUUID())

        assertEquals(4, downloader.start)
        assertEquals(10, store.progress)
        assertEquals(TransferState.COMPLETED, store.state)
        assertEquals(0, store.resetCount)
    }

    @Test
    fun restartsDestinationWhenObjectVersionChanges() = runBlocking {
        val store = MemoryStore(DownloadWork("file-1", 10, 7, "old"))
        val downloader = RecordingDownloader(10)

        ResumableDownloadEngine(store, { ticket("new") }, downloader).run(UUID.randomUUID())

        assertEquals(0, downloader.start)
        assertEquals(1, store.resetCount)
        assertEquals("new", store.version)
        assertEquals(10, store.progress)
    }

    @Test
    fun cancellationIsPersistedAsCancelled() = runBlocking {
        val store = MemoryStore(DownloadWork("file-1", 10, 0, null))
        val downloader = RangeDownloader { _, _, _, _, _ -> throw CancellationException("stop") }

        try {
            ResumableDownloadEngine(store, { ticket("v1") }, downloader).run(UUID.randomUUID())
        } catch (_: CancellationException) {
        }

        assertEquals(TransferState.CANCELLED, store.state)
    }

    private fun ticket(version: String) = DownloadTicket(
        FileMetadata("file-1", "fixture.bin", "application/octet-stream", 10, version, "now"),
        "https://storage/file-1", "later", true)

    private class RecordingDownloader(private val bytes: Long) : RangeDownloader {
        var start = -1L
        override suspend fun transfer(
            url: String,
            startOffset: Long,
            expectedBytes: Long,
            write: suspend (ByteArray, Int) -> Unit,
            progress: suspend (Long) -> Unit
        ): Long {
            start = startOffset
            progress(bytes)
            return bytes
        }
    }

    private class MemoryStore(private val work: DownloadWork) : DownloadStore {
        var state = TransferState.QUEUED
        var progress = work.transferredBytes
        var version = work.objectVersion
        var resetCount = 0
        override suspend fun load(id: UUID) = work.copy(transferredBytes = progress, objectVersion = version)
        override suspend fun reset(objectVersion: String, totalBytes: Long) {
            resetCount++
            progress = 0
            version = objectVersion
        }
        override suspend fun recordProgress(transferredBytes: Long) { progress = transferredBytes }
        override suspend fun write(bytes: ByteArray, count: Int) = Unit
        override suspend fun setState(state: TransferState) { this.state = state }
    }
}

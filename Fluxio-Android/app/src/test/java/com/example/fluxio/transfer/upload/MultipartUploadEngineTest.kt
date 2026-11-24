package com.example.fluxio.transfer.upload

import com.example.fluxio.data.remote.CompletedPart
import com.example.fluxio.data.remote.FileMetadata
import com.example.fluxio.data.remote.SignedPart
import com.example.fluxio.data.remote.SignedParts
import com.example.fluxio.data.remote.UploadedParts
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartUploadEngineTest {
    @Test
    fun serverPartsAreAuthoritativeAndConcurrencyIsBounded() = runBlocking {
        val transferId = UUID.randomUUID()
        val store = MemoryStore(UploadWork("upload-1", 32, 8))
        val control = MemoryControlPlane()
        val uploader = RecordingUploader()
        val engine = MultipartUploadEngine(store, control, uploader, maxConcurrency = 2)

        val result = engine.run(transferId)

        assertEquals("file-1", result.fileId)
        assertEquals(listOf(2, 3, 4), uploader.uploaded.sorted())
        assertTrue(uploader.maxActive.get() <= 2)
        assertEquals(listOf(1, 2, 3, 4), control.completed.map { it.partNumber })
        assertEquals(TransferState.COMPLETED, store.state)
    }

    @Test
    fun retriesOnlyTheTransientlyFailedPart() = runBlocking {
        val store = MemoryStore(UploadWork("upload-1", 16, 8))
        val control = object : UploadControlPlane {
            override suspend fun listParts(uploadId: String) = UploadedParts(uploadId, emptyList())
            override suspend fun signParts(uploadId: String, partNumbers: List<Int>) = SignedParts(
                uploadId, "expiry", partNumbers.map { SignedPart(it, "http://storage/$it") })
            override suspend fun complete(uploadId: String, parts: List<CompletedPart>) =
                FileMetadata("file-1", "f", null, 16, "v1", "now")
        }
        val attempts = mutableMapOf<Int, Int>()
        val uploader = PartUploader { _, range ->
            val attempt = (attempts[range.partNumber] ?: 0) + 1
            attempts[range.partNumber] = attempt
            if (range.partNumber == 2 && attempt == 1) throw PartUploadException(503, 0)
            CompletedPart(range.partNumber, "etag-${range.partNumber}", range.sizeBytes)
        }

        MultipartUploadEngine(store, control, uploader, 1,
            UploadRetryPolicy(3, 1, 2)).run(UUID.randomUUID())

        assertEquals(1, attempts[1])
        assertEquals(2, attempts[2])
    }

    @Test
    fun rejectsDuplicateConcurrentWorkForTheSameTransfer() = runBlocking {
        val id = UUID.randomUUID()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = MemoryStore(UploadWork("upload-1", 8, 8))
        val control = object : UploadControlPlane {
            override suspend fun listParts(uploadId: String) = UploadedParts(uploadId, emptyList())
            override suspend fun signParts(uploadId: String, partNumbers: List<Int>) = SignedParts(
                uploadId, "later", partNumbers.map { SignedPart(it, "https://storage/$it") })
            override suspend fun complete(uploadId: String, parts: List<CompletedPart>) =
                FileMetadata("file-1", "f", null, 8, "v1", "now")
        }
        val uploader = PartUploader { _, range ->
            started.complete(Unit)
            release.await()
            CompletedPart(range.partNumber, "etag", range.sizeBytes)
        }
        val registry = TransferRunRegistry()
        val first = async { MultipartUploadEngine(store, control, uploader, 1, runRegistry = registry).run(id) }
        started.await()

        var duplicate = false
        try {
            MultipartUploadEngine(store, control, uploader, 1, runRegistry = registry).run(id)
        } catch (_: DuplicateTransferException) {
            duplicate = true
        }
        release.complete(Unit)
        first.await()

        assertTrue(duplicate)
    }

    private class MemoryStore(private val work: UploadWork) : TransferStore {
        val confirmed = linkedMapOf<Int, CompletedPart>()
        var state = TransferState.QUEUED
        override suspend fun load(id: UUID) = work
        override suspend fun reconcile(parts: List<CompletedPart>) {
            confirmed.clear(); parts.forEach { confirmed[it.partNumber] = it }
        }
        override suspend fun confirm(part: CompletedPart) { confirmed[part.partNumber] = part }
        override suspend fun setState(state: TransferState) { this.state = state }
    }

    private class MemoryControlPlane : UploadControlPlane {
        var completed = emptyList<CompletedPart>()
        override suspend fun listParts(uploadId: String) = UploadedParts(
            uploadId, listOf(CompletedPart(1, "etag-1", 8)))
        override suspend fun signParts(uploadId: String, partNumbers: List<Int>) = SignedParts(
            uploadId, "2026-11-24T09:15:00Z",
            partNumbers.map { SignedPart(it, "http://storage/part/$it") })
        override suspend fun complete(uploadId: String, parts: List<CompletedPart>): FileMetadata {
            completed = parts
            return FileMetadata("file-1", "fixture.bin", null, 32, "v1", "2026-11-24T09:00:00Z")
        }
    }

    private class RecordingUploader : PartUploader {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val uploaded = java.util.Collections.synchronizedList(mutableListOf<Int>())
        override suspend fun upload(signed: SignedPart, range: PartRange): CompletedPart {
            val current = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, current) }
            try {
                delay(20)
                uploaded += range.partNumber
                return CompletedPart(range.partNumber, "etag-${range.partNumber}", range.sizeBytes)
            } finally {
                active.decrementAndGet()
            }
        }
    }
}

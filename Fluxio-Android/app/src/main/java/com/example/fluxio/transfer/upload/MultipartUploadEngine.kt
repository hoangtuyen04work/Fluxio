package com.example.fluxio.transfer.upload

import com.example.fluxio.data.remote.CompleteUploadRequest
import com.example.fluxio.data.remote.CompletedPart
import com.example.fluxio.data.remote.FileMetadata
import com.example.fluxio.data.remote.SignPartsRequest
import com.example.fluxio.data.remote.SignedParts
import com.example.fluxio.data.remote.TransferApi
import com.example.fluxio.data.remote.UploadedParts
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class UploadWork(val remoteUploadId: String, val totalBytes: Long, val partSizeBytes: Long)

enum class TransferState { QUEUED, RUNNING, PAUSED, RECONCILING, COMPLETED, FAILED, CANCELLING, CANCELLED }

interface TransferStore {
    suspend fun load(id: UUID): UploadWork
    suspend fun reconcile(parts: List<CompletedPart>)
    suspend fun confirm(part: CompletedPart)
    suspend fun setState(state: TransferState)
}

interface UploadControlPlane {
    suspend fun listParts(uploadId: String): UploadedParts
    suspend fun signParts(uploadId: String, partNumbers: List<Int>): SignedParts
    suspend fun complete(uploadId: String, parts: List<CompletedPart>): FileMetadata
}

class RetrofitUploadControlPlane(private val api: TransferApi) : UploadControlPlane {
    override suspend fun listParts(uploadId: String) = api.listParts(uploadId)
    override suspend fun signParts(uploadId: String, partNumbers: List<Int>) =
        api.signParts(uploadId, SignPartsRequest(partNumbers))
    override suspend fun complete(uploadId: String, parts: List<CompletedPart>) =
        api.completeUpload(uploadId, UUID.randomUUID().toString(), CompleteUploadRequest(parts))
}

fun interface PartUploader {
    suspend fun upload(signed: com.example.fluxio.data.remote.SignedPart, range: PartRange): CompletedPart
}

class MultipartUploadEngine(
    private val store: TransferStore,
    private val controlPlane: UploadControlPlane,
    private val uploader: PartUploader,
    maxConcurrency: Int,
    private val retryPolicy: UploadRetryPolicy = UploadRetryPolicy()
) {
    private val permits = Semaphore(maxConcurrency.also { require(it > 0) })

    suspend fun run(localTransferId: UUID): FileMetadata {
        val work = store.load(localTransferId)
        store.setState(TransferState.RECONCILING)
        return try {
            val authoritative = controlPlane.listParts(work.remoteUploadId).parts
            store.reconcile(authoritative)
            val confirmed = authoritative.associateByTo(linkedMapOf()) { it.partNumber }
            val missing = ChunkPlanner.plan(work.totalBytes, work.partSizeBytes)
                .filterNot { confirmed.containsKey(it.partNumber) }
            store.setState(TransferState.RUNNING)

            for (batch in missing.chunked(32)) {
                val signed = controlPlane.signParts(work.remoteUploadId, batch.map { it.partNumber })
                    .parts.associateBy { it.partNumber }
                val uploaded = coroutineScope {
                    batch.map { range ->
                        async {
                            permits.withPermit {
                                val part = uploadPart(
                                    work.remoteUploadId, signed.getValue(range.partNumber), range)
                                store.confirm(part)
                                part
                            }
                        }
                    }.awaitAll()
                }
                uploaded.forEach { confirmed[it.partNumber] = it }
            }

            val result = controlPlane.complete(
                work.remoteUploadId, confirmed.values.sortedBy { it.partNumber })
            store.setState(TransferState.COMPLETED)
            result
        } catch (failure: Throwable) {
            store.setState(TransferState.FAILED)
            throw failure
        }
    }

    private suspend fun uploadPart(
        uploadId: String,
        initial: com.example.fluxio.data.remote.SignedPart,
        range: PartRange
    ): CompletedPart {
        var signed = initial
        var attempt = 1
        while (true) {
            try {
                return uploader.upload(signed, range)
            } catch (failure: PartUploadException) {
                attempt++
                if (!retryPolicy.shouldAttempt(attempt)) throw failure
                if (failure.httpStatus == 403) {
                    signed = controlPlane.signParts(uploadId, listOf(range.partNumber)).parts.single()
                    continue
                }
                if (!retryPolicy.isRetryable(failure.httpStatus)) throw failure
                delay(retryPolicy.delayMillis(attempt - 1, failure.retryAfterMillis))
            }
        }
    }
}

class PartUploadException(
    val httpStatus: Int,
    val retryAfterMillis: Long? = null
) : java.io.IOException("Part upload failed with HTTP $httpStatus")

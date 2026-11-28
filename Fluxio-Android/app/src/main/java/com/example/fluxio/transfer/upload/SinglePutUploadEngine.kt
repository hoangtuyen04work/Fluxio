package com.example.fluxio.transfer.upload

import com.example.fluxio.data.remote.FileMetadata
import com.example.fluxio.data.remote.SinglePutTicket
import com.example.fluxio.data.remote.SingleUploadSession
import java.io.IOException

data class SinglePutWork(
    val idempotencyKey: String,
    val fileName: String,
    val contentType: String,
    val totalBytes: Long
)

interface SinglePutStore {
    suspend fun work(): SinglePutWork
    suspend fun beginAttempt()
    suspend fun recordAttemptBytes(bytes: Long, failed: Boolean)
    suspend fun setRemote(uploadId: String)
    suspend fun setState(value: TransferState)
}

interface SinglePutControlPlane {
    suspend fun create(work: SinglePutWork): SingleUploadSession
    suspend fun refresh(uploadId: String): SinglePutTicket
    suspend fun complete(uploadId: String): FileMetadata
    suspend fun abort(uploadId: String)
}

fun interface WholePutUploader {
    suspend fun upload(ticket: SinglePutTicket, progress: suspend (Long) -> Unit)
}

class SinglePutUploadEngine(
    private val store: SinglePutStore,
    private val controlPlane: SinglePutControlPlane,
    private val maxAttempts: Int = 5,
    private val uploader: WholePutUploader
) {
    suspend fun run(): FileMetadata {
        val work = store.work()
        require(work.totalBytes > 0) { "Source must expose an exact positive size" }
        store.setState(TransferState.RECONCILING)
        val session = controlPlane.create(work)
        store.setRemote(session.uploadId)
        var ticket = session.put
        repeat(maxAttempts) { index ->
            store.beginAttempt()
            store.setState(TransferState.RUNNING)
            var sent = 0L
            try {
                uploader.upload(ticket) { bytes -> sent = bytes }
                store.recordAttemptBytes(sent, false)
                val file = controlPlane.complete(session.uploadId)
                store.setState(TransferState.COMPLETED)
                return file
            } catch (failure: SinglePutException) {
                store.recordAttemptBytes(sent, true)
                if (failure.httpStatus == 401) {
                    store.setState(TransferState.AUTH_REQUIRED)
                    throw failure
                }
                if (index + 1 >= maxAttempts || !failure.retryable) {
                    store.setState(TransferState.FAILED)
                    throw failure
                }
                ticket = controlPlane.refresh(session.uploadId)
            } catch (failure: IOException) {
                store.recordAttemptBytes(sent, true)
                if (index + 1 >= maxAttempts) {
                    store.setState(TransferState.FAILED)
                    throw failure
                }
                ticket = controlPlane.refresh(session.uploadId)
            }
        }
        error("unreachable")
    }
}

class SinglePutException(val httpStatus: Int) : IOException("Single PUT failed with HTTP $httpStatus") {
    val retryable: Boolean get() = httpStatus == 408 || httpStatus == 429 || httpStatus >= 500
}

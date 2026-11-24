package com.example.fluxio.transfer.download

import com.example.fluxio.data.remote.DownloadTicket
import com.example.fluxio.data.remote.TransferApi
import com.example.fluxio.transfer.upload.TransferState
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class DownloadWork(
    val fileId: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val objectVersion: String?
)

interface DownloadStore {
    suspend fun load(id: UUID): DownloadWork
    suspend fun reset(objectVersion: String, totalBytes: Long)
    suspend fun write(bytes: ByteArray, count: Int)
    suspend fun recordProgress(transferredBytes: Long)
    suspend fun setState(state: TransferState)
}

fun interface DownloadControlPlane {
    suspend fun ticket(fileId: String): DownloadTicket
}

class RetrofitDownloadControlPlane(private val api: TransferApi) : DownloadControlPlane {
    override suspend fun ticket(fileId: String) = api.createDownloadTicket(fileId)
}

fun interface RangeDownloader {
    suspend fun transfer(
        url: String,
        startOffset: Long,
        expectedBytes: Long,
        write: suspend (ByteArray, Int) -> Unit,
        progress: suspend (Long) -> Unit
    ): Long
}

class ResumableDownloadEngine(
    private val store: DownloadStore,
    private val controlPlane: DownloadControlPlane,
    private val downloader: RangeDownloader
) {
    suspend fun run(localTransferId: UUID) {
        val work = store.load(localTransferId)
        store.setState(TransferState.RECONCILING)
        try {
            val ticket = controlPlane.ticket(work.fileId)
            require(ticket.rangeSupported) { "Server does not support ranged downloads" }
            val metadata = ticket.file
            var offset = work.transferredBytes.coerceIn(0, metadata.sizeBytes)
            if (work.objectVersion != metadata.objectVersion || work.totalBytes != metadata.sizeBytes) {
                store.reset(metadata.objectVersion, metadata.sizeBytes)
                offset = 0
            }
            store.setState(TransferState.RUNNING)
            val initialOffset = offset
            val transferred = downloader.transfer(
                ticket.url,
                offset,
                metadata.sizeBytes - offset,
                store::write
            ) { delta ->
                offset += delta
                store.recordProgress(offset)
            }
            check(transferred == metadata.sizeBytes - initialOffset) {
                "Download ended before the expected byte count"
            }
            check(offset == metadata.sizeBytes) { "Download ended at $offset of ${metadata.sizeBytes}" }
            store.setState(TransferState.COMPLETED)
        } catch (cancelled: CancellationException) {
            store.setState(TransferState.CANCELLED)
            throw cancelled
        } catch (failure: Throwable) {
            store.setState(TransferState.FAILED)
            throw failure
        }
    }
}

package com.example.fluxio.transfer.persistence

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.fluxio.transfer.download.DownloadStore
import com.example.fluxio.transfer.download.DownloadWork
import com.example.fluxio.transfer.upload.TransferState
import java.io.Closeable
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.util.UUID

class RoomDownloadStore(
    private val dao: TransferDao,
    private val resolver: ContentResolver,
    private val localId: UUID,
    private val now: () -> Long = System::currentTimeMillis
) : DownloadStore, Closeable {
    private var channel: FileChannel? = null
    private var descriptor: ParcelFileDescriptor? = null

    override suspend fun load(id: UUID): DownloadWork {
        require(id == localId)
        val transfer = requireNotNull(dao.transfer(id.toString()))
        require(transfer.direction == "DOWNLOAD")
        return DownloadWork(
            requireNotNull(transfer.fileId), transfer.totalBytes,
            transfer.transferredBytes, transfer.objectVersion)
    }

    override suspend fun reset(objectVersion: String, totalBytes: Long) {
        output().truncate(0).position(0)
        dao.resetDownload(localId.toString(), objectVersion, totalBytes, now())
    }

    override suspend fun write(bytes: ByteArray, count: Int) {
        val buffer = java.nio.ByteBuffer.wrap(bytes, 0, count)
        while (buffer.hasRemaining()) output().write(buffer)
    }

    override suspend fun recordProgress(transferredBytes: Long) {
        dao.setProgress(localId.toString(), transferredBytes, now())
    }

    override suspend fun setState(state: TransferState) {
        dao.setState(localId.toString(), state, now())
    }

    private suspend fun output(): FileChannel {
        channel?.let { return it }
        val transfer = requireNotNull(dao.transfer(localId.toString()))
        val uri = Uri.parse(requireNotNull(transfer.destinationUri))
        descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rw"))
        return FileOutputStream(requireNotNull(descriptor).fileDescriptor).channel.apply {
            position(transfer.transferredBytes)
            channel = this
        }
    }

    override fun close() {
        channel?.close()
        channel = null
        descriptor?.close()
        descriptor = null
    }
}

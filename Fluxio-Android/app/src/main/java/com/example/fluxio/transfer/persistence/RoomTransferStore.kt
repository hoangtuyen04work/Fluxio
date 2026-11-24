package com.example.fluxio.transfer.persistence

import com.example.fluxio.data.remote.CompletedPart
import com.example.fluxio.transfer.upload.TransferState
import com.example.fluxio.transfer.upload.TransferStore
import com.example.fluxio.transfer.upload.UploadWork
import java.util.UUID

class RoomTransferStore(
    private val dao: TransferDao,
    private val localId: UUID,
    private val now: () -> Long = System::currentTimeMillis
) : TransferStore {
    override suspend fun load(id: UUID): UploadWork {
        require(id == localId)
        val transfer = requireNotNull(dao.transfer(id.toString()))
        return UploadWork(requireNotNull(transfer.remoteUploadId), transfer.totalBytes, transfer.partSizeBytes)
    }

    override suspend fun reconcile(parts: List<CompletedPart>) {
        dao.replaceParts(localId.toString(), parts.map(::entity))
    }

    override suspend fun confirm(part: CompletedPart) {
        dao.putPart(entity(part))
    }

    override suspend fun setState(state: TransferState) {
        dao.setState(localId.toString(), state, now())
    }

    private fun entity(part: CompletedPart) = TransferPartEntity(
        transferId = localId.toString(),
        partNumber = part.partNumber,
        offsetBytes = 0,
        sizeBytes = part.sizeBytes,
        etag = part.etag,
        checksumSha256 = part.checksumSha256,
        state = "CONFIRMED",
        attemptCount = 1,
        updatedAtEpochMillis = now()
    )
}

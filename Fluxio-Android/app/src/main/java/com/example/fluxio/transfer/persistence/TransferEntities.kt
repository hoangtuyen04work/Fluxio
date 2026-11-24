package com.example.fluxio.transfer.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.fluxio.transfer.upload.TransferState

@Entity(
    tableName = "transfers",
    indices = [Index(value = ["direction", "contentUri", "destinationUri", "state"])]
)
data class TransferEntity(
    @PrimaryKey val localId: String,
    val remoteUploadId: String?,
    val fileId: String?,
    val direction: String,
    val contentUri: String?,
    val destinationUri: String?,
    val displayName: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val partSizeBytes: Long,
    val objectVersion: String?,
    val state: TransferState,
    val retryCount: Int,
    val nextRetryAtEpochMillis: Long?,
    val uriPermissionPersisted: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "transfer_parts",
    primaryKeys = ["transferId", "partNumber"],
    foreignKeys = [ForeignKey(
        entity = TransferEntity::class,
        parentColumns = ["localId"],
        childColumns = ["transferId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("transferId")]
)
data class TransferPartEntity(
    val transferId: String,
    val partNumber: Int,
    val offsetBytes: Long,
    val sizeBytes: Long,
    val etag: String?,
    val checksumSha256: String?,
    val state: String,
    val attemptCount: Int,
    val updatedAtEpochMillis: Long
)

class TransferStateConverter {
    @TypeConverter fun fromState(state: TransferState): String = state.name
    @TypeConverter fun toState(value: String): TransferState = TransferState.valueOf(value)
}

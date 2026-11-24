package com.example.fluxio.transfer.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.fluxio.transfer.upload.TransferState

@Dao
interface TransferDao {
    @Query("select * from transfers where localId = :id")
    suspend fun transfer(id: String): TransferEntity?

    @Query("select * from transfer_parts where transferId = :id order by partNumber")
    suspend fun parts(id: String): List<TransferPartEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putTransfer(transfer: TransferEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPart(part: TransferPartEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putParts(parts: List<TransferPartEntity>)

    @Query("delete from transfer_parts where transferId = :id")
    suspend fun deleteParts(id: String)

    @Query("update transfers set state = :state, updatedAtEpochMillis = :now where localId = :id")
    suspend fun setState(id: String, state: TransferState, now: Long)

    @Transaction
    suspend fun replaceParts(id: String, parts: List<TransferPartEntity>) {
        deleteParts(id)
        putParts(parts)
    }
}

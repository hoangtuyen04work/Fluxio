package com.example.fluxio.transfer.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [TransferEntity::class, TransferPartEntity::class], version = 1, exportSchema = true)
@TypeConverters(TransferStateConverter::class)
abstract class TransferDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao
}

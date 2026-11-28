package com.example.fluxio.transfer.persistence

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.AutoMigration

@Database(
    entities = [TransferEntity::class, TransferPartEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
@TypeConverters(TransferStateConverter::class)
abstract class TransferDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao
}

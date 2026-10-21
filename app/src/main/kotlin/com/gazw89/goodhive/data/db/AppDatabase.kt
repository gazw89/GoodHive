package com.gazw89.goodhive.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        HeatingZoneCacheEntity::class,
        PendingCommandEntity::class,
        CommandLogEntity::class,
        RoomOrderEntity::class,
        HistoryReadingEntity::class,
        HistorySyncEntity::class,
        ZoneStateLogEntity::class,
        ScheduleSnapshotEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun heatingZoneCacheDao(): HeatingZoneCacheDao
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun commandLogDao(): CommandLogDao
    abstract fun roomOrderDao(): RoomOrderDao
    abstract fun historyDao(): HistoryDao
    abstract fun zoneStateDao(): ZoneStateDao
}

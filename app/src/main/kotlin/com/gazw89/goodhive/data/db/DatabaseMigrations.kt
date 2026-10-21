package com.gazw89.goodhive.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve all operational data from every shipped development schema. */
object DatabaseMigrations {
    /** Declared before [ALL]: object initialisers run in order. */
    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE heating_zone_cache ADD COLUMN historySourceIdsJson TEXT NOT NULL DEFAULT '[]'")
        }
    }

    /** Reports: local history store and state log. Frozen DDL. */
    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `history_reading` (`sourceId` TEXT NOT NULL, `epochMs` INTEGER NOT NULL, `tempC` REAL NOT NULL, PRIMARY KEY(`sourceId`, `epochMs`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `history_sync` (`sourceId` TEXT NOT NULL, `oldestMs` INTEGER, `newestMs` INTEGER, `backfillDone` INTEGER NOT NULL, `lastAttemptMs` INTEGER, `lastError` TEXT, PRIMARY KEY(`sourceId`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `zone_state_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `zoneId` TEXT NOT NULL, `epochMs` INTEGER NOT NULL, `mode` TEXT NOT NULL, `targetC` REAL, `scheduleOverride` INTEGER, `boostActive` INTEGER NOT NULL, `hodActive` INTEGER NOT NULL, `working` INTEGER, `online` INTEGER NOT NULL, `battery` INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_zone_state_log_zoneId_epochMs` ON `zone_state_log` (`zoneId`, `epochMs`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `schedule_snapshot` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `zoneId` TEXT NOT NULL, `fromMs` INTEGER NOT NULL, `scheduleJson` TEXT NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_schedule_snapshot_zoneId_fromMs` ON `schedule_snapshot` (`zoneId`, `fromMs`)")
        }
    }

    /** Drops every table that is not part of schema 11, left behind by removed features. */
    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val existing = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            existing.filter { it !in TABLES_V11 }.forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }
        }
    }

    /** Frozen version-11 table set, including Room's own bookkeeping tables. */
    internal val TABLES_V11 = setOf(
        "heating_zone_cache", "pending_command", "command_log", "room_order",
        "history_reading", "history_sync", "zone_state_log", "schedule_snapshot",
        "android_metadata", "room_master_table",
    )

    val ALL: Array<Migration> = (1..7).map<Int, Migration> { from ->
        object : Migration(from, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existing = db.query("PRAGMA table_info(heating_zone_cache)").use { cursor ->
                    buildSet {
                        val nameColumn = cursor.getColumnIndexOrThrow("name")
                        while (cursor.moveToNext()) add(cursor.getString(nameColumn))
                    }
                }
                db.execSQL(CREATE_CACHE_V8)
                val source = CACHE_COLUMNS_V8.joinToString(", ") { column ->
                    if (column in existing) "`$column`" else when (column) {
                        "reportedByHive" -> "1"
                        "heatOnDemandAvailable" -> "0"
                        "autoBoostTrvIdsJson" -> "'[]'"
                        else -> "NULL"
                    }
                }
                val destination = CACHE_COLUMNS_V8.joinToString(", ") { "`$it`" }
                db.execSQL("INSERT INTO heating_zone_cache_migrated ($destination) SELECT $source FROM heating_zone_cache")
                db.execSQL("DROP TABLE heating_zone_cache")
                db.execSQL("ALTER TABLE heating_zone_cache_migrated RENAME TO heating_zone_cache")
                db.execSQL("CREATE TABLE IF NOT EXISTS room_order (zoneId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(zoneId))")
                // Explicit user decision: removed reporting must not keep collecting/storing temperatures.
                db.execSQL("DROP TABLE IF EXISTS heating_sample")
            }
        }
    }.toTypedArray() + MIGRATION_8_9 + MIGRATION_9_10 + MIGRATION_10_11

    // Frozen version-8 definitions: future entity edits must not alter these migration contracts.
    private val CACHE_COLUMNS_V8 = listOf(
        "id",
        "productType",
        "name",
        "online",
        "currentTemperatureC",
        "targetTemperatureC",
        "mode",
        "frostProtectionC",
        "maxEventsPerDay",
        "scheduleJson",
        "heatOnDemand",
        "heatOnDemandAvailable",
        "hiveZoneId",
        "batteryLevel",
        "working",
        "autoBoostActive",
        "autoBoostTrvIdsJson",
        "previousMode",
        "previousTargetC",
        "boostMinutesLeft",
        "lastSyncedAtEpochMillis",
        "reportedByHive",
    )
    private const val CREATE_CACHE_V8 = "CREATE TABLE IF NOT EXISTS `heating_zone_cache_migrated` (`id` TEXT NOT NULL, `productType` TEXT NOT NULL, `name` TEXT NOT NULL, `online` INTEGER NOT NULL, `currentTemperatureC` REAL, `targetTemperatureC` REAL, `mode` TEXT NOT NULL, `frostProtectionC` REAL NOT NULL, `maxEventsPerDay` INTEGER NOT NULL, `scheduleJson` TEXT NOT NULL, `heatOnDemand` TEXT, `heatOnDemandAvailable` INTEGER NOT NULL, `hiveZoneId` TEXT, `batteryLevel` INTEGER, `working` INTEGER, `autoBoostActive` INTEGER, `autoBoostTrvIdsJson` TEXT NOT NULL, `previousMode` TEXT, `previousTargetC` REAL, `boostMinutesLeft` INTEGER, `lastSyncedAtEpochMillis` INTEGER NOT NULL, `reportedByHive` INTEGER NOT NULL, PRIMARY KEY(`id`))"
}

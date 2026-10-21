package com.gazw89.goodhive.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Operates only on disposable fixture databases; never opens goodhive.db or contacts Hive. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test fun version1PreservesOperationalData() = migrate(1)
    @Test fun version2PreservesOperationalData() = migrate(2)
    @Test fun version3PreservesOperationalData() = migrate(3)
    @Test fun version4PreservesOperationalData() = migrate(4)
    @Test fun version5PreservesOperationalData() = migrate(5)
    @Test fun version6PreservesOperationalData() = migrate(6)
    @Test fun version7PreservesOperationalData() = migrate(7)
    // Versions 1..7 above also exercise the full chain through 8, 9 and 10 to 11.
    @Test fun version8PreservesOperationalData() = migrate(8)

    /** The v9 snapshot is the real starting point of MIGRATION_9_10, so it gets its own fixture. */
    @Test fun version9GainsReportTablesAndKeepsOperationalData() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "goodhive-migration-test-9"
        context.deleteDatabase(name)
        val entities = JSONObject(instrumentation.context.assets.open("com.gazw89.goodhive.data.db.AppDatabase/9.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        context.getDatabasePath(name).parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { legacy ->
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            legacy.execSQL("INSERT INTO heating_zone_cache (id, productType, name, online, mode, frostProtectionC, maxEventsPerDay, scheduleJson, heatOnDemandAvailable, autoBoostTrvIdsJson, historySourceIdsJson, lastSyncedAtEpochMillis, reportedByHive) VALUES ('fixture-zone', 'trvcontrol', 'Fixture room', 1, 'MANUAL', 7.0, 6, '{\"days\":{}}', 0, '[]', '[\"trv-device\"]', 123456, 1)")
            legacy.execSQL("INSERT INTO pending_command (commandId, zoneId, zoneName, productType, description, payloadJson, expectedStateJson, status, createdAtEpochMillis, attemptCount) VALUES (41, 'fixture-zone', 'Fixture room', 'trvcontrol', 'Fixture command', '{\"target\":21}', '{\"target\":\"21.0\"}', 'PENDING', 123456, 2)")
            legacy.version = 9
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*DatabaseMigrations.ALL).build()
        try {
            // Room validates the current schema (tables, columns, indices) while opening.
            migrated.openHelper.writableDatabase
            assertEquals("[\"trv-device\"]", migrated.heatingZoneCacheDao().getById("fixture-zone")!!.historySourceIdsJson)
            assertEquals(CommandStatus.PENDING, migrated.pendingCommandDao().getById(41)?.status)

            val history = migrated.historyDao()
            history.commitChunk(
                listOf(HistoryReadingEntity("trv-device", 1_000, 19.0), HistoryReadingEntity("trv-device", 1_000, 19.5)),
                HistorySyncEntity("trv-device", oldestMs = 1_000, newestMs = 2_000, backfillDone = false),
            )
            assertEquals(listOf(19.5), history.readings("trv-device", 0, 5_000).map { it.tempC })
            assertEquals(1_000L, history.getSync("trv-device")!!.oldestMs)
            val zoneState = migrated.zoneStateDao()
            zoneState.insertState(ZoneStateLogEntity(zoneId = "fixture-zone", epochMs = 1, mode = "MANUAL", targetC = 20.0, scheduleOverride = null, boostActive = false, hodActive = false, working = null, online = true, battery = 80))
            zoneState.insertSchedule(ScheduleSnapshotEntity(zoneId = "fixture-zone", fromMs = 1, scheduleJson = "{}"))
            assertEquals(1, zoneState.latestStatePerZone().size)
            assertEquals(1, zoneState.latestSchedulePerZone().size)
            assertOnlyCurrentTables(migrated.openHelper.writableDatabase)
            history.clearHistory()
            zoneState.clearLog()
            assertTrue(history.getAllSync().isEmpty() && zoneState.latestStatePerZone().isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    private fun migrate(version: Int) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "goodhive-migration-test-$version"
        context.deleteDatabase(name)
        val schema = JSONObject(instrumentation.context.assets.open("com.gazw89.goodhive.data.db.AppDatabase/${if (version >= 8) 8 else 7}.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        val values = linkedMapOf<String, Any?>(
            "id" to "fixture-zone", "productType" to "trvcontrol", "name" to "Fixture room", "online" to 1,
            "currentTemperatureC" to 19.0, "targetTemperatureC" to 20.0, "mode" to "MANUAL",
            "frostProtectionC" to 7.0, "maxEventsPerDay" to 6, "scheduleJson" to "{\"days\":{}}",
            "heatOnDemand" to "ENABLED", "heatOnDemandAvailable" to 1, "hiveZoneId" to "fixture-link",
            "batteryLevel" to 77, "working" to 1, "autoBoostActive" to 1,
            "autoBoostTrvIdsJson" to "[\"fixture-zone\"]", "previousMode" to "SCHEDULE",
            "previousTargetC" to 19.5, "boostMinutesLeft" to 15, "lastSyncedAtEpochMillis" to 123456L,
        )
        val introduced = mapOf(
            "heatOnDemand" to 2, "heatOnDemandAvailable" to 4, "hiveZoneId" to 5, "batteryLevel" to 6,
            "working" to 7, "autoBoostActive" to 7, "autoBoostTrvIdsJson" to 7,
            "previousMode" to 7, "previousTargetC" to 7, "boostMinutesLeft" to 7,
        )
        // Recreate the legacy column additions from repository history. Version 3 covers
        // the intermediate room-order shape between the archived version-2 and version-4 snapshots.
        val legacyValues = values.filterKeys { (introduced[it] ?: 1) <= version } +
            // Present only in the version-8 snapshot (NOT NULL, no default).
            (if (version >= 8) mapOf("reportedByHive" to 1) else emptyMap())
        context.getDatabasePath(name).parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { legacy ->
            for (i in 0 until schema.length()) {
                val entity = schema.getJSONObject(i)
                val table = entity.getString("tableName")
                if (table == "room_order" && version < 3) continue
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            }
            introduced.filter { it.value > version }.keys.forEach { legacy.execSQL("ALTER TABLE heating_zone_cache DROP COLUMN `$it`") }
            val columns = legacyValues.keys.joinToString(",") { "`$it`" }
            val placeholders = legacyValues.keys.joinToString(",") { "?" }
            legacy.execSQL("INSERT INTO heating_zone_cache ($columns) VALUES ($placeholders)", legacyValues.values.toTypedArray())
            if (version < 8) legacy.execSQL("INSERT INTO heating_sample (sampleId, zoneId, zoneName, timestampEpochMillis, currentTemperatureC, targetTemperatureC, mode, online) VALUES (1, 'fixture-zone', 'Fixture room', 123456, 19, 20, 'MANUAL', 1)")
            legacy.execSQL("INSERT INTO pending_command (commandId, zoneId, zoneName, productType, description, payloadJson, expectedStateJson, status, createdAtEpochMillis, attemptCount) VALUES (41, 'fixture-zone', 'Fixture room', 'trvcontrol', 'Fixture command', '{\"target\":21}', '{\"target\":\"21.0\"}', 'PENDING', 123456, 2)")
            legacy.execSQL("INSERT INTO command_log (logId, zoneId, zoneName, description, outcome, createdAtEpochMillis, resolvedAtEpochMillis, attemptCount) VALUES (51, 'fixture-zone', 'Fixture room', 'Fixture failure', 'FAILED', 123456, 123457, 3)")
            if (version >= 3) legacy.execSQL("INSERT INTO room_order (zoneId, position) VALUES ('fixture-zone', 4)")
            legacy.version = version
        }
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*DatabaseMigrations.ALL).build()
        try {
            // Room validates the resulting schema while opening: a mismatched migration fails here.
            val db = migrated.openHelper.writableDatabase
            val cached = migrated.heatingZoneCacheDao().getById("fixture-zone")!!
            assertEquals("Fixture room", cached.name)
            assertEquals(20.0, cached.targetTemperatureC!!, 0.0)
            assertEquals(123456L, cached.lastSyncedAtEpochMillis)
            assertTrue(cached.reportedByHive)
            assertEquals(if (version >= 2) "ENABLED" else null, cached.heatOnDemand)
            assertEquals(version >= 4, cached.heatOnDemandAvailable)
            assertEquals(if (version >= 5) "fixture-link" else null, cached.hiveZoneId)
            assertEquals(if (version >= 6) 77 else null, cached.batteryLevel)
            assertEquals(if (version >= 7) "[\"fixture-zone\"]" else "[]", cached.autoBoostTrvIdsJson)
            assertEquals(if (version >= 7) "SCHEDULE" else null, cached.previousMode)
            // Added by MIGRATION_8_9: every legacy row gets the empty-list default.
            assertEquals("[]", cached.historySourceIdsJson)
            // Added by MIGRATION_9_10: the report tables exist and are empty.
            for (table in listOf("history_reading", "history_sync", "zone_state_log", "schedule_snapshot")) {
                db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(table, 0, it.getInt(0)) }
            }
            assertOnlyCurrentTables(db)
            assertEquals(CommandStatus.PENDING, migrated.pendingCommandDao().getById(41)?.status)
            assertEquals(2, migrated.pendingCommandDao().getById(41)?.attemptCount)
            db.query("SELECT COUNT(*) FROM command_log WHERE logId=51 AND outcome='FAILED'").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM room_order").use { assertTrue(it.moveToFirst()); assertEquals(if (version >= 3) 1 else 0, it.getInt(0)) }
            // Explicit removal commits cancellation + failure dismissal with cache removal.
            migrated.heatingZoneCacheDao().applySnapshot(emptyList(), emptyList())
            assertNotNull(migrated.heatingZoneCacheDao().getById("fixture-zone"))
            assertTrue(migrated.heatingZoneCacheDao().removeUnreported("fixture-zone"))
            assertNull(migrated.heatingZoneCacheDao().getById("fixture-zone"))
            assertEquals(CommandStatus.SUPERSEDED, migrated.pendingCommandDao().getById(41)?.status)
            db.query("SELECT outcome FROM command_log WHERE logId=51").use { assertTrue(it.moveToFirst()); assertEquals("DISMISSED", it.getString(0)) }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    /** Room bookkeeping and app tables only; anything from removed features (e.g. heating_sample) is gone. */
    private fun assertOnlyCurrentTables(db: SupportSQLiteDatabase) {
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        assertEquals(DatabaseMigrations.TABLES_V11, tables)
    }
}

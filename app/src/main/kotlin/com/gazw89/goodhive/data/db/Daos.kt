package com.gazw89.goodhive.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HeatingZoneCacheDao {
    @Query("SELECT * FROM heating_zone_cache ORDER BY name")
    fun observeAll(): Flow<List<HeatingZoneCacheEntity>>

    @Query("SELECT * FROM heating_zone_cache WHERE id = :id")
    suspend fun getById(id: String): HeatingZoneCacheEntity?

    @Query("SELECT * FROM heating_zone_cache")
    suspend fun getAll(): List<HeatingZoneCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(zones: List<HeatingZoneCacheEntity>)

    @Query("UPDATE heating_zone_cache SET reportedByHive = 0")
    suspend fun markAllUnreported()

    @Query("UPDATE heating_zone_cache SET reportedByHive = 1 WHERE id IN (:ids)")
    suspend fun markReported(ids: List<String>)

    /** Missing products retain their saved details until the user explicitly removes them. */
    @Transaction
    suspend fun applySnapshot(zones: List<HeatingZoneCacheEntity>, reportedIds: List<String>) {
        markAllUnreported()
        markReported(reportedIds)
        upsertAll(zones)
    }

    @Query("DELETE FROM heating_zone_cache WHERE id = :id AND reportedByHive = 0")
    suspend fun deleteUnreported(id: String): Int

    @Query("UPDATE pending_command SET status = 'SUPERSEDED' WHERE zoneId = :id AND status IN ('PENDING', 'SENDING', 'CONFIRMING')")
    suspend fun cancelCommandsForRemovedDevice(id: String)

    @Query("UPDATE command_log SET outcome = 'DISMISSED' WHERE zoneId = :id AND outcome = 'FAILED'")
    suspend fun dismissRemovedDeviceFailures(id: String)

    @Transaction
    suspend fun removeUnreported(id: String): Boolean {
        if (deleteUnreported(id) == 0) return false
        cancelCommandsForRemovedDevice(id)
        dismissRemovedDeviceFailures(id)
        return true
    }
}

@Dao
interface PendingCommandDao {
    @Insert
    suspend fun insert(command: PendingCommandEntity): Long

    @Update
    suspend fun update(command: PendingCommandEntity)

    @Query("SELECT * FROM pending_command WHERE commandId = :commandId LIMIT 1")
    suspend fun getById(commandId: Long): PendingCommandEntity?

    @Query("SELECT * FROM pending_command WHERE commandId = :commandId")
    fun observeById(commandId: Long): Flow<PendingCommandEntity?>

    @Query("SELECT * FROM pending_command WHERE status IN (:statuses) ORDER BY commandId")
    fun observeWithStatuses(statuses: List<String>): Flow<List<PendingCommandEntity>>

    @Query("UPDATE pending_command SET status = 'SUPERSEDED' WHERE commandId IN (:ids) AND status IN (:activeStatuses)")
    suspend fun supersede(ids: List<Long>, activeStatuses: List<String>)

    @Transaction
    suspend fun insertSuperseding(
        command: PendingCommandEntity,
        supersededIds: List<Long>,
        activeStatuses: List<String>,
    ): Long {
        val id = insert(command)
        supersede(supersededIds, activeStatuses)
        return id
    }

    @Query(
        "UPDATE pending_command SET status = :status, attemptCount = :attemptCount, " +
            "lastAttemptAtEpochMillis = :lastAttemptAt WHERE commandId = :commandId " +
            "AND status IN ('PENDING', 'SENDING', 'CONFIRMING')",
    )
    suspend fun advanceIfActive(commandId: Long, status: String, attemptCount: Int, lastAttemptAt: Long?): Int

    @Query(
        "INSERT INTO command_log (zoneId, zoneName, description, outcome, createdAtEpochMillis, " +
            "resolvedAtEpochMillis, attemptCount) SELECT zoneId, zoneName, description || :suffix, " +
            ":outcome, createdAtEpochMillis, :now, attemptCount FROM pending_command " +
            "WHERE commandId = :commandId AND status IN ('PENDING', 'SENDING', 'CONFIRMING')",
    )
    suspend fun logIfActive(commandId: Long, outcome: String, suffix: String, now: Long): Long

    /** Outcome and its UI notice commit together; a superseded command cannot be resurrected. */
    @Transaction
    suspend fun finishIfActive(commandId: Long, outcome: String, suffix: String, now: Long) {
        val command = getById(commandId) ?: return
        if (command.status !in listOf(CommandStatus.PENDING, CommandStatus.SENDING, CommandStatus.CONFIRMING)) return
        logIfActive(commandId, outcome, suffix, now)
        advanceIfActive(commandId, outcome, command.attemptCount, command.lastAttemptAtEpochMillis)
    }

    @Query("SELECT * FROM pending_command WHERE status IN (:statuses) ORDER BY createdAtEpochMillis")
    suspend fun withStatuses(statuses: List<String>): List<PendingCommandEntity>

    @Query(
        "SELECT * FROM pending_command WHERE zoneId = :zoneId AND status IN (:statuses) " +
            "ORDER BY commandId DESC LIMIT 1",
    )
    suspend fun latestForZone(zoneId: String, statuses: List<String>): PendingCommandEntity?

    @Query(
        "SELECT * FROM pending_command WHERE zoneId = :zoneId AND status = :status " +
            "ORDER BY commandId DESC LIMIT 1",
    )
    suspend fun latestWithStatus(zoneId: String, status: String): PendingCommandEntity?

    @Query("SELECT * FROM pending_command ORDER BY createdAtEpochMillis DESC")
    fun observeAll(): Flow<List<PendingCommandEntity>>

    @Query(
        "UPDATE pending_command SET status = :newStatus WHERE zoneId = :zoneId AND status IN (:activeStatuses) " +
            "AND commandId != :exceptCommandId",
    )
    suspend fun supersedeActiveForZone(zoneId: String, exceptCommandId: Long, newStatus: String, activeStatuses: List<String>)
}

@Dao
interface CommandLogDao {
    @Insert
    suspend fun insert(entry: CommandLogEntity)

    @Query("SELECT * FROM command_log ORDER BY createdAtEpochMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<CommandLogEntity>>

    @Query("SELECT * FROM command_log WHERE outcome = 'FAILED' ORDER BY createdAtEpochMillis DESC")
    fun observeUnresolvedFailures(): Flow<List<CommandLogEntity>>

    /** Soft-ack so the detail banner stays gone after Dismiss (and across process death). */
    @Query("UPDATE command_log SET outcome = 'DISMISSED' WHERE zoneId = :zoneId AND outcome = 'FAILED'")
    suspend fun dismissFailuresForZone(zoneId: String)
}

@Dao
interface RoomOrderDao {
    @Query("SELECT * FROM room_order")
    fun observeAll(): Flow<List<RoomOrderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(orders: List<RoomOrderEntity>)
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadings(readings: List<HistoryReadingEntity>)

    @Query("SELECT * FROM history_sync WHERE sourceId = :sourceId")
    suspend fun getSync(sourceId: String): HistorySyncEntity?

    @Query("SELECT * FROM history_sync")
    suspend fun getAllSync(): List<HistorySyncEntity>

    @Query("SELECT * FROM history_sync")
    fun observeAllSync(): Flow<List<HistorySyncEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSync(sync: HistorySyncEntity)

    /** A chunk's readings and the sync bounds that cover them commit together, so a killed job resumes cleanly. */
    @Transaction
    suspend fun commitChunk(readings: List<HistoryReadingEntity>, sync: HistorySyncEntity) {
        upsertReadings(readings)
        upsertSync(sync)
    }

    @Query(
        "SELECT * FROM history_reading WHERE sourceId = :sourceId AND epochMs >= :fromMs AND epochMs < :toMs " +
            "ORDER BY epochMs",
    )
    suspend fun readings(sourceId: String, fromMs: Long, toMs: Long): List<HistoryReadingEntity>

    @Query(
        "SELECT * FROM history_reading WHERE sourceId IN (:sourceIds) AND epochMs >= :fromMs AND epochMs < :toMs " +
            "ORDER BY epochMs",
    )
    fun observeReadings(sourceIds: List<String>, fromMs: Long, toMs: Long): Flow<List<HistoryReadingEntity>>

    @Query("SELECT COUNT(*) FROM history_reading")
    fun observeReadingCount(): Flow<Int>

    @Query("DELETE FROM history_reading")
    suspend fun deleteAllReadings()

    @Query("DELETE FROM history_sync")
    suspend fun deleteAllSync()

    @Transaction
    suspend fun clearHistory() {
        deleteAllReadings()
        deleteAllSync()
    }
}

@Dao
interface ZoneStateDao {
    @Insert
    suspend fun insertState(entry: ZoneStateLogEntity)

    @Insert
    suspend fun insertSchedule(snapshot: ScheduleSnapshotEntity)

    @Query("SELECT * FROM zone_state_log WHERE id IN (SELECT MAX(id) FROM zone_state_log GROUP BY zoneId)")
    suspend fun latestStatePerZone(): List<ZoneStateLogEntity>

    @Query("SELECT * FROM schedule_snapshot WHERE id IN (SELECT MAX(id) FROM schedule_snapshot GROUP BY zoneId)")
    suspend fun latestSchedulePerZone(): List<ScheduleSnapshotEntity>

    @Query("SELECT * FROM zone_state_log WHERE zoneId = :zoneId AND epochMs >= :fromMs AND epochMs < :toMs ORDER BY epochMs")
    suspend fun stateLog(zoneId: String, fromMs: Long, toMs: Long): List<ZoneStateLogEntity>

    @Query("SELECT * FROM schedule_snapshot WHERE zoneId = :zoneId ORDER BY fromMs")
    suspend fun schedules(zoneId: String): List<ScheduleSnapshotEntity>

    @Query("SELECT * FROM zone_state_log WHERE zoneId = :zoneId ORDER BY epochMs")
    fun observeStateLog(zoneId: String): Flow<List<ZoneStateLogEntity>>

    @Query("SELECT * FROM schedule_snapshot WHERE zoneId = :zoneId ORDER BY fromMs")
    fun observeSchedules(zoneId: String): Flow<List<ScheduleSnapshotEntity>>

    @Query("DELETE FROM zone_state_log")
    suspend fun deleteAllState()

    @Query("DELETE FROM schedule_snapshot")
    suspend fun deleteAllSchedules()

    @Transaction
    suspend fun clearLog() {
        deleteAllState()
        deleteAllSchedules()
    }
}

package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.network.toHeatingZones

import android.util.Log
import com.gazw89.goodhive.auth.AuthRepository
import com.gazw89.goodhive.data.db.*
import com.gazw89.goodhive.data.network.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.mockito.MockedStatic
import org.mockito.Mockito

/** All device traffic in these tests terminates here; no real account or network is used. */
internal class FakeHiveApi : HiveApiService {
    var getCount = 0
    val writes = mutableListOf<Pair<String, JsonObject>>()
    var state = buildJsonObject { put("name", "Room"); put("mode", "MANUAL"); put("target", 18.0) }
    var get: suspend () -> HiveNodesResponse = { snapshot() }
    data class HistoryCall(
        val token: String, val id: String, val start: Long, val end: Long,
        val timeUnit: String, val rate: Int, val operation: String,
    )
    val historyCalls = mutableListOf<HistoryCall>()
    var history: suspend (HistoryCall) -> HiveHistoryResponse = { HiveHistoryResponse() }
    var set: suspend (String, JsonObject) -> Unit = { _, payload -> state = JsonObject(state + payload) }
    fun snapshot() = HiveNodesResponse(products = listOf(HiveNode("zone", "trvcontrol", state = state)))
    override suspend fun getAllNodes(token: String, products: Boolean, devices: Boolean, actions: Boolean): HiveNodesResponse {
        check(!actions) { "Unused actions should not be downloaded" }
        getCount++
        return get()
    }
    override suspend fun getTemperatureHistory(
        token: String, id: String, start: Long, end: Long, timeUnit: String, rate: Int, operation: String,
    ): HiveHistoryResponse {
        historyCalls += HistoryCall(token, id, start, end, timeUnit, rate, operation)
        return history(historyCalls.last())
    }
    override suspend fun setState(token: String, type: String, id: String, body: JsonObject) {
        writes += id to body
        set(id, body)
    }
}

internal class FakeZoneDao : HeatingZoneCacheDao {
    val rows = MutableStateFlow<List<HeatingZoneCacheEntity>>(emptyList())
    override fun observeAll() = rows
    override suspend fun getById(id: String) = rows.value.find { it.id == id }
    override suspend fun getAll() = rows.value
    override suspend fun markAllUnreported() { rows.value = rows.value.map { it.copy(reportedByHive = false) } }
    override suspend fun markReported(ids: List<String>) { rows.value = rows.value.map { if (it.id in ids) it.copy(reportedByHive = true) else it } }
    override suspend fun deleteUnreported(id: String): Int {
        val old = rows.value.size
        rows.value = rows.value.filterNot { it.id == id && !it.reportedByHive }
        return old - rows.value.size
    }
    override suspend fun cancelCommandsForRemovedDevice(id: String) = Unit
    override suspend fun dismissRemovedDeviceFailures(id: String) = Unit
    override suspend fun upsertAll(zones: List<HeatingZoneCacheEntity>) {
        rows.value = (rows.value.associateBy { it.id } + zones.associateBy { it.id }).values.toList()
    }
}

internal class FakePendingDao : PendingCommandDao {
    val rows = MutableStateFlow<List<PendingCommandEntity>>(emptyList())
    val logs = mutableListOf<CommandLogEntity>()
    private var sequence = 0L
    private val active = setOf(CommandStatus.PENDING, CommandStatus.SENDING, CommandStatus.CONFIRMING)
    override suspend fun insert(command: PendingCommandEntity): Long {
        val id = ++sequence
        rows.value += command.copy(commandId = id)
        return id
    }
    override suspend fun update(command: PendingCommandEntity) { rows.value = rows.value.map { if (it.commandId == command.commandId) command else it } }
    override suspend fun getById(commandId: Long) = rows.value.find { it.commandId == commandId }
    override fun observeById(commandId: Long) = rows.map { it.find { row -> row.commandId == commandId } }
    override fun observeWithStatuses(statuses: List<String>) = rows.map { it.filter { row -> row.status in statuses } }
    override suspend fun withStatuses(statuses: List<String>) = rows.value.filter { it.status in statuses }
    override suspend fun latestForZone(zoneId: String, statuses: List<String>) = rows.value.lastOrNull { it.zoneId == zoneId && it.status in statuses }
    override suspend fun latestWithStatus(zoneId: String, status: String) = rows.value.lastOrNull { it.zoneId == zoneId && it.status == status }
    override fun observeAll() = rows
    override suspend fun supersedeActiveForZone(zoneId: String, exceptCommandId: Long, newStatus: String, activeStatuses: List<String>) {
        rows.value = rows.value.map { if (it.zoneId == zoneId && it.commandId != exceptCommandId && it.status in activeStatuses) it.copy(status = newStatus) else it }
    }
    override suspend fun supersede(ids: List<Long>, activeStatuses: List<String>) {
        rows.value = rows.value.map { if (it.commandId in ids && it.status in activeStatuses) it.copy(status = CommandStatus.SUPERSEDED) else it }
    }
    override suspend fun advanceIfActive(commandId: Long, status: String, attemptCount: Int, lastAttemptAt: Long?): Int {
        val row = getById(commandId)?.takeIf { it.status in active } ?: return 0
        update(row.copy(status = status, attemptCount = attemptCount, lastAttemptAtEpochMillis = lastAttemptAt))
        return 1
    }
    override suspend fun logIfActive(commandId: Long, outcome: String, suffix: String, now: Long): Long {
        val row = getById(commandId)?.takeIf { it.status in active } ?: return -1
        logs += CommandLogEntity(zoneId = row.zoneId, zoneName = row.zoneName, description = row.description + suffix,
            outcome = outcome, createdAtEpochMillis = row.createdAtEpochMillis, resolvedAtEpochMillis = now, attemptCount = row.attemptCount)
        return logs.size.toLong()
    }
}

internal class FakeLogDao : CommandLogDao {
    val rows = MutableStateFlow<List<CommandLogEntity>>(emptyList())
    override suspend fun insert(entry: CommandLogEntity) { rows.value += entry }
    override fun observeRecent(limit: Int) = rows.map { it.takeLast(limit).reversed() }
    override fun observeUnresolvedFailures() = rows.map { it.filter { row -> row.outcome == "FAILED" }.reversed() }
    override suspend fun dismissFailuresForZone(zoneId: String) {
        rows.value = rows.value.map { if (it.zoneId == zoneId && it.outcome == "FAILED") it.copy(outcome = "DISMISSED") else it }
    }
}

internal class FakeHistoryDao : HistoryDao {
    val readings = MutableStateFlow<Map<Pair<String, Long>, HistoryReadingEntity>>(emptyMap())
    val sync = MutableStateFlow<Map<String, HistorySyncEntity>>(emptyMap())
    override suspend fun upsertReadings(readings: List<HistoryReadingEntity>) {
        this.readings.value = this.readings.value + readings.associateBy { it.sourceId to it.epochMs }
    }
    override suspend fun getSync(sourceId: String) = sync.value[sourceId]
    override suspend fun getAllSync() = sync.value.values.toList()
    override fun observeAllSync() = sync.map { it.values.toList() }
    override suspend fun upsertSync(sync: HistorySyncEntity) { this.sync.value = this.sync.value + (sync.sourceId to sync) }
    override suspend fun readings(sourceId: String, fromMs: Long, toMs: Long) =
        readings.value.values.filter { it.sourceId == sourceId && it.epochMs >= fromMs && it.epochMs < toMs }.sortedBy { it.epochMs }
    override fun observeReadings(sourceIds: List<String>, fromMs: Long, toMs: Long): Flow<List<HistoryReadingEntity>> =
        readings.map { all -> all.values.filter { it.sourceId in sourceIds && it.epochMs >= fromMs && it.epochMs < toMs }.sortedBy { it.epochMs } }
    override fun observeReadingCount() = readings.map { it.size }
    override suspend fun deleteAllReadings() { readings.value = emptyMap() }
    override suspend fun deleteAllSync() { sync.value = emptyMap() }
}

internal class FakeZoneStateDao : ZoneStateDao {
    val states = mutableListOf<ZoneStateLogEntity>()
    val schedules = mutableListOf<ScheduleSnapshotEntity>()
    private var sequence = 0L
    private val revision = MutableStateFlow(0L)
    override suspend fun insertState(entry: ZoneStateLogEntity) { states += entry.copy(id = ++sequence); revision.value++ }
    override suspend fun insertSchedule(snapshot: ScheduleSnapshotEntity) { schedules += snapshot.copy(id = ++sequence); revision.value++ }
    override suspend fun latestStatePerZone() = states.groupBy { it.zoneId }.map { (_, rows) -> rows.maxBy { it.id } }
    override suspend fun latestSchedulePerZone() = schedules.groupBy { it.zoneId }.map { (_, rows) -> rows.maxBy { it.id } }
    override suspend fun stateLog(zoneId: String, fromMs: Long, toMs: Long) =
        states.filter { it.zoneId == zoneId && it.epochMs >= fromMs && it.epochMs < toMs }.sortedBy { it.epochMs }
    override suspend fun schedules(zoneId: String) = schedules.filter { it.zoneId == zoneId }.sortedBy { it.fromMs }
    override fun observeStateLog(zoneId: String) = revision.map { states.filter { it.zoneId == zoneId }.sortedBy { it.epochMs } }
    override fun observeSchedules(zoneId: String) = revision.map { schedules.filter { it.zoneId == zoneId }.sortedBy { it.fromMs } }
    override suspend fun deleteAllState() { states.clear(); revision.value++ }
    override suspend fun deleteAllSchedules() { schedules.clear(); revision.value++ }
}

open class RepositoryTestBase {
    private lateinit var logging: MockedStatic<Log>
    @Before fun silenceAndroidLogging() { logging = Mockito.mockStatic(Log::class.java) }
    @After fun closeAndroidLogging() { logging.close() }
    internal val json = Json { ignoreUnknownKeys = true }
    internal val api = FakeHiveApi()
    internal val zones = FakeZoneDao()
    internal val pending = FakePendingDao()
    internal val auth: AuthRepository = Mockito.mock(AuthRepository::class.java)
    internal val stateDao = FakeZoneStateDao()
    internal val stateLogger = ZoneStateLogger(stateDao, json)
    internal val repository = HeatingRepository(HiveRequestRunner(auth), api, zones, json, stateLogger)
    internal suspend fun configureToken() {
        Mockito.`when`(auth.currentIdToken()).thenReturn("test-token")
        // User commands originate from a room in the account cache, never an arbitrary product id.
        zones.upsertAll(api.snapshot().toHeatingZones().map { it.toCacheEntity(0L, json) })
    }
}

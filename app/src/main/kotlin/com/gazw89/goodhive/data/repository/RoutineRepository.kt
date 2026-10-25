package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.db.RoutineDao
import com.gazw89.goodhive.data.db.RoutineEntity
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.routines.Routine
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.data.model.routines.morningBoostRoutine
import com.gazw89.goodhive.data.prefs.RoutinePreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Routines are stored on this phone only and cleared with the rest of the account on sign-out. */
@Singleton
class RoutineRepository @Inject constructor(
    private val dao: RoutineDao,
    private val preferences: RoutinePreferences,
) {
    private val seedMutex = Mutex()

    fun observeRoutines(): Flow<List<Routine>> = dao.observeAll().map { rows -> rows.map { it.toRoutine() } }

    suspend fun get(id: Long): Routine? = dao.getById(id)?.toRoutine()

    /** Inserts a new routine (id 0) at the end of the dashboard row, or updates an existing one. Returns its id. */
    suspend fun save(routine: Routine): Long {
        val existing = if (routine.id == 0L) null else dao.getById(routine.id)
        if (existing == null) {
            return dao.insert(
                RoutineEntity(
                    name = routine.name,
                    iconKey = routine.iconKey,
                    position = dao.nextPosition(),
                    stepsJson = encodeSteps(routine.steps),
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        }
        dao.update(existing.copy(name = routine.name, iconKey = routine.iconKey, stepsJson = encodeSteps(routine.steps)))
        return existing.id
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun reorder(ids: List<Long>) = dao.reorder(ids)

    /** Creates the starter "Morning boost" once per account, the first time its rooms are known. */
    suspend fun seedDefaultsIfNeeded(zones: List<HeatingZone>) = seedMutex.withLock {
        if (preferences.isSeeded()) return@withLock
        val starter = morningBoostRoutine(zones) ?: return@withLock
        if (dao.count() == 0) save(starter)
        preferences.setSeeded(true)
    }

    private fun RoutineEntity.toRoutine() = Routine(
        id = id,
        name = name,
        iconKey = iconKey,
        position = position,
        steps = decodeSteps(stepsJson),
    )

    companion object {
        internal val routineJson = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
        private val stepsSerializer = ListSerializer(RoutineStep.serializer())

        internal fun encodeSteps(steps: List<RoutineStep>): String = routineJson.encodeToString(stepsSerializer, steps)

        /** A routine whose steps can't be read (e.g. a step type from a newer build) opens with no steps. */
        internal fun decodeSteps(raw: String): List<RoutineStep> =
            runCatching { routineJson.decodeFromString(stepsSerializer, raw) }.getOrDefault(emptyList())
    }
}

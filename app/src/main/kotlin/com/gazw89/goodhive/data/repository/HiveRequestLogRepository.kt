package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.db.HiveRequestLogDao
import com.gazw89.goodhive.data.db.HiveRequestLogEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** On-device Beekeeper write log. Inserts never block the request that produced them. */
@Singleton
class HiveRequestLogRepository @Inject constructor(
    private val dao: HiveRequestLogDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { runCatchingLog { dao.deleteOlderThan(cutoff()) } }
    }

    fun observeAll(): Flow<List<HiveRequestLogEntity>> = dao.observeAll()

    fun observeById(id: Long): Flow<HiveRequestLogEntity?> = dao.observeById(id)

    fun record(entry: HiveRequestLogEntity) {
        scope.launch {
            runCatchingLog {
                dao.insert(entry)
                dao.deleteOlderThan(cutoff())
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { dao.deleteAll() }
    }

    private fun cutoff(): Long = System.currentTimeMillis() - RETENTION_MS

    private suspend inline fun runCatchingLog(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A logging failure must not surface on the heating write.
        }
    }

    companion object {
        const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}

package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.auth.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException

/** Runs a Beekeeper request with the current id token, refreshing and retrying once on HTTP 401. */
@Singleton
class HiveRequestRunner @Inject constructor(
    private val authRepository: AuthRepository,
) {
    suspend fun <T> call(request: suspend (token: String) -> T): T {
        val token = authRepository.currentIdToken()
        return try {
            request(token)
        } catch (err: HttpException) {
            // 403 is also used by Hive's CDN: do not turn its backoff into a login storm.
            if (err.code() != 401) throw err
            request(authRepository.refreshAfterRejection(token))
        }
    }
}

package com.gazw89.goodhive.data.network

import com.gazw89.goodhive.data.db.HiveRequestLogEntity
import com.gazw89.goodhive.data.repository.HiveRequestLogRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Records Beekeeper POSTs after [sanitizeHiveBody]. Logcat stays at BASIC in NetworkModule.
 * A failure here is swallowed so a heating write still completes.
 */
@Singleton
class HiveWriteLoggingInterceptor @Inject constructor(
    private val log: HiveRequestLogRepository,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!shouldLogHiveWrite(request.method, request.url.host)) return chain.proceed(request)

        val startedAt = System.currentTimeMillis()
        val startNanos = System.nanoTime()
        val path = hiveRequestPath(request.url.encodedPath, request.url.encodedQuery)
        val requestBody = runCatching { sanitizeHiveBody(readRequestBody(request)) }.getOrDefault("")
        return try {
            val response = chain.proceed(request)
            record(
                startedAt = startedAt,
                durationMs = elapsedMs(startNanos),
                method = request.method,
                path = path,
                requestBody = requestBody,
                responseCode = response.code,
                responseBody = runCatching {
                    sanitizeHiveBody(response.peekBody(HIVE_WRITE_BODY_CAP.toLong()).string())
                }.getOrDefault(""),
                error = null,
            )
            response
        } catch (io: IOException) {
            record(
                startedAt = startedAt,
                durationMs = elapsedMs(startNanos),
                method = request.method,
                path = path,
                requestBody = requestBody,
                responseCode = null,
                responseBody = "",
                error = sanitizeHiveBody(io.message.orEmpty()).ifBlank { io.javaClass.simpleName },
            )
            throw io
        }
    }

    private fun record(
        startedAt: Long,
        durationMs: Long,
        method: String,
        path: String,
        requestBody: String,
        responseCode: Int?,
        responseBody: String,
        error: String?,
    ) {
        runCatching {
            log.record(
                HiveRequestLogEntity(
                    startedAtEpochMillis = startedAt,
                    method = method,
                    path = path,
                    requestBody = requestBody,
                    responseCode = responseCode,
                    durationMs = durationMs,
                    responseBody = responseBody,
                    error = error,
                ),
            )
        }
    }

    private fun readRequestBody(request: okhttp3.Request): String {
        val body = request.body ?: return ""
        if (body.isDuplex() || body.isOneShot()) return ""
        val buffer = Buffer()
        body.writeTo(buffer)
        return buffer.readUtf8()
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}

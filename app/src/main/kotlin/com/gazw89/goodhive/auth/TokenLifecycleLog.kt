package com.gazw89.goodhive.auth

import android.util.Base64
import android.util.Log
import com.gazw89.goodhive.util.GoodHiveTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/**
 * Structured, secret-free logging for Cognito token lifetimes.
 *
 * Use logcat filter: `adb logcat -s GoodHiveTokens`
 *
 * Never logs raw tokens — only lengths, fingerprints, and decoded JWT *claims*
 * from the ID token (`exp` / `iat` / `auth_time`), which are not secrets.
 */
internal object TokenLifecycleLog {
    const val TAG = "GoodHiveTokens"

    private val timeFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(GoodHiveTime.londonZone)

    fun tokensSaved(
        source: String,
        tokens: CognitoTokens,
        previousRefreshFingerprint: String?,
        refreshSessionStartedAtEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val claims = IdTokenClaims.parse(tokens.idToken)
        val idExpiresAt = claims?.expEpochMillis
            ?: (nowEpochMillis + tokens.expiresInSeconds * 1000L)
        val refreshFp = fingerprint(tokens.refreshToken)
        val refreshRotated = previousRefreshFingerprint != null &&
            refreshFp != null &&
            previousRefreshFingerprint != refreshFp
        val refreshAgeMs = if (refreshSessionStartedAtEpochMillis > 0L) {
            nowEpochMillis - refreshSessionStartedAtEpochMillis
        } else {
            -1L
        }

        Log.i(
            TAG,
            buildString {
                append("tokens_saved source=").append(source)
                append(" idExpiresInSec=").append(tokens.expiresInSeconds)
                append(" idExpiresAt=").append(format(idExpiresAt))
                append(" idExpiresInMs=").append(idExpiresAt - nowEpochMillis)
                claims?.iatEpochMillis?.let { append(" jwtIat=").append(format(it)) }
                claims?.authTimeEpochMillis?.let { append(" jwtAuthTime=").append(format(it)) }
                claims?.expEpochMillis?.let { append(" jwtExp=").append(format(it)) }
                append(" refreshPresent=").append(tokens.refreshToken.isNotBlank())
                append(" refreshLen=").append(tokens.refreshToken.length)
                append(" refreshFp=").append(refreshFp ?: "none")
                append(" refreshRotated=").append(refreshRotated)
                append(" refreshSessionAgeMs=").append(refreshAgeMs)
                if (refreshAgeMs >= 0) {
                    append(" refreshSessionAge=").append(formatDuration(refreshAgeMs))
                    append(" refreshSessionStartedAt=").append(format(refreshSessionStartedAtEpochMillis))
                }
            },
        )
    }

    fun usingCachedIdToken(
        createdAtEpochMillis: Long,
        expiresInSeconds: Long,
        shouldRefresh: Boolean,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val absoluteExpiry = if (createdAtEpochMillis > 0L && expiresInSeconds > 0L) {
            createdAtEpochMillis + expiresInSeconds * 1000L
        } else {
            -1L
        }
        Log.d(
            TAG,
            buildString {
                append("id_token_reuse")
                append(" shouldRefresh=").append(shouldRefresh)
                append(" createdAt=").append(format(createdAtEpochMillis))
                if (absoluteExpiry > 0L) {
                    append(" absoluteExpiry=").append(format(absoluteExpiry))
                    append(" remainingMs=").append(absoluteExpiry - nowEpochMillis)
                }
            },
        )
    }

    fun refreshStarting(refreshFingerprint: String?, refreshSessionAgeMs: Long) {
        Log.i(
            TAG,
            buildString {
                append("refresh_start")
                append(" refreshFp=").append(refreshFingerprint ?: "none")
                append(" refreshSessionAgeMs=").append(refreshSessionAgeMs)
                if (refreshSessionAgeMs >= 0) {
                    append(" refreshSessionAge=").append(formatDuration(refreshSessionAgeMs))
                }
            },
        )
    }

    fun refreshFailed(
        error: Throwable,
        refreshFingerprint: String?,
        refreshSessionAgeMs: Long,
    ) {
        val auth = error as? HiveAuthException
        Log.w(
            TAG,
            buildString {
                append("refresh_failed")
                append(" definiteRejection=").append(auth?.isDefiniteSessionRejection == true)
                append(" cognitoType=").append(auth?.cognitoErrorType ?: "n/a")
                append(" message=").append(error.message)
                append(" refreshFp=").append(refreshFingerprint ?: "none")
                append(" refreshSessionAgeMs=").append(refreshSessionAgeMs)
                if (refreshSessionAgeMs >= 0) {
                    append(" refreshSessionAge=").append(formatDuration(refreshSessionAgeMs))
                    append(" — if Cognito says refresh expired, this age approximates Hive's refresh lifetime")
                }
            },
        )
    }

    fun sessionCleared(reason: String, refreshSessionAgeMs: Long) {
        Log.w(
            TAG,
            buildString {
                append("session_cleared reason=").append(reason)
                append(" refreshSessionAgeMs=").append(refreshSessionAgeMs)
                if (refreshSessionAgeMs >= 0) {
                    append(" refreshSessionAge=").append(formatDuration(refreshSessionAgeMs))
                }
            },
        )
    }

    /** Stable, non-reversible-ish short id for comparing refresh tokens without logging them. */
    fun fingerprint(token: String?): String? {
        if (token.isNullOrBlank()) return null
        var h = 0x811c9dc5.toInt()
        for (c in token) {
            h = h xor c.code
            h *= 0x01000193
        }
        val unsigned = h.toLong() and 0xffffffffL
        return "%08x_len%d".format(unsigned, token.length)
    }

    fun format(epochMillis: Long): String =
        if (epochMillis <= 0L) "n/a" else timeFmt.format(Instant.ofEpochMilli(epochMillis))

    fun formatDuration(ms: Long): String {
        if (ms < 0) return "n/a"
        val totalSec = ms / 1000
        val days = totalSec / 86_400
        val hours = (totalSec % 86_400) / 3600
        val mins = (totalSec % 3600) / 60
        val secs = totalSec % 60
        return when {
            days > 0 -> "${days}d ${hours}h ${mins}m"
            hours > 0 -> "${hours}h ${mins}m ${secs}s"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }
}

/** Subset of Cognito ID-token claims useful for lifetime monitoring. */
internal data class IdTokenClaims(
    val expEpochMillis: Long?,
    val iatEpochMillis: Long?,
    val authTimeEpochMillis: Long?,
) {
    companion object {
        fun parse(idToken: String): IdTokenClaims? {
            val parts = idToken.split('.')
            if (parts.size < 2) return null
            return runCatching {
                val decoded = Base64.decode(
                    parts[1],
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                )
                val json = JSONObject(String(decoded, Charsets.UTF_8))
                IdTokenClaims(
                    expEpochMillis = json.optLongSecondsAsMillis("exp"),
                    iatEpochMillis = json.optLongSecondsAsMillis("iat"),
                    authTimeEpochMillis = json.optLongSecondsAsMillis("auth_time"),
                )
            }.getOrNull()
        }

        private fun JSONObject.optLongSecondsAsMillis(key: String): Long? {
            if (!has(key)) return null
            val seconds = optLong(key, -1L)
            return if (seconds > 0L) seconds * 1000L else null
        }
    }
}

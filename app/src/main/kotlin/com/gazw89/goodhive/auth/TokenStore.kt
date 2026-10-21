package com.gazw89.goodhive.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Keystore-backed encrypted storage for Cognito tokens and a confirmed remembered device.
 *
 * Every write here stamps [tokenCreatedAtEpochMillis] — the exact thing Pyhive's
 * `Hive.start_session()` fails to do when tokens are supplied externally (upstream bug
 * https://github.com/Pyhass/Pyhive/issues/123), which is what silently broke the spike
 * script's first run. This store makes that omission structurally impossible: there is
 * no code path that stores a token without also stamping its creation time.
 *
 * After SMS/login, [AuthRepository] calls ConfirmDevice and persists the device password
 * here. REFRESH_TOKEN_AUTH then includes DEVICE_KEY so Hive keeps the refresh token alive;
 * without that, Cognito rejects refresh after roughly one ID-token lifetime.
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "goodhive_secure_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { store ->
            // Drop unconfirmed device keys from older builds (no device password = never
            // ConfirmDevice'd). Confirmed devices keep KEY_DEVICE_PASSWORD — leave those.
            if (
                (store.contains(KEY_DEVICE_KEY) || store.contains(KEY_DEVICE_GROUP_KEY)) &&
                !store.contains(KEY_DEVICE_PASSWORD)
            ) {
                store.edit()
                    .remove(KEY_DEVICE_KEY)
                    .remove(KEY_DEVICE_GROUP_KEY)
                    .apply()
            }
        }
    }

    fun save(tokens: CognitoTokens, source: String = "unknown") {
        val now = System.currentTimeMillis()
        val previousRefreshFp = TokenLifecycleLog.fingerprint(refreshToken())
        val incomingRefresh = tokens.refreshToken
        val refreshChanged = incomingRefresh.isNotBlank() &&
            TokenLifecycleLog.fingerprint(incomingRefresh) != previousRefreshFp

        val priorCreatedAt = tokenCreatedAtEpochMillis()
        val priorSource = lastTokenSource()
        val priorExpiresInSec = expiresInSeconds()
        val priorRefreshStartedAt = refreshSessionStartedAtEpochMillis()
        val history = tokenHistory().toMutableList()

        if (priorCreatedAt > 0L) {
            history.addCompleted(
                TokenHistoryEntry(
                    obtainedAtEpochMillis = priorCreatedAt,
                    replacedAtEpochMillis = now,
                    livedMs = (now - priorCreatedAt).coerceAtLeast(0L),
                    configuredLifetimeSeconds = priorExpiresInSec,
                    source = priorSource ?: "unknown",
                ),
            )
        }

        // Cognito often omits RefreshToken on REFRESH_TOKEN_AUTH — keep the existing token and
        // its session clock. Reset only when a *new* refresh token arrives (login / SMS).
        // If the clock was never set (upgrade mid-session), leave it at 0 until the next login
        // rather than pretending the session started at this refresh.
        val refreshSessionStartedAt = when {
            incomingRefresh.isNotBlank() && refreshChanged -> now
            incomingRefresh.isNotBlank() && refreshSessionStartedAtEpochMillis() == 0L -> now
            else -> refreshSessionStartedAtEpochMillis()
        }

        prefs.edit()
            .putString(KEY_ID_TOKEN, tokens.idToken)
            .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
            .apply {
                if (incomingRefresh.isNotBlank()) putString(KEY_REFRESH_TOKEN, incomingRefresh)

                // Keep single-previous fields in sync for older diagnostics callers.
                if (priorCreatedAt > 0L) {
                    putLong(KEY_PREV_ID_OBTAINED_AT, priorCreatedAt)
                    putLong(KEY_PREV_ID_REPLACED_AT, now)
                    putLong(KEY_PREV_ID_LIVED_MS, (now - priorCreatedAt).coerceAtLeast(0L))
                    putLong(KEY_PREV_ID_TTL_SEC, priorExpiresInSec)
                    putString(KEY_PREV_ID_SOURCE, priorSource.orEmpty())
                }

                if (refreshChanged && priorRefreshStartedAt > 0L) {
                    putLong(KEY_PREV_REFRESH_STARTED_AT, priorRefreshStartedAt)
                    putLong(KEY_PREV_REFRESH_ENDED_AT, now)
                    putLong(KEY_PREV_REFRESH_LIVED_MS, (now - priorRefreshStartedAt).coerceAtLeast(0L))
                }

                if (refreshSessionStartedAt > 0L) {
                    putLong(KEY_REFRESH_SESSION_STARTED_AT, refreshSessionStartedAt)
                }

                putString(KEY_TOKEN_HISTORY, encodeHistory(history))
            }
            .putLong(KEY_EXPIRES_IN_SECONDS, tokens.expiresInSeconds)
            .putLong(KEY_TOKEN_CREATED_AT, now)
            .putString(KEY_LAST_TOKEN_SOURCE, source)
            .apply()

        TokenLifecycleLog.tokensSaved(
            source = source,
            tokens = tokens,
            previousRefreshFingerprint = previousRefreshFp,
            refreshSessionStartedAtEpochMillis = refreshSessionStartedAt,
            nowEpochMillis = now,
        )
    }

    /** Persists a Cognito-confirmed remembered device (after ConfirmDevice succeeds). */
    fun saveRememberedDevice(device: RememberedDevice) {
        prefs.edit()
            .putString(KEY_DEVICE_KEY, device.deviceKey)
            .putString(KEY_DEVICE_GROUP_KEY, device.deviceGroupKey)
            .putString(KEY_DEVICE_PASSWORD, device.devicePassword)
            .putString(KEY_DEVICE_USERNAME, device.username)
            .apply()
        Log.i(
            TokenLifecycleLog.TAG,
            "remembered_device_saved deviceKeyFp=${TokenLifecycleLog.fingerprint(device.deviceKey)} usernameSet=true",
        )
    }

    fun rememberedDevice(): RememberedDevice? {
        val key = prefs.getString(KEY_DEVICE_KEY, null) ?: return null
        val group = prefs.getString(KEY_DEVICE_GROUP_KEY, null) ?: return null
        val password = prefs.getString(KEY_DEVICE_PASSWORD, null) ?: return null
        val username = prefs.getString(KEY_DEVICE_USERNAME, null) ?: return null
        if (key.isBlank() || group.isBlank() || password.isBlank() || username.isBlank()) return null
        return RememberedDevice(
            deviceKey = key,
            deviceGroupKey = group,
            devicePassword = password,
            username = username,
        )
    }

    fun clearRememberedDevice() {
        prefs.edit()
            .remove(KEY_DEVICE_KEY)
            .remove(KEY_DEVICE_GROUP_KEY)
            .remove(KEY_DEVICE_PASSWORD)
            .remove(KEY_DEVICE_USERNAME)
            .apply()
    }

    fun idToken(): String? = prefs.getString(KEY_ID_TOKEN, null)
    fun refreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun tokenCreatedAtEpochMillis(): Long = prefs.getLong(KEY_TOKEN_CREATED_AT, 0L)
    fun expiresInSeconds(): Long = prefs.getLong(KEY_EXPIRES_IN_SECONDS, 0L)
    fun lastTokenSource(): String? = prefs.getString(KEY_LAST_TOKEN_SOURCE, null)

    /** Wall clock when the currently stored refresh token was first observed (login/SMS). */
    fun refreshSessionStartedAtEpochMillis(): Long = prefs.getLong(KEY_REFRESH_SESSION_STARTED_AT, 0L)

    fun refreshSessionAgeMs(nowEpochMillis: Long = System.currentTimeMillis()): Long {
        val started = refreshSessionStartedAtEpochMillis()
        return if (started > 0L) nowEpochMillis - started else -1L
    }

    /** Newest-first completed ID-token records (max [MAX_TOKEN_HISTORY]). */
    fun tokenHistory(): List<TokenHistoryEntry> {
        val raw = prefs.getString(KEY_TOKEN_HISTORY, null)
        if (!raw.isNullOrBlank()) {
            return runCatching {
                json.decodeFromString(ListSerializer(TokenHistoryEntry.serializer()), raw)
            }.getOrDefault(emptyList())
        }
        // Migrate the old single "previous ID token" slot into the ring buffer once.
        val obtained = prefs.getLong(KEY_PREV_ID_OBTAINED_AT, 0L)
        val lived = prefs.getLong(KEY_PREV_ID_LIVED_MS, -1L)
        if (obtained > 0L && lived >= 0L) {
            val migrated = listOf(
                TokenHistoryEntry(
                    obtainedAtEpochMillis = obtained,
                    replacedAtEpochMillis = prefs.getLong(KEY_PREV_ID_REPLACED_AT, 0L),
                    livedMs = lived,
                    configuredLifetimeSeconds = prefs.getLong(KEY_PREV_ID_TTL_SEC, 0L),
                    source = prefs.getString(KEY_PREV_ID_SOURCE, null)?.takeIf { it.isNotBlank() }
                        ?: "unknown",
                ),
            )
            prefs.edit().putString(KEY_TOKEN_HISTORY, encodeHistory(migrated)).apply()
            return migrated
        }
        return emptyList()
    }

    /** Snapshot of session/token timing for About (no raw token material). */
    fun sessionDiagnostics(nowEpochMillis: Long = System.currentTimeMillis()): SessionDiagnostics {
        val createdAt = tokenCreatedAtEpochMillis()
        val expiresInSec = expiresInSeconds()
        val idToken = idToken()
        val claims = idToken?.let { IdTokenClaims.parse(it) }
        val absoluteExpiry = if (createdAt > 0L && expiresInSec > 0L) {
            createdAt + expiresInSec * 1000L
        } else {
            claims?.expEpochMillis ?: 0L
        }
        val history = tokenHistory()
        val prevRefreshLived = prefs.getLong(KEY_PREV_REFRESH_LIVED_MS, -1L)
        return SessionDiagnostics(
            hasIdToken = !idToken.isNullOrBlank(),
            hasRefreshToken = hasRefreshToken(),
            lastTokenSource = lastTokenSource(),
            idTokenObtainedAtEpochMillis = createdAt,
            idTokenExpiresAtEpochMillis = absoluteExpiry,
            idTokenConfiguredLifetimeSeconds = expiresInSec,
            idTokenRemainingMs = if (absoluteExpiry > 0L) absoluteExpiry - nowEpochMillis else -1L,
            idTokenAgeMs = if (createdAt > 0L) nowEpochMillis - createdAt else -1L,
            refreshSessionStartedAtEpochMillis = refreshSessionStartedAtEpochMillis(),
            refreshSessionAgeMs = refreshSessionAgeMs(nowEpochMillis),
            jwtAuthTimeEpochMillis = claims?.authTimeEpochMillis,
            jwtIatEpochMillis = claims?.iatEpochMillis,
            jwtExpEpochMillis = claims?.expEpochMillis,
            refreshFingerprint = TokenLifecycleLog.fingerprint(refreshToken()),
            tokenHistory = history,
            previousRefreshSession = PreviousRefreshSessionDiagnostics(
                startedAtEpochMillis = prefs.getLong(KEY_PREV_REFRESH_STARTED_AT, 0L),
                endedAtEpochMillis = prefs.getLong(KEY_PREV_REFRESH_ENDED_AT, 0L),
                livedMs = prevRefreshLived,
            ).takeIf { it.livedMs >= 0L && it.startedAtEpochMillis > 0L },
        )
    }

    /** True once we're within [refreshThreshold] of actual expiry — matches Hive's own 90% policy. */
    fun shouldRefresh(refreshThreshold: Double = 0.9): Boolean {
        val createdAt = tokenCreatedAtEpochMillis()
        if (createdAt == 0L) return true
        val expiresAtMillis = createdAt + (expiresInSeconds() * 1000 * refreshThreshold).toLong()
        return System.currentTimeMillis() >= expiresAtMillis
    }

    /** True when we have an ID token cached (may still be near expiry). */
    fun isLoggedIn(): Boolean = !idToken().isNullOrBlank()

    /** True when a refresh token exists — used for cold-start routing. */
    fun hasRefreshToken(): Boolean = !refreshToken().isNullOrBlank()

    /**
     * Soft reauth: drop short-lived tokens so the UI won't pretend the session is healthy,
     * but leave the refresh token in place until a successful re-login overwrites the store
     * (avoids a hard wipe that also destroys any future device-remembering state).
     */
    fun clearAccessTokens(reason: String = "soft_reauth") {
        val now = System.currentTimeMillis()
        val priorCreatedAt = tokenCreatedAtEpochMillis()
        val priorSource = lastTokenSource()
        val priorExpiresInSec = expiresInSeconds()
        val history = tokenHistory().toMutableList()
        TokenLifecycleLog.sessionCleared(reason, refreshSessionAgeMs())
        prefs.edit()
            .apply {
                if (priorCreatedAt > 0L) {
                    val entry = TokenHistoryEntry(
                        obtainedAtEpochMillis = priorCreatedAt,
                        replacedAtEpochMillis = now,
                        livedMs = (now - priorCreatedAt).coerceAtLeast(0L),
                        configuredLifetimeSeconds = priorExpiresInSec,
                        source = priorSource ?: "unknown",
                    )
                    history.addCompleted(entry)
                    putLong(KEY_PREV_ID_OBTAINED_AT, entry.obtainedAtEpochMillis)
                    putLong(KEY_PREV_ID_REPLACED_AT, entry.replacedAtEpochMillis)
                    putLong(KEY_PREV_ID_LIVED_MS, entry.livedMs)
                    putLong(KEY_PREV_ID_TTL_SEC, entry.configuredLifetimeSeconds)
                    putString(KEY_PREV_ID_SOURCE, entry.source)
                    putString(KEY_TOKEN_HISTORY, encodeHistory(history))
                }
            }
            .remove(KEY_ID_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_TOKEN_CREATED_AT)
            .remove(KEY_EXPIRES_IN_SECONDS)
            .remove(KEY_LAST_TOKEN_SOURCE)
            .apply()
    }

    fun clear(reason: String = "sign_out") {
        // Preserve token history + previous-refresh metrics across logout.
        val now = System.currentTimeMillis()
        val priorCreatedAt = tokenCreatedAtEpochMillis()
        val priorSource = lastTokenSource()
        val priorExpiresInSec = expiresInSeconds()
        val priorRefreshStartedAt = refreshSessionStartedAtEpochMillis()
        val history = tokenHistory().toMutableList()

        val archivedRefreshStarted = prefs.getLong(KEY_PREV_REFRESH_STARTED_AT, 0L)
        val archivedRefreshEnded = prefs.getLong(KEY_PREV_REFRESH_ENDED_AT, 0L)
        val archivedRefreshLived = prefs.getLong(KEY_PREV_REFRESH_LIVED_MS, -1L)

        if (priorCreatedAt > 0L) {
            history.addCompleted(
                TokenHistoryEntry(
                    obtainedAtEpochMillis = priorCreatedAt,
                    replacedAtEpochMillis = now,
                    livedMs = (now - priorCreatedAt).coerceAtLeast(0L),
                    configuredLifetimeSeconds = priorExpiresInSec,
                    source = priorSource ?: "unknown",
                ),
            )
        }

        TokenLifecycleLog.sessionCleared(reason, refreshSessionAgeMs())
        prefs.edit().clear().apply()

        prefs.edit().apply {
            if (history.isNotEmpty()) {
                putString(KEY_TOKEN_HISTORY, encodeHistory(history))
                val latest = history.first()
                putLong(KEY_PREV_ID_OBTAINED_AT, latest.obtainedAtEpochMillis)
                putLong(KEY_PREV_ID_REPLACED_AT, latest.replacedAtEpochMillis)
                putLong(KEY_PREV_ID_LIVED_MS, latest.livedMs)
                putLong(KEY_PREV_ID_TTL_SEC, latest.configuredLifetimeSeconds)
                putString(KEY_PREV_ID_SOURCE, latest.source)
            }

            if (priorRefreshStartedAt > 0L) {
                putLong(KEY_PREV_REFRESH_STARTED_AT, priorRefreshStartedAt)
                putLong(KEY_PREV_REFRESH_ENDED_AT, now)
                putLong(KEY_PREV_REFRESH_LIVED_MS, (now - priorRefreshStartedAt).coerceAtLeast(0L))
            } else if (archivedRefreshLived >= 0L && archivedRefreshStarted > 0L) {
                putLong(KEY_PREV_REFRESH_STARTED_AT, archivedRefreshStarted)
                putLong(KEY_PREV_REFRESH_ENDED_AT, archivedRefreshEnded)
                putLong(KEY_PREV_REFRESH_LIVED_MS, archivedRefreshLived)
            }
        }.apply()
    }

    private fun encodeHistory(entries: List<TokenHistoryEntry>): String =
        json.encodeToString(ListSerializer(TokenHistoryEntry.serializer()), entries.take(MAX_TOKEN_HISTORY))

    private fun MutableList<TokenHistoryEntry>.addCompleted(entry: TokenHistoryEntry) {
        add(0, entry)
        while (size > MAX_TOKEN_HISTORY) removeLast()
    }

    private companion object {
        const val MAX_TOKEN_HISTORY = 10
        const val KEY_ID_TOKEN = "id_token"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_DEVICE_GROUP_KEY = "device_group_key"
        const val KEY_DEVICE_KEY = "device_key"
        const val KEY_DEVICE_PASSWORD = "device_password"
        const val KEY_DEVICE_USERNAME = "device_username"
        const val KEY_EXPIRES_IN_SECONDS = "expires_in_seconds"
        const val KEY_TOKEN_CREATED_AT = "token_created_at"
        const val KEY_REFRESH_SESSION_STARTED_AT = "refresh_session_started_at"
        const val KEY_LAST_TOKEN_SOURCE = "last_token_source"
        const val KEY_PREV_ID_OBTAINED_AT = "prev_id_obtained_at"
        const val KEY_PREV_ID_REPLACED_AT = "prev_id_replaced_at"
        const val KEY_PREV_ID_LIVED_MS = "prev_id_lived_ms"
        const val KEY_PREV_ID_TTL_SEC = "prev_id_ttl_sec"
        const val KEY_PREV_ID_SOURCE = "prev_id_source"
        const val KEY_PREV_REFRESH_STARTED_AT = "prev_refresh_started_at"
        const val KEY_PREV_REFRESH_ENDED_AT = "prev_refresh_ended_at"
        const val KEY_PREV_REFRESH_LIVED_MS = "prev_refresh_lived_ms"
        const val KEY_TOKEN_HISTORY = "token_history_json"
    }
}

/** One completed ID-token lifetime, newest-first in [SessionDiagnostics.tokenHistory]. */
@Serializable
data class TokenHistoryEntry(
    val obtainedAtEpochMillis: Long,
    val replacedAtEpochMillis: Long,
    val livedMs: Long,
    val configuredLifetimeSeconds: Long,
    val source: String,
)

/** Secret-free session/token timing for the About screen. */
data class SessionDiagnostics(
    val hasIdToken: Boolean,
    val hasRefreshToken: Boolean,
    val lastTokenSource: String?,
    val idTokenObtainedAtEpochMillis: Long,
    val idTokenExpiresAtEpochMillis: Long,
    val idTokenConfiguredLifetimeSeconds: Long,
    val idTokenRemainingMs: Long,
    val idTokenAgeMs: Long,
    val refreshSessionStartedAtEpochMillis: Long,
    val refreshSessionAgeMs: Long,
    val jwtAuthTimeEpochMillis: Long?,
    val jwtIatEpochMillis: Long?,
    val jwtExpEpochMillis: Long?,
    val refreshFingerprint: String?,
    val tokenHistory: List<TokenHistoryEntry>,
    val previousRefreshSession: PreviousRefreshSessionDiagnostics?,
)

data class PreviousRefreshSessionDiagnostics(
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val livedMs: Long,
)

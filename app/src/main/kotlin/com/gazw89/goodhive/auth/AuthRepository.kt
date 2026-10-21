package com.gazw89.goodhive.auth

import android.util.Log
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LoginOutcome {
    data object Success : LoginOutcome
    data class SmsCodeRequired(val session: String, val username: String) : LoginOutcome
}

/**
 * Single entry point for authentication state. Wraps [CognitoSrpClient] with persistence
 * and refresh policy so callers never touch tokens directly.
 *
 * After SMS/password login, ConfirmDevice is called when Cognito returns NewDeviceMetadata
 * so REFRESH_TOKEN_AUTH (with DEVICE_KEY) stays valid beyond a single ID-token hour. If
 * refresh still fails, DEVICE_SRP_AUTH is tried before treating the session as dead.
 */
@Singleton
class AuthRepository internal constructor(
    private val cognitoClient: CognitoSrpClient,
    private val tokenStore: TokenStore,
    private val credentialStore: CredentialStore,
    private val clearAccountData: suspend () -> Unit = {},
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Inject constructor(
        cognitoClient: CognitoSrpClient,
        tokenStore: TokenStore,
        credentialStore: CredentialStore,
        cleaner: Provider<AccountDataCleaner>,
    ) : this(cognitoClient, tokenStore, credentialStore, { cleaner.get().clear() })

    private val sessionLock = Any()
    @Volatile private var sessionGeneration = 0L
    @Volatile private var cleanup: Deferred<Unit>? = null
    @Volatile private var signedOut = false

    private fun checkGeneration(generation: Long) {
        if (generation != sessionGeneration) throw CancellationException("Account session changed")
    }

    private fun requireActiveSession() {
        if (signedOut) throw HiveAuthException("Login required after sign-out")
    }

    private val mutex = Mutex()
    private var cachedConfig: HiveSsoConfig? = null

    /** Held across the SMS challenge so credentials can be saved only after full success. */
    private var pendingUsername: String? = null
    private var pendingPassword: String? = null
    private var pendingMfaSession: String? = null
    private var pendingMfaUsername: String? = null

    private suspend fun poolConfig(): HiveSsoConfig =
        cachedConfig ?: cognitoClient.discoverPoolConfig().also { cachedConfig = it }

    suspend fun login(username: String, password: String): LoginOutcome {
        // No refresh token is also the durable sign-out signal after a process restart.
        // Reauth for the same saved account retains history; a different account must wipe first.
        if ((!tokenStore.hasRefreshToken() && cleanup == null) ||
            credentialStore.username()?.equals(username, ignoreCase = true) == false
        ) signOut()
        val generation = sessionGeneration
        cleanup?.await() // A new account must never see the previous account's local data.
        return mutex.withLock {
            checkGeneration(generation)
            pendingUsername = username
            pendingPassword = password
            val config = poolConfig()
            // Reuse a confirmed remembered device for this user so the new refresh token is
            // bound to it — otherwise Hive rejects refresh after roughly one ID-token hour.
            val deviceKey = rememberedDeviceKeyFor(username)
            when (val result = cognitoClient.login(config, username, password, deviceKey)) {
                is CognitoAuthResult.Authenticated -> {
                    finishLogin(result.tokens, username, source = "login", generation = generation)
                    LoginOutcome.Success
                }
                is CognitoAuthResult.SmsMfaRequired -> {
                    synchronized(sessionLock) {
                        checkGeneration(generation)
                        pendingMfaSession = result.session
                        pendingMfaUsername = result.username
                    }
                    LoginOutcome.SmsCodeRequired(result.session, result.username)
                }
                is CognitoAuthResult.DeviceSrpRequired ->
                    throw HiveAuthException("Device SRP challenge not yet supported during password login")
            }
        }
    }

    suspend fun submitSmsCode(code: String, session: String, username: String) {
        val generation = sessionGeneration
        cleanup?.await()
        mutex.withLock {
            checkGeneration(generation)
            check(pendingMfaSession == session && pendingMfaUsername?.equals(username, ignoreCase = true) == true) {
                "SMS challenge expired — start login again"
            }
            val config = poolConfig()
            val deviceKey = rememberedDeviceKeyFor(username)
            val tokens = cognitoClient.submitSmsCode(config, code, session, username, deviceKey)
            finishLogin(tokens, username, source = "sms_mfa", generation = generation)
        }
    }

    private fun rememberedDeviceKeyFor(username: String): String? {
        val device = tokenStore.rememberedDevice() ?: return null
        return device.takeIf { it.username.equals(username, ignoreCase = true) }?.deviceKey
    }

    private suspend fun finishLogin(tokens: CognitoTokens, username: String, source: String, generation: Long) {
        synchronized(sessionLock) {
            checkGeneration(generation)
            tokenStore.save(tokens, source = source)
            persistPendingCredentials()
            signedOut = false
        }
        maybeConfirmDevice(tokens, username, generation)
    }

    private fun persistPendingCredentials() {
        val user = pendingUsername
        val pass = pendingPassword
        if (!user.isNullOrBlank() && pass != null) {
            credentialStore.save(user, pass)
        }
        pendingUsername = null
        pendingPassword = null
        pendingMfaSession = null
        pendingMfaUsername = null
    }

    /**
     * Registers this phone as a Cognito remembered device when login returns NewDeviceMetadata.
     * Without ConfirmDevice, Hive rejects refresh after ~1 ID-token lifetime.
     */
    private suspend fun maybeConfirmDevice(tokens: CognitoTokens, username: String, generation: Long) {
        val deviceKey = tokens.newDeviceKey
        val deviceGroupKey = tokens.newDeviceGroupKey
        if (deviceKey.isNullOrBlank() || deviceGroupKey.isNullOrBlank()) {
            Log.i("GoodHiveAuth", "No NewDeviceMetadata on $username login — skipping ConfirmDevice")
            return
        }
        val config = poolConfig()
        runCatching {
            val devicePassword = cognitoClient.confirmDevice(
                config = config,
                accessToken = tokens.accessToken,
                deviceKey = deviceKey,
                deviceGroupKey = deviceGroupKey,
            )
            synchronized(sessionLock) {
                checkGeneration(generation)
                tokenStore.saveRememberedDevice(
                    RememberedDevice(
                        deviceKey = deviceKey,
                        deviceGroupKey = deviceGroupKey,
                        devicePassword = devicePassword,
                        username = username,
                    ),
                )
            }
            Log.i("GoodHiveAuth", "ConfirmDevice succeeded — refresh will include DEVICE_KEY")
        }.onFailure { err ->
            if (err is CancellationException) throw err
            Log.w("GoodHiveAuth", "ConfirmDevice failed — session may need SMS again in ~1h: ${err.message}")
        }
    }

    /** Ensures a valid, non-expiring-soon ID token exists, refreshing first if needed. */
    suspend fun currentIdToken(): String = mutex.withLock {
        requireActiveSession()
        val needsRefresh = tokenStore.shouldRefresh()
        if (!needsRefresh) {
            TokenLifecycleLog.usingCachedIdToken(
                createdAtEpochMillis = tokenStore.tokenCreatedAtEpochMillis(),
                expiresInSeconds = tokenStore.expiresInSeconds(),
                shouldRefresh = false,
            )
        } else {
            refreshLocked()
        }
        tokenStore.idToken() ?: throw HiveAuthException("No ID token available — login required")
    }

    /** One refresh for a rejected token, even when several callers received HTTP 401 together. */
    suspend fun refreshAfterRejection(rejectedToken: String): String = mutex.withLock {
        requireActiveSession()
        if (tokenStore.idToken() == rejectedToken) refreshLocked()
        tokenStore.idToken() ?: throw HiveAuthException("No ID token available — login required")
    }

    private suspend fun refreshLocked() {
        val generation = sessionGeneration
        val refreshToken = tokenStore.refreshToken()
            ?: throw HiveAuthException("No refresh token available — full login required")
        val device = tokenStore.rememberedDevice()
        val refreshFp = TokenLifecycleLog.fingerprint(refreshToken)
        val sessionAge = tokenStore.refreshSessionAgeMs()
        TokenLifecycleLog.refreshStarting(refreshFp, sessionAge)
        val config = poolConfig()

        val tokens = try {
            cognitoClient.refreshTokens(config, refreshToken, deviceKey = device?.deviceKey)
        } catch (refreshError: Throwable) {
            TokenLifecycleLog.refreshFailed(refreshError, refreshFp, sessionAge)
            val auth = refreshError as? HiveAuthException
            if (device != null && auth?.isDefiniteSessionRejection == true) {
                Log.w("GoodHiveAuth", "REFRESH_TOKEN_AUTH rejected — trying DEVICE_SRP_AUTH")
                try {
                    cognitoClient.deviceSrpAuth(config, device)
                } catch (deviceError: Throwable) {
                    Log.w("GoodHiveAuth", "DEVICE_SRP_AUTH failed: ${deviceError.message}")
                    throw refreshError
                }
            } else {
                throw refreshError
            }
        }

        synchronized(sessionLock) {
            checkGeneration(generation)
            tokenStore.save(
                tokens.copy(refreshToken = tokens.refreshToken.ifBlank { refreshToken }),
                source = "refresh",
            )
        }
    }

    fun isLoggedIn(): Boolean = tokenStore.isLoggedIn()

    fun hasRefreshToken(): Boolean = tokenStore.hasRefreshToken()

    fun savedUsername(): String? = credentialStore.username()

    fun savedPassword(): String? = credentialStore.password()

    fun sessionDiagnostics(): SessionDiagnostics = tokenStore.sessionDiagnostics()

    fun hasRememberedDevice(): Boolean = tokenStore.rememberedDevice() != null

    /**
     * Soft reauth: drop short-lived access/id tokens and signal the UI to show login,
     * without wiping the encrypted credential store or doing a hard [signOut].
     * Remembered-device secrets stay so a later login can still use DEVICE_SRP if needed.
     */
    fun markSessionNeedsReauth() {
        Log.w("GoodHiveAuth", "Session needs reauth — clearing access/id tokens only")
        tokenStore.clearAccessTokens(reason = "soft_reauth")
    }

    fun signOut() {
        synchronized(sessionLock) {
            sessionGeneration++ // Invalidates any login/refresh already in flight.
            signedOut = true
            pendingUsername = null
            pendingPassword = null
            pendingMfaSession = null
            pendingMfaUsername = null
            tokenStore.clear(reason = "sign_out")
            val previousCleanup = cleanup
            cleanup = cleanupScope.async(start = CoroutineStart.LAZY) {
                previousCleanup?.join()
                // Drain auth work before cleanup, but do not hold auth's lock while draining readers.
                mutex.withLock { }
                clearAccountData()
            }.also { it.start() }
        }
        // Credentials are retained; login awaits cleanup and propagates a wipe failure (fail closed).
    }
}

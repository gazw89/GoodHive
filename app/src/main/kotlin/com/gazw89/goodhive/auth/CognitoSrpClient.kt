package com.gazw89.goodhive.auth

import java.math.BigInteger
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Cognito USER_SRP_AUTH client, talking directly to Cognito's public unauthenticated
 * Identity Provider JSON API (the same one amazon-cognito-identity-js uses) — no AWS SDK,
 * no IAM credentials required for this flow.
 *
 * Ported from Pyhive's `hive_auth_async.py`, which is the verified-working reference for
 * exactly how Hive's Cognito pool expects this handshake. See SrpCrypto.kt for the
 * underlying math.
 */
@Singleton
class CognitoSrpClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val amzJson = "application/x-amz-json-1.1".toMediaType()

    private val k: BigInteger = BigInteger(
        SrpCrypto.hexHash(SrpCrypto.padHex(SrpCrypto.N) + SrpCrypto.padHex(SrpCrypto.G)),
        16,
    )

    /** Scrapes https://sso.hivehome.com/ for the Cognito pool/client IDs Hive currently uses. */
    suspend fun discoverPoolConfig(): HiveSsoConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("https://sso.hivehome.com/").get().build()
        val body = okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw HiveAuthException("SSO page fetch failed: HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

        fun extract(varName: String): String {
            val regex = Regex("""$varName\s*=\s*["']([^"']+)["']""")
            return regex.find(body)?.groupValues?.get(1)
                ?: throw HiveAuthException("Could not find $varName on Hive SSO page — layout may have changed")
        }

        val poolId = extract("HiveSSOPoolId")
        val clientId = extract("HiveSSOPublicCognitoClientId")
        HiveSsoConfig(userPoolId = poolId, clientId = clientId, region = poolId.substringBefore("_"))
    }

    /**
     * Step 1 of USER_SRP_AUTH: send SRP_A, get back either a PASSWORD_VERIFIER challenge or
     * (rarely) direct tokens. When [deviceKey] is a previously confirmed remembered device,
     * Cognito associates this login with that device so the new refresh token stays valid
     * across ID-token cycles (without it, Hive often rejects refresh after ~1 hour).
     */
    suspend fun login(
        config: HiveSsoConfig,
        username: String,
        password: String,
        deviceKey: String? = null,
    ): CognitoAuthResult {
        val smallA = SrpCrypto.getRandom(128).mod(SrpCrypto.N)
        val largeA = SrpCrypto.G.modPow(smallA, SrpCrypto.N)

        val initParams = buildMap {
            put("USERNAME", username)
            put("SRP_A", largeA.toString(16))
            if (!deviceKey.isNullOrBlank()) put("DEVICE_KEY", deviceKey)
        }

        val initResponse = callCognito(
            config.region,
            "AWSCognitoIdentityProviderService.InitiateAuth",
            InitiateAuthRequest(
                authFlow = "USER_SRP_AUTH",
                clientId = config.clientId,
                authParameters = initParams,
            ).let { json.encodeToString(InitiateAuthRequest.serializer(), it) },
        )

        val auth = json.decodeFromString(CognitoAuthResponse.serializer(), initResponse)
        auth.authenticationResult?.let { return CognitoAuthResult.Authenticated(it.toTokens()) }

        if (auth.challengeName != "PASSWORD_VERIFIER") {
            throw HiveAuthException("Unsupported initial challenge: ${auth.challengeName}")
        }
        val params = auth.challengeParameters
            ?: throw HiveAuthException("PASSWORD_VERIFIER challenge missing parameters")

        val userId = params.getValue("USER_ID_FOR_SRP")
        val saltHex = params.getValue("SALT")
        val srpBHex = params.getValue("SRP_B")
        val secretBlockB64 = params.getValue("SECRET_BLOCK")

        val challengeResponse = buildPasswordClaim(
            poolId = config.userPoolId,
            userId = userId,
            password = password,
            smallA = smallA,
            largeA = largeA,
            saltHex = saltHex,
            srpBHex = srpBHex,
            secretBlockB64 = secretBlockB64,
        ).toMutableMap()
        if (!deviceKey.isNullOrBlank()) challengeResponse["DEVICE_KEY"] = deviceKey

        val challengeResult = callCognito(
            config.region,
            "AWSCognitoIdentityProviderService.RespondToAuthChallenge",
            json.encodeToString(
                RespondToAuthChallengeRequest.serializer(),
                RespondToAuthChallengeRequest(
                    clientId = config.clientId,
                    challengeName = "PASSWORD_VERIFIER",
                    challengeResponses = challengeResponse,
                    session = auth.session,
                ),
            ),
        )

        val challengeAuth = json.decodeFromString(CognitoAuthResponse.serializer(), challengeResult)
        challengeAuth.authenticationResult?.let { return CognitoAuthResult.Authenticated(it.toTokens()) }

        if (challengeAuth.challengeName == "SMS_MFA") {
            val session = challengeAuth.session
                ?: throw HiveAuthException("SMS_MFA challenge missing session")
            return CognitoAuthResult.SmsMfaRequired(session = session, username = userId)
        }

        throw HiveAuthException("Unsupported post-password challenge: ${challengeAuth.challengeName}")
    }

    /** Step 2 (when required): submit the SMS code Hive texted to the user's phone. */
    suspend fun submitSmsCode(
        config: HiveSsoConfig,
        code: String,
        session: String,
        username: String,
        deviceKey: String? = null,
    ): CognitoTokens {
        val challengeResponses = buildMap {
            put("SMS_MFA_CODE", code)
            put("USERNAME", username)
            if (!deviceKey.isNullOrBlank()) put("DEVICE_KEY", deviceKey)
        }
        val result = callCognito(
            config.region,
            "AWSCognitoIdentityProviderService.RespondToAuthChallenge",
            json.encodeToString(
                RespondToAuthChallengeRequest.serializer(),
                RespondToAuthChallengeRequest(
                    clientId = config.clientId,
                    challengeName = "SMS_MFA",
                    session = session,
                    challengeResponses = challengeResponses,
                ),
            ),
        )
        val auth = json.decodeFromString(CognitoAuthResponse.serializer(), result)
        return auth.authenticationResult?.toTokens()
            ?: throw HiveAuthException("SMS 2FA did not return tokens (challenge: ${auth.challengeName})")
    }

    /**
     * Refreshes tokens using REFRESH_TOKEN_AUTH. When [deviceKey] is a *confirmed* remembered
     * device, Cognito keeps the refresh token alive across ID-token cycles; without it Hive's
     * pool rejects the refresh after roughly one ID-token lifetime ("Invalid Refresh Token").
     */
    suspend fun refreshTokens(
        config: HiveSsoConfig,
        refreshToken: String,
        deviceKey: String? = null,
    ): CognitoTokens {
        val authParameters = buildMap {
            put("REFRESH_TOKEN", refreshToken)
            if (!deviceKey.isNullOrBlank()) put("DEVICE_KEY", deviceKey)
        }
        val result = callCognito(
            config.region,
            "AWSCognitoIdentityProviderService.InitiateAuth",
            json.encodeToString(
                InitiateAuthRequest.serializer(),
                InitiateAuthRequest(
                    authFlow = "REFRESH_TOKEN_AUTH",
                    clientId = config.clientId,
                    authParameters = authParameters,
                ),
            ),
        )
        val auth = json.decodeFromString(CognitoAuthResponse.serializer(), result)
        return auth.authenticationResult?.toTokens()
            ?: throw HiveAuthException("Refresh did not return tokens (challenge: ${auth.challengeName})")
    }

    /**
     * Registers this install as a Cognito remembered device so subsequent REFRESH_TOKEN_AUTH
     * (with DEVICE_KEY) and DEVICE_SRP_AUTH stay valid. Mirrors Netlify `confirmDevice`.
     */
    suspend fun confirmDevice(
        config: HiveSsoConfig,
        accessToken: String,
        deviceKey: String,
        deviceGroupKey: String,
        deviceName: String = "GoodHive Android",
    ): String {
        val verifier = SrpCrypto.generateDeviceVerifier(deviceGroupKey, deviceKey)
        val confirmBody = callCognito(
            config.region,
            "AWSCognitoIdentityProviderService.ConfirmDevice",
            json.encodeToString(
                ConfirmDeviceRequest.serializer(),
                ConfirmDeviceRequest(
                    accessToken = accessToken,
                    deviceKey = deviceKey,
                    deviceName = deviceName,
                    deviceSecretVerifierConfig = DeviceSecretVerifierConfig(
                        passwordVerifier = verifier.passwordVerifierB64,
                        salt = verifier.saltB64,
                    ),
                ),
            ),
        )
        val confirm = json.decodeFromString(ConfirmDeviceResponse.serializer(), confirmBody)
        if (confirm.userConfirmationNecessary) {
            callCognito(
                config.region,
                "AWSCognitoIdentityProviderService.UpdateDeviceStatus",
                json.encodeToString(
                    UpdateDeviceStatusRequest.serializer(),
                    UpdateDeviceStatusRequest(
                        accessToken = accessToken,
                        deviceKey = deviceKey,
                        deviceRememberedStatus = "remembered",
                    ),
                ),
            )
        }
        return verifier.devicePassword
    }

    /**
     * Silent re-auth using a confirmed remembered device — fallback when REFRESH_TOKEN_AUTH
     * fails but device secrets are still valid (no SMS needed).
     */
    suspend fun deviceSrpAuth(
        config: HiveSsoConfig,
        device: RememberedDevice,
    ): CognitoTokens {
        val smallA = SrpCrypto.getRandom(128).mod(SrpCrypto.N)
        val largeA = SrpCrypto.G.modPow(smallA, SrpCrypto.N)

        val initResponse = callCognito(
            config.region,
            "AWSCognitoIdentityProviderService.InitiateAuth",
            json.encodeToString(
                InitiateAuthRequest.serializer(),
                InitiateAuthRequest(
                    authFlow = "DEVICE_SRP_AUTH",
                    clientId = config.clientId,
                    authParameters = mapOf(
                        "USERNAME" to device.username,
                        "DEVICE_KEY" to device.deviceKey,
                        "SRP_A" to largeA.toString(16),
                    ),
                ),
            ),
        )
        val init = json.decodeFromString(CognitoAuthResponse.serializer(), initResponse)
        init.authenticationResult?.let { return it.toTokens() }

        if (init.challengeName != "DEVICE_PASSWORD_VERIFIER") {
            throw HiveAuthException("Unexpected DEVICE_SRP challenge: ${init.challengeName}")
        }
        val params = init.challengeParameters
            ?: throw HiveAuthException("DEVICE_PASSWORD_VERIFIER missing parameters")

        // Device SRP reuses the password-claim math with poolName=deviceGroupKey and
        // userId=deviceKey (amazon-cognito-identity-js / Netlify buildDevicePasswordClaim).
        val claim = buildPasswordClaim(
            poolId = "_${device.deviceGroupKey}",
            userId = device.deviceKey,
            password = device.devicePassword,
            smallA = smallA,
            largeA = largeA,
            saltHex = params.getValue("SALT"),
            srpBHex = params.getValue("SRP_B"),
            secretBlockB64 = params.getValue("SECRET_BLOCK"),
        )
        val challengeResponses = claim + mapOf(
            "USERNAME" to (params["USERNAME"] ?: device.username),
            "DEVICE_KEY" to device.deviceKey,
        )

        val challengeResult = callCognito(
            config.region,
            "AWSCognitoIdentityProviderService.RespondToAuthChallenge",
            json.encodeToString(
                RespondToAuthChallengeRequest.serializer(),
                RespondToAuthChallengeRequest(
                    clientId = config.clientId,
                    challengeName = "DEVICE_PASSWORD_VERIFIER",
                    challengeResponses = challengeResponses,
                    session = init.session,
                ),
            ),
        )
        val challenge = json.decodeFromString(CognitoAuthResponse.serializer(), challengeResult)
        return challenge.authenticationResult?.toTokens()
            ?: throw HiveAuthException(
                "DEVICE_SRP did not return tokens (challenge: ${challenge.challengeName})",
            )
    }

    private fun AuthenticationResult.toTokens(): CognitoTokens = CognitoTokens(
        idToken = idToken ?: throw HiveAuthException("Missing IdToken in AuthenticationResult"),
        accessToken = accessToken ?: throw HiveAuthException("Missing AccessToken in AuthenticationResult"),
        refreshToken = refreshToken.orEmpty(),
        expiresInSeconds = expiresIn,
        newDeviceGroupKey = newDeviceMetadata?.deviceGroupKey,
        newDeviceKey = newDeviceMetadata?.deviceKey,
    )

    /**
     * Computes the PASSWORD_VERIFIER challenge response: the SRP password proof plus the
     * timestamp-signed PASSWORD_CLAIM_SIGNATURE, exactly matching Cognito's expected format.
     */
    private fun buildPasswordClaim(
        poolId: String,
        userId: String,
        password: String,
        smallA: BigInteger,
        largeA: BigInteger,
        saltHex: String,
        srpBHex: String,
        secretBlockB64: String,
    ): Map<String, String> {
        val serverB = BigInteger(srpBHex, 16)
        val uValue = SrpCrypto.calculateU(largeA, serverB)
        require(uValue != BigInteger.ZERO) { "U cannot be zero" }

        val poolIdSuffix = poolId.substringAfter("_")
        val usernamePasswordHash = SrpCrypto.hashSha256("$poolIdSuffix$userId:$password".toByteArray(Charsets.UTF_8))
        val xValue = BigInteger(SrpCrypto.hexHash(SrpCrypto.padHex(saltHex) + usernamePasswordHash), 16)

        val gModPowX = SrpCrypto.G.modPow(xValue, SrpCrypto.N)
        val base = serverB.subtract(k.multiply(gModPowX)).mod(SrpCrypto.N)
        val exponent = smallA.add(uValue.multiply(xValue))
        val sValue = base.modPow(exponent, SrpCrypto.N)

        val hkdf = SrpCrypto.computeHkdf(
            SrpCrypto.hexStringToBytes(SrpCrypto.padHex(sValue)),
            SrpCrypto.hexStringToBytes(SrpCrypto.padHex(uValue)),
        )

        // Cognito requires this exact "Wed Jan 1 21:49:31 UTC 2026" style timestamp — a single
        // 'd' pattern letter in java.time already yields an unpadded day, so no regex hack needed.
        val timestamp = ZonedDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss 'UTC' yyyy", Locale.ENGLISH))

        val secretBlockBytes = Base64.getDecoder().decode(secretBlockB64)
        val msg = poolIdSuffix.toByteArray(Charsets.UTF_8) +
            userId.toByteArray(Charsets.UTF_8) +
            secretBlockBytes +
            timestamp.toByteArray(Charsets.UTF_8)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hkdf, "HmacSHA256"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(msg))

        return mapOf(
            "TIMESTAMP" to timestamp,
            "USERNAME" to userId,
            "PASSWORD_CLAIM_SECRET_BLOCK" to secretBlockB64,
            "PASSWORD_CLAIM_SIGNATURE" to signature,
        )
    }

    private suspend fun callCognito(region: String, target: String, jsonBody: String): String =
        withContext(Dispatchers.IO) {
            callCognitoBlocking(region, target, jsonBody)
        }

    private fun callCognitoBlocking(region: String, target: String, jsonBody: String): String {
        val request = Request.Builder()
            .url("https://cognito-idp.$region.amazonaws.com/")
            .addHeader("X-Amz-Target", target)
            .addHeader("Content-Type", "application/x-amz-json-1.1")
            .post(jsonBody.toRequestBody(amzJson))
            .build()

        okHttpClient.newCall(request).execute().use { resp: Response ->
            val bodyString = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val error = runCatching {
                    json.decodeFromString(CognitoErrorResponse.serializer(), bodyString)
                }.getOrNull()
                val normalizedType = HiveAuthException.normalizeCognitoType(error?.type)
                val message = error?.message ?: "Cognito request failed: HTTP ${resp.code}"
                android.util.Log.w(
                    "GoodHiveAuth",
                    "Cognito failure target=$target http=${resp.code} type=$normalizedType message=$message",
                )
                throw HiveAuthException(
                    message = message,
                    cognitoErrorType = normalizedType,
                    httpStatusCode = resp.code,
                )
            }
            return bodyString
        }
    }
}

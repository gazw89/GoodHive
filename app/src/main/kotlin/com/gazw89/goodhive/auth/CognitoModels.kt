package com.gazw89.goodhive.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Pool/client config scraped from https://sso.hivehome.com/ at runtime (Hive-controlled, not hardcoded). */
data class HiveSsoConfig(
    val userPoolId: String,
    val clientId: String,
    val region: String,
)

/** Result of a Cognito auth step: either full tokens, or a challenge that needs another round-trip. */
sealed interface CognitoAuthResult {
    data class Authenticated(val tokens: CognitoTokens) : CognitoAuthResult
    data class SmsMfaRequired(val session: String, val username: String) : CognitoAuthResult
    data class DeviceSrpRequired(
        val session: String,
        val srpB: String,
        val salt: String,
        val secretBlock: String,
    ) : CognitoAuthResult
}

data class CognitoTokens(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    /** Present on first login/SMS when Cognito wants this client to ConfirmDevice. */
    val newDeviceGroupKey: String? = null,
    val newDeviceKey: String? = null,
)

/** Confirmed remembered-device secrets — required for long-lived REFRESH_TOKEN_AUTH. */
data class RememberedDevice(
    val deviceKey: String,
    val deviceGroupKey: String,
    val devicePassword: String,
    val username: String,
)

@Serializable
data class InitiateAuthRequest(
    @SerialName("AuthFlow") val authFlow: String,
    @SerialName("ClientId") val clientId: String,
    @SerialName("AuthParameters") val authParameters: Map<String, String>,
)

@Serializable
data class RespondToAuthChallengeRequest(
    @SerialName("ClientId") val clientId: String,
    @SerialName("ChallengeName") val challengeName: String,
    @SerialName("ChallengeResponses") val challengeResponses: Map<String, String>,
    @SerialName("Session") val session: String? = null,
)

@Serializable
data class CognitoAuthResponse(
    @SerialName("AuthenticationResult") val authenticationResult: AuthenticationResult? = null,
    @SerialName("ChallengeName") val challengeName: String? = null,
    @SerialName("ChallengeParameters") val challengeParameters: Map<String, String>? = null,
    @SerialName("Session") val session: String? = null,
)

@Serializable
data class AuthenticationResult(
    @SerialName("IdToken") val idToken: String? = null,
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("RefreshToken") val refreshToken: String? = null,
    @SerialName("ExpiresIn") val expiresIn: Long = 0,
    @SerialName("NewDeviceMetadata") val newDeviceMetadata: NewDeviceMetadata? = null,
)

@Serializable
data class NewDeviceMetadata(
    @SerialName("DeviceGroupKey") val deviceGroupKey: String? = null,
    @SerialName("DeviceKey") val deviceKey: String? = null,
)

@Serializable
data class ConfirmDeviceRequest(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("DeviceKey") val deviceKey: String,
    @SerialName("DeviceName") val deviceName: String,
    @SerialName("DeviceSecretVerifierConfig") val deviceSecretVerifierConfig: DeviceSecretVerifierConfig,
)

@Serializable
data class DeviceSecretVerifierConfig(
    @SerialName("PasswordVerifier") val passwordVerifier: String,
    @SerialName("Salt") val salt: String,
)

@Serializable
data class ConfirmDeviceResponse(
    @SerialName("UserConfirmationNecessary") val userConfirmationNecessary: Boolean = false,
)

@Serializable
data class UpdateDeviceStatusRequest(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("DeviceKey") val deviceKey: String,
    @SerialName("DeviceRememberedStatus") val deviceRememberedStatus: String,
)

@Serializable
data class CognitoErrorResponse(
    @SerialName("__type") val type: String? = null,
    @SerialName("message") val message: String? = null,
)

class HiveAuthException(
    message: String,
    cognitoErrorType: String? = null,
    val httpStatusCode: Int? = null,
) : Exception(message) {

    /** Short Cognito exception name, e.g. `NotAuthorizedException` (namespace stripped). */
    val cognitoErrorType: String? = normalizeCognitoType(cognitoErrorType)

    /**
     * True only when Cognito (or local token state) has definitively rejected the session —
     * the refresh token is actually dead/revoked, or there was never one stored to begin with.
     * False for anything transient hitting the refresh call (throttling, a 5xx, a parse hiccup,
     * a network blip) — those should be retried later, not treated as "sign the user out and
     * force a full password+SMS re-login".
     *
     * "No ID token available" is intentionally *not* definite: soft reauth clears the ID
     * token while keeping the refresh token, and a follow-up refresh must be allowed.
     */
    val isDefiniteSessionRejection: Boolean
        get() {
            if (cognitoErrorType in DEFINITE_REJECTION_TYPES) return true
            val msg = message.orEmpty()
            return msg.contains("no refresh token", ignoreCase = true) ||
                msg.contains("invalid refresh token", ignoreCase = true) ||
                msg.contains("refresh token has been revoked", ignoreCase = true)
        }

    companion object {
        private val DEFINITE_REJECTION_TYPES = setOf(
            "NotAuthorizedException",
            "UserNotFoundException",
            "UserNotConfirmedException",
            "PasswordResetRequiredException",
        )

        /**
         * Cognito sometimes returns `__type` as a bare name and sometimes as a URL/ARN-ish
         * suffix (`…#NotAuthorizedException`). Always reduce to the short class name.
         */
        fun normalizeCognitoType(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return raw.substringAfterLast('#').substringAfterLast('/').takeIf { it.isNotBlank() }
        }
    }
}

class HiveSmsCodeRequiredException(val session: String, val username: String) : Exception("SMS 2FA code required")

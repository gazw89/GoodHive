package com.gazw89.goodhive.auth

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure SRP-6a / HKDF crypto for AWS Cognito USER_SRP_AUTH.
 *
 * Ported line-for-line from Pyhive's verified `srp_crypto.py` (itself a port of
 * amazon-cognito-identity-js's AuthenticationHelper.js) — same N/G group parameters,
 * same padding rules. Do not "simplify" the padding logic; Cognito's server-side
 * signature check is byte-exact against this specific hex-padding scheme.
 */
object SrpCrypto {

    // Standard 3072-bit SRP group used by Cognito (identical to amazon-cognito-identity-js).
    val N: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
            "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
            "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
            "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
            "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
            "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
        16,
    )
    val G: BigInteger = BigInteger.valueOf(2)

    private val INFO_BITS = "Caldera Derived Key".toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    /** Unsigned random BigInteger from `bytesLen` random bytes (matches Python's os.urandom + hex_to_long). */
    fun getRandom(bytesLen: Int): BigInteger {
        val bytes = ByteArray(bytesLen)
        secureRandom.nextBytes(bytes)
        return BigInteger(1, bytes)
    }

    /** SHA-256 hex digest, left-padded to 64 chars (matches Python's hash_sha256). */
    fun hashSha256(buf: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(buf)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.padStart(64, '0')
    }

    /**
     * SHA-256 of the raw bytes represented by an *already even-length* hex string.
     * Matches Python's `hex_hash`, which does NOT re-pad its input — callers are
     * responsible for padding each component before concatenating.
     */
    fun hexHash(hexString: String): String = hashSha256(hexStringToBytes(hexString))

    fun padHex(value: BigInteger): String = padHex(value.toString(16))

    /** Matches Python's pad_hex: even-length, and a leading zero byte if the first nibble is 8-F. */
    fun padHex(hexIn: String): String {
        var hashStr = hexIn
        if (hashStr.length % 2 == 1) {
            hashStr = "0$hashStr"
        } else if (hashStr.isNotEmpty() && hashStr[0].lowercaseChar() in "89abcdef") {
            hashStr = "00$hashStr"
        }
        return hashStr
    }

    fun calculateU(bigA: BigInteger, bigB: BigInteger): BigInteger {
        val uHexHash = hexHash(padHex(bigA) + padHex(bigB))
        return BigInteger(uHexHash, 16)
    }

    /** HKDF-ish key derivation matching Cognito's specific (non-standard) construction. */
    fun computeHkdf(ikm: ByteArray, salt: ByteArray): ByteArray {
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = prkMac.doFinal(ikm)

        val infoBitsUpdate = INFO_BITS + byteArrayOf(1)
        val hmacMac = Mac.getInstance("HmacSHA256")
        hmacMac.init(SecretKeySpec(prk, "HmacSHA256"))
        val hmacHash = hmacMac.doFinal(infoBitsUpdate)

        return hmacHash.copyOfRange(0, 16)
    }

    fun hexStringToBytes(hex: String): ByteArray {
        val clean = padHex(hex)
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Device verifier for Cognito ConfirmDevice — matches amazon-cognito-identity-js
     * AuthenticationHelper.generateHashDevice / Netlify `generateDeviceVerifier`.
     */
    fun generateDeviceVerifier(
        deviceGroupKey: String,
        deviceKey: String,
    ): DeviceVerifier {
        val passwordBytes = ByteArray(40)
        secureRandom.nextBytes(passwordBytes)
        val devicePassword = Base64.getEncoder().encodeToString(passwordBytes)
        val combined = "$deviceGroupKey$deviceKey:$devicePassword"
        val hashedString = hashSha256(combined.toByteArray(Charsets.UTF_8))
        val saltHex = padHex(getRandom(16))
        val xValue = BigInteger(hexHash(saltHex + hashedString), 16)
        val verifierHex = padHex(G.modPow(xValue, N))
        return DeviceVerifier(
            devicePassword = devicePassword,
            // Encode the already-padded hex as raw bytes — do not run padHex again (that can
            // insert an extra leading 00 and break Cognito's verifier check).
            saltB64 = Base64.getEncoder().encodeToString(evenHexToBytes(saltHex)),
            passwordVerifierB64 = Base64.getEncoder().encodeToString(evenHexToBytes(verifierHex)),
        )
    }

    /** Hex → bytes without Cognito's SRP padHex leading-00 rule (for ConfirmDevice salt/verifier). */
    private fun evenHexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 1) "0$hex" else hex
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    data class DeviceVerifier(
        val devicePassword: String,
        val saltB64: String,
        val passwordVerifierB64: String,
    )
}

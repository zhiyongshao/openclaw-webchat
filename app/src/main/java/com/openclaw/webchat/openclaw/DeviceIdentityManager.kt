package com.openclaw.webchat.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Manages Ed25519 device identity for OpenClaw Gateway pairing.
 * Uses BouncyCastle low-level Ed25519 API (compatible with Go crypto).
 * 
 * IMPORTANT: Clears any stale AndroidKeyStore v3 identity on init,
 * ensuring fresh Ed25519 keypair generated with BC.
 */
class DeviceIdentityManager(private val context: Context) {

    private val TAG = "DeviceIdentityMgr"

    companion object {
        private const val PREFS_NAME = "openclaw_device_identity_v2"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"    // base64url raw 32 bytes
        private const val KEY_PRIVATE_KEY = "private_key" // base64url raw 32 bytes
        private const val KEY_LEGACY_V3 = "identity_v3_used"

        const val OPENCLAW_CLIENT_ID = "openclaw-control-ui"
        const val OPENCLAW_CLIENT_MODE = "ui"
        const val OPENCLAW_ROLE = "operator"

        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Clear stale AndroidKeyStore-based v3 identity if it exists
        // (v3 used AndroidKeyStore which may have failed on Android 10)
        if (prefs.getBoolean(KEY_LEGACY_V3, false)) {
            android.util.Log.w(TAG, "Clearing stale AndroidKeyStore v3 identity")
            clearIdentity()
        }
    }

    data class DeviceIdentity(
        val id: String,
        val publicKeyBase64Url: String,
        val privateKeyBase64Url: String
    )

    data class DeviceConnectField(
        val id: String,
        val publicKey: String,
        val signature: String,
        val signedAt: Long,
        val nonce: String
    )

    /**
     * Get or create device identity.
     * Uses BC Ed25519 low-level API — compatible with Go crypto/ed25519.
     * 
     * Key format: raw 32-byte scalars (NOT DER-encoded).
     * This is what Ed25519PrivateKeyParameters.encoded / Ed25519PublicKeyParameters.encoded returns.
     */
    fun getOrCreateDeviceIdentity(): DeviceIdentity? {
        val storedId = prefs.getString(KEY_DEVICE_ID, null)
        val storedPub = prefs.getString(KEY_PUBLIC_KEY, null)
        val storedPriv = prefs.getString(KEY_PRIVATE_KEY, null)

        if (storedId != null && storedPub != null && storedPriv != null) {
            android.util.Log.d(TAG, "Loaded existing BC identity: $storedId")
            return DeviceIdentity(storedId, storedPub, storedPriv)
        }

        android.util.Log.d(TAG, "Generating new Ed25519 keypair with BC...")
        return try {
            val keyGen = Ed25519KeyPairGenerator()
            keyGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = keyGen.generateKeyPair()

            val pubKeyParams = keyPair.public as Ed25519PublicKeyParameters
            val privKeyParams = keyPair.private as Ed25519PrivateKeyParameters

            // BC .encoded returns raw 32-byte key (scalar for priv, u coordinate for pub)
            val publicKeyRaw = pubKeyParams.encoded
            val privateKeyRaw = privKeyParams.encoded

            android.util.Log.d(TAG, "Generated: pubKey len=${publicKeyRaw.size}, privKey len=${privateKeyRaw.size}")

            val idHash = MessageDigest.getInstance("SHA-256").digest(publicKeyRaw)
            val deviceId = idHash.joinToString("") { "%02x".format(it) }

            val pubBase64Url = base64urlEncode(publicKeyRaw)
            val privBase64Url = base64urlEncode(privateKeyRaw)

            android.util.Log.d(TAG, "New BC identity: id=$deviceId")
            android.util.Log.d(TAG, "pubBase64Url=$pubBase64Url")

            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_PUBLIC_KEY, pubBase64Url)
                .putString(KEY_PRIVATE_KEY, privBase64Url)
                .putBoolean(KEY_LEGACY_V3, false)
                .apply()

            DeviceIdentity(deviceId, pubBase64Url, privBase64Url)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getOrCreateDeviceIdentity FAILED: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Sign the challenge payload using BC Ed25519Signer.
     * Output is 64-byte raw Ed25519 signature, base64url encoded.
     * 
     * Compatible with Go crypto/ed25519 signature verification.
     */
    fun signChallenge(
        nonce: String,
        token: String,
        scopes: List<String>
    ): DeviceConnectField? {
        val identity = getOrCreateDeviceIdentity()
        if (identity == null) {
            android.util.Log.e(TAG, "signChallenge FAILED: identity is null")
            return null
        }
        android.util.Log.d(TAG, "signChallenge: id=${identity.id}, nonce=$nonce")

        val signedAt = System.currentTimeMillis()
        val scopesStr = scopes.joinToString(",")

        // v2 payload format (matches ClawControl exactly)
        val payload = "v2|${identity.id}|${OPENCLAW_CLIENT_ID}|${OPENCLAW_CLIENT_MODE}|${OPENCLAW_ROLE}|${scopesStr}|${signedAt}|${token}|${nonce}"
        android.util.Log.d(TAG, "signChallenge: payload=$payload")

        val signature: String
        try {
            // Decode stored raw 32-byte private key
            val privKeyBytes = base64urlDecode(identity.privateKeyBase64Url)
            android.util.Log.d(TAG, "privKey bytes len=${privKeyBytes.size}")

            val privKeyParams = Ed25519PrivateKeyParameters(privKeyBytes, 0)
            val signer = Ed25519Signer()
            signer.init(true, privKeyParams)
            signer.update(payload.toByteArray(Charsets.UTF_8), 0, payload.length)
            val sigBytes = signer.generateSignature()

            signature = base64urlEncode(sigBytes)
            android.util.Log.d(TAG, "signChallenge: sig len=${sigBytes.size}, sig=$signature")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "signChallenge FAILED: ${e.message}")
            e.printStackTrace()
            return null
        }

        return DeviceConnectField(
            id = identity.id,
            publicKey = identity.publicKeyBase64Url,
            signature = signature,
            signedAt = signedAt,
            nonce = nonce
        )
    }

    fun clearIdentity() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_PRIVATE_KEY)
            .remove(KEY_LEGACY_V3)
            .apply()
        android.util.Log.d(TAG, "Identity cleared")
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private fun base64urlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
            .replace("+", "-").replace("/", "_").trimEnd('=')
    }

    private fun base64urlDecode(str: String): ByteArray {
        var s = str.replace("-", "+").replace("_", "/")
        when (s.length % 4) {
            2 -> s += "=="
            3 -> s += "="
        }
        return Base64.decode(s, Base64.NO_WRAP)
    }
}

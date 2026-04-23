package com.openclaw.webchat.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security

/**
 * Manages Ed25519 device identity for OpenClaw Gateway pairing.
 * Uses BouncyCastle for Ed25519 key generation and signing.
 */
class DeviceIdentityManager(private val context: Context) {

    private val TAG = "DeviceIdentityMgr"

    companion object {
        private const val PREFS_NAME = "openclaw_device_identity_v2"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"      // base64url raw 32 bytes
        private const val KEY_PRIVATE_KEY = "private_key"   // base64url raw 32 bytes
        private const val KEY_DEVICE_TOKEN = "device_token"

        const val OPENCLAW_CLIENT_ID = "openclaw-control-ui"
        const val OPENCLAW_CLIENT_MODE = "ui"
        const val OPENCLAW_ROLE = "operator"

        init {
            // Register BC provider
            if (Security.getProvider("BC") == null) {
                Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class DeviceIdentity(
        val id: String,
        val publicKeyBase64Url: String,  // base64url, raw 32 bytes
        val privateKeyBase64Url: String // base64url, raw 32 bytes
    )

    data class DeviceConnectField(
        val id: String,
        val publicKey: String,   // base64url raw
        val signature: String,   // base64url
        val signedAt: Long,
        val nonce: String
    )

    /**
     * Get or create device identity. Keys stored as base64url raw bytes.
     */
    fun getOrCreateDeviceIdentity(): DeviceIdentity? {
        val storedId = prefs.getString(KEY_DEVICE_ID, null)
        val storedPub = prefs.getString(KEY_PUBLIC_KEY, null)
        val storedPriv = prefs.getString(KEY_PRIVATE_KEY, null)

        if (storedId != null && storedPub != null && storedPriv != null) {
            android.util.Log.d(TAG, "Loaded existing identity: $storedId")
            return DeviceIdentity(storedId, storedPub, storedPriv)
        }

        android.util.Log.d(TAG, "Generating new Ed25519 keypair...")
        return try {
            // Use BC low-level API for Ed25519 key generation
            val keyGen = org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator()
            keyGen.init(org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters(SecureRandom()))
            val keyPair = keyGen.generateKeyPair()

            val publicKeyRaw = (keyPair.public as org.bouncycastle.crypto.params.Ed25519PublicKeyParameters).encoded
            val privateKeyRaw = (keyPair.private as org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters).encoded

            android.util.Log.d(TAG, "publicKey len=${publicKeyRaw.size}, privateKey len=${privateKeyRaw.size}")

            val idHash = MessageDigest.getInstance("SHA-256").digest(publicKeyRaw)
            val deviceId = idHash.joinToString("") { "%02x".format(it) }

            val pubBase64Url = base64urlEncode(publicKeyRaw)
            val privBase64Url = base64urlEncode(privateKeyRaw)

            android.util.Log.d(TAG, "New identity: id=$deviceId")
            android.util.Log.d(TAG, "pubBase64Url=$pubBase64Url")

            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_PUBLIC_KEY, pubBase64Url)
                .putString(KEY_PRIVATE_KEY, privBase64Url)
                .apply()

            DeviceIdentity(deviceId, pubBase64Url, privBase64Url)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getOrCreateDeviceIdentity FAILED: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Sign the challenge payload.
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
        val scopesStr = scopes.joinToString(",")  // Declaration order, matches ClawControl

        // v2 payload format (matches ClawControl exactly)
        val payload = "v2|${identity.id}|${OPENCLAW_CLIENT_ID}|${OPENCLAW_CLIENT_MODE}|${OPENCLAW_ROLE}|${scopesStr}|${signedAt}|${token}|${nonce}"
        android.util.Log.d(TAG, "signChallenge: payload=$payload")

        val signature: String
        try {
            val privKeyParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(base64urlDecode(identity.privateKeyBase64Url), 0)
            val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
            signer.init(true, privKeyParams)
            signer.update(payload.toByteArray(Charsets.UTF_8), 0, payload.length)
            val sigBytes = signer.generateSignature()
            signature = base64urlEncode(sigBytes)
            android.util.Log.d(TAG, "signChallenge: signature=$signature")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "signChallenge FAILED during signing: ${e.message}")
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

    fun saveDeviceToken(serverHost: String, token: String) {
        prefs.edit().putString("${KEY_DEVICE_TOKEN}_$serverHost", token).apply()
    }

    fun getDeviceToken(serverHost: String): String? {
        return prefs.getString("${KEY_DEVICE_TOKEN}_$serverHost", null)
    }

    fun clearIdentity() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_PRIVATE_KEY)
            .apply()
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

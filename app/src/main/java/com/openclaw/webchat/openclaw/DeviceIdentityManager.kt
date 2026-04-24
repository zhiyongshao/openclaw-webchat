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
 * Uses BouncyCastle low-level Ed25519 API (RFC 8032 compliant).
 * 
 * Compatibility note: BC Ed25519Signer uses pure Ed25519 mode,
 * compatible with Go crypto/ed25519. Signatures are 64 bytes.
 */
class DeviceIdentityManager(private val context: Context) {

    private val TAG = "DeviceIdentityMgr"

    companion object {
        private const val PREFS_NAME = "openclaw_device_identity_bc"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"    // base64url raw 32 bytes
        private const val KEY_PRIVATE_KEY = "private_key" // base64url raw 32 bytes

        const val OPENCLAW_CLIENT_ID = "openclaw-control-ui"
        const val OPENCLAW_CLIENT_MODE = "ui"
        const val OPENCLAW_ROLE = "operator"

        init {
            // Ensure BC is registered as the crypto provider
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.insertProviderAt(BouncyCastleProvider(), 1)
            }
            android.util.Log.d(TAG, "BC provider registered.")
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
     * Generate Ed25519 keypair using BC low-level API.
     * Keys are stored as raw 32-byte values (not DER-encoded).
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

            // .encoded returns raw 32-byte values
            val publicKeyRaw = pubKeyParams.encoded
            val privateKeyRaw = privKeyParams.encoded

            android.util.Log.d(TAG, "BC keygen: pub=${publicKeyRaw.size} bytes, priv=${privateKeyRaw.size} bytes")

            val idHash = MessageDigest.getInstance("SHA-256").digest(publicKeyRaw)
            val deviceId = idHash.joinToString("") { "%02x".format(it) }

            val pubBase64Url = base64urlEncode(publicKeyRaw)
            val privBase64Url = base64urlEncode(privateKeyRaw)

            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_PUBLIC_KEY, pubBase64Url)
                .putString(KEY_PRIVATE_KEY, privBase64Url)
                .apply()

            android.util.Log.d(TAG, "New BC identity: $deviceId")
            android.util.Log.d(TAG, "pubBase64Url: $pubBase64Url")
            android.util.Log.d(TAG, "privBase64Url stored (len=${privBase64Url.length})")

            DeviceIdentity(deviceId, pubBase64Url, privBase64Url)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "KeyGen FAILED: ${e.message}", e)
            null
        }
    }

    /**
     * Sign the challenge payload.
     * Uses BC Ed25519Signer in pure Ed25519 mode (RFC 8032).
     * Signature is 64 bytes, base64url encoded.
     */
    fun signChallenge(
        nonce: String,
        token: String,
        scopes: List<String>
    ): DeviceConnectField? {
        val identity = getOrCreateDeviceIdentity()
        if (identity == null) {
            android.util.Log.e(TAG, "signChallenge: identity is null")
            return null
        }

        val signedAt = System.currentTimeMillis()
        val scopesStr = scopes.joinToString(",")

        val payload = "v2|${identity.id}|${OPENCLAW_CLIENT_ID}|${OPENCLAW_CLIENT_MODE}|${OPENCLAW_ROLE}|${scopesStr}|${signedAt}|${token}|$nonce"
        android.util.Log.d(TAG, "signChallenge payload: $payload")

        val signature: String
        try {
            val privKeyBytes = base64urlDecode(identity.privateKeyBase64Url)
            android.util.Log.d(TAG, "signChallenge: decoded privKey len=${privKeyBytes.size}")

            val privKeyParams = Ed25519PrivateKeyParameters(privKeyBytes, 0)
            val signer = Ed25519Signer()
            signer.init(true, privKeyParams)
            signer.update(payload.toByteArray(Charsets.UTF_8), 0, payload.length)
            val sigBytes = signer.generateSignature()

            signature = base64urlEncode(sigBytes)
            android.util.Log.d(TAG, "signChallenge: sig len=${sigBytes.size}, sig=$signature")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "signChallenge EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
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
        prefs.edit().clear().apply()
        android.util.Log.d(TAG, "Identity cleared")
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
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

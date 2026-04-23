package com.openclaw.webchat.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security

/**
 * Manages Ed25519 device identity for OpenClaw Gateway pairing.
 * Uses BouncyCastle for Ed25519 key generation and signing (no AndroidKeyStore).
 */
class DeviceIdentityManager(private val context: Context) {

    private val TAG = "DeviceIdentityMgr"

    companion object {
        private const val PREFS_NAME = "openclaw_device_identity_v2"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"      // base64url raw 32 bytes
        private const val KEY_PRIVATE_KEY = "private_key"   // base64url raw 32 bytes
        private const val KEY_DEVICE_TOKEN = "device_token"

        const val OPENCLAW_CLIENT_ID = "openclaw-webchat"
        const val OPENCLAW_CLIENT_MODE = "ui"
        const val OPENCLAW_ROLE = "operator"

        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
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
            // Generate Ed25519 keypair using BC
            val kg = KeyPairGenerator.getInstance("EdDSA", "BC")
            kg.initialize(256, SecureRandom())
            val keyPair = kg.generateKeyPair()

            val publicKeyRaw = keyPair.public.encoded
            val privateKeyRaw = keyPair.private.encoded

            android.util.Log.d(TAG, "publicKey.encoded len=${publicKeyRaw.size}, privateKey.encoded len=${privateKeyRaw.size}")

            // Extract raw 32-byte keys from DER-encoded subjectPublicKeyInfo / PKCS8
            val pubRaw = extractRawFromDer(publicKeyRaw, isPublic = true)
            val privRaw = extractRawFromDer(privateKeyRaw, isPublic = false)

            val idHash = MessageDigest.getInstance("SHA-256").digest(pubRaw)
            val deviceId = idHash.joinToString("") { "%02x".format(it) }

            val pubBase64Url = base64urlEncode(pubRaw)
            val privBase64Url = base64urlEncode(privRaw)

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
        val scopesStr = scopes.sorted().joinToString(",")

        // v2 payload format (matches ClawControl exactly)
        val payload = "v2|${identity.id}|${OPENCLAW_CLIENT_ID}|${OPENCLAW_CLIENT_MODE}|${OPENCLAW_ROLE}|${scopesStr}|${signedAt}|${token}|${nonce}"
        android.util.Log.d(TAG, "signChallenge: payload=$payload")

        val signature: String
        try {
            val privRaw = base64urlDecode(identity.privateKeyBase64Url)
            val msgBytes = payload.toByteArray(Charsets.UTF_8)
            val sigBytes = ByteArray(64)
            org.bouncycastle.math.ec.rfc8032.Ed25519.sign(privRaw, 0, msgBytes, 0, msgBytes.size, sigBytes, 0)
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

    private fun extractRawFromDer(der: ByteArray, isPublic: Boolean): ByteArray {
        // DER structure for Ed25519 public key (subjectPublicKeyInfo):
        // SEQUENCE { OID(1.3.101.112) BIT STRING<raw 32 bytes> }
        // DER structure for Ed25519 private key (PKCS8):
        // SEQUENCE { INTEGER(0) OCTET STRING(32) [0](BIT STRING pubKey) }
        var pos = 0
        pos++ // SEQUENCE tag (0x30)
        val seqLen = der[pos++].toInt() and 0xFF
        if (seqLen > 0x80) pos++ // long length

        if (isPublic) {
            // Public key: after SEQUENCE, skip OID INTEGER, then BIT STRING
            pos++ // INTEGER tag (0x02)
            val oidLen = der[pos++].toInt() and 0xFF
            pos += oidLen
            pos++ // BIT STRING tag (0x03)
            val bitLen = der[pos++].toInt() and 0xFF
            if (bitLen > 0x80) pos++ // long length
            pos++ // unused bits byte (0x00)
        } else {
            // Private key: after SEQUENCE, INTEGER(0), OCTET STRING(32)
            pos++ // INTEGER tag (0x02)
            val zeroLen = der[pos++].toInt() and 0xFF
            pos += zeroLen
            pos++ // OCTET STRING tag (0x04)
            val octetLen = der[pos++].toInt() and 0xFF
            if (octetLen > 0x80) {
                val numBytes = octetLen and 0x7F
                var len = 0
                for (i in 0 until numBytes) len = (len shl 8) or (der[pos++].toInt() and 0xFF)
                pos += len
            }
            // pos now at the raw 32-byte private key
        }

        val raw = ByteArray(32)
        System.arraycopy(der, pos, raw, 0, 32)
        return raw
    }

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
package com.openclaw.webchat.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.Security

/**
 * Manages Ed25519 device identity for OpenClaw Gateway pairing.
 * Uses Android native EdDSA (via AndroidKeyStore) instead of BouncyCastle.
 * Android 10+ required for EdDSA support.
 */
class DeviceIdentityManager(private val context: Context) {

    private val TAG = "DeviceIdentityMgr"

    companion object {
        private const val PREFS_NAME = "openclaw_device_identity_v3"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"    // base64url raw 32 bytes
        private const val KEY_ALIAS = "openclaw_ed25519_device_key"

        const val OPENCLAW_CLIENT_ID = "openclaw-control-ui"
        const val OPENCLAW_CLIENT_MODE = "ui"
        const val OPENCLAW_ROLE = "operator"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class DeviceIdentity(
        val id: String,
        val publicKeyBase64Url: String,  // base64url, raw 32 bytes
        val alias: String                  // AndroidKeyStore key alias
    )

    data class DeviceConnectField(
        val id: String,
        val publicKey: String,   // base64url raw
        val signature: String,   // base64url
        val signedAt: Long,
        val nonce: String
    )

    /**
     * Get or create device identity.
     * Uses AndroidKeyStore for key storage and signing (native EdDSA).
     */
    fun getOrCreateDeviceIdentity(): DeviceIdentity? {
        val storedId = prefs.getString(KEY_DEVICE_ID, null)
        val storedPub = prefs.getString(KEY_PUBLIC_KEY, null)
        val storedAlias = prefs.getString(KEY_ALIAS, null)

        if (storedId != null && storedPub != null && storedAlias != null) {
            // Verify the key still exists in AndroidKeyStore
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(storedAlias)) {
                android.util.Log.d(TAG, "Loaded existing identity: $storedId")
                return DeviceIdentity(storedId, storedPub, storedAlias)
            }
            android.util.Log.d(TAG, "Key missing from AndroidKeyStore, regenerating...")
        }

        android.util.Log.d(TAG, "Generating new EdDSA keypair via AndroidKeyStore...")
        return try {
            val keyPairGen = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )

            val builder = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN
            )
                .setKeySize(256)
                .setDigests(KeyProperties.DIGEST_NONE)
                // EdDSA does not use digests

            keyPairGen.initialize(builder.build())
            val keyPair = keyPairGen.generateKeyPair()

            // Export public key in raw 32-byte format
            val publicKey = keyPair.public as java.security.interfaces.ECPublicKey
            // Get the EC public key point and extract raw 32-byte x coordinate
            val sp = publicKey.encoded
            val rawPublicKey = extractRawPublicKeyFromSpki(sp)
            val deviceId = sha256Hex(rawPublicKey)

            android.util.Log.d(TAG, "New identity: id=$deviceId")
            android.util.Log.d(TAG, "publicKey spki len=${sp.size}, raw len=${rawPublicKey.size}")

            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_PUBLIC_KEY, base64urlEncode(rawPublicKey))
                .putString(KEY_ALIAS, KEY_ALIAS)
                .apply()

            DeviceIdentity(deviceId, base64urlEncode(rawPublicKey), KEY_ALIAS)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "getOrCreateDeviceIdentity FAILED: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Sign the challenge payload using AndroidKeyStore EdDSA.
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
            val sig = Signature.getInstance("EdDSA", "AndroidKeyStore")
            sig.initSign(getPrivateKey(identity.alias))
            sig.update(payload.toByteArray(Charsets.UTF_8))
            val sigBytes = sig.sign()
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

    private fun getPrivateKey(alias: String): java.security.PrivateKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(alias, null) as java.security.PrivateKey
    }

    /**
     * Extract raw 32-byte public key from SPKI DER encoding.
     * Format: SEQUENCE { OID(1.2.840.10045.2.1) [1](BIT STRING raw) }
     * The BIT STRING contains the uncompressed EC point: 0x04 || x || y (65 bytes)
     * Ed25519 public key is the x coordinate (32 bytes).
     */
    private fun extractRawPublicKeyFromSpki(spki: ByteArray): ByteArray {
        var pos = 0
        pos++ // SEQUENCE tag (0x30)
        val seqLen = spki[pos++].toInt() and 0xFF
        if (seqLen > 0x80) pos += (seqLen and 0x7F) // long length
        pos++ // OID tag (0x06)
        val oidLen = spki[pos++].toInt() and 0xFF
        pos += oidLen // skip OID
        pos++ // BIT STRING tag (0x03)
        val bitLen = spki[pos++].toInt() and 0xFF
        if (bitLen > 0x80) {
            val numBytes = bitLen and 0x7F
            var len = 0
            for (i in 0 until numBytes) len = (len shl 8) or (spki[pos++].toInt() and 0xFF)
        }
        pos++ // skip unused bits byte (0x00)
        // pos now at the raw public key point
        // Ed25519: skip 0x04 prefix (1 byte), next 32 bytes are x coordinate
        val raw = ByteArray(32)
        System.arraycopy(spki, pos + 1, raw, 0, 32)
        return raw
    }

    private fun sha256Hex(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }

    fun clearIdentity() {
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_ALIAS)
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

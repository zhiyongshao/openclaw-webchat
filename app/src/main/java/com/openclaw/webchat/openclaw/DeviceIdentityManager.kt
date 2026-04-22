package com.openclaw.webchat.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import javax.crypto.Cipher

/**
 * Manages Ed25519 device identity for OpenClaw Gateway pairing.
 * Uses AndroidKeyStore (API 28+) with JWK storage format matching ClawControl.
 */
class DeviceIdentityManager(private val context: Context) {

    private val TAG = "DeviceIdentityMgr"

    companion object {
        private const val PREFS_NAME = "openclaw_device_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"      // base64url raw 32 bytes
        private const val KEY_PRIVATE_KEY_JWK = "private_key_jwk"  // JWK format
        private const val KEY_DEVICE_TOKEN = "device_token"

        const val OPENCLAW_CLIENT_ID = "openclaw-webchat"
        const val OPENCLAW_CLIENT_MODE = "ui"
        const val OPENCLAW_ROLE = "operator"

        private const val ANDROID_KEYSTORE_ALIAS = "openclaw_ed25519_key"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
    }

    data class DeviceIdentity(
        val id: String,
        val publicKeyBase64Url: String,  // base64url, raw 32 bytes
        val privateKeyJwk: String        // JWK JSON string
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
     */
    fun getOrCreateDeviceIdentity(): DeviceIdentity? {
        val storedId = prefs.getString(KEY_DEVICE_ID, null)
        val storedPub = prefs.getString(KEY_PUBLIC_KEY, null)
        val storedPriv = prefs.getString(KEY_PRIVATE_KEY_JWK, null)

        if (storedId != null && storedPub != null && storedPriv != null) {
            return DeviceIdentity(storedId, storedPub, storedPriv)
        }

        return try {
            val keyPair = generateEd25519KeyPair()
            val publicKeyRaw = extractRawPublicKey(keyPair.public.encoded)
            val idHash = MessageDigest.getInstance("SHA-256").digest(publicKeyRaw)
            val deviceId = idHash.joinToString("") { "%02x".format(it) }
            val publicKeyBase64Url = Base64.encodeToString(publicKeyRaw, Base64.NO_WRAP)
                .replace("+", "-").replace("/", "_").trimEnd('=')

            // Export private key as JWK
            val privateKeyJwk = exportPrivateKeyAsJwk(keyPair.private.encoded)

            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_PUBLIC_KEY, publicKeyBase64Url)
                .putString(KEY_PRIVATE_KEY_JWK, privateKeyJwk)
                .apply()

            DeviceIdentity(deviceId, publicKeyBase64Url, privateKeyJwk)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Sign the challenge payload. Matches ClawControl's Web Crypto implementation.
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
        android.util.Log.d(TAG, "signChallenge: identity.id=${identity.id}")
        android.util.Log.d(TAG, "signChallenge: identity.publicKeyBase64Url=${identity.publicKeyBase64Url}")
        android.util.Log.d(TAG, "signChallenge: nonce=$nonce")
        android.util.Log.d(TAG, "signChallenge: token=$token")

        val signedAt = System.currentTimeMillis()
        val scopesStr = scopes.sorted().joinToString(",")

        // v2 payload format (matches ClawControl exactly)
        val payload = "v2|${identity.id}|${OPENCLAW_CLIENT_ID}|${OPENCLAW_CLIENT_MODE}|${OPENCLAW_ROLE}|${scopesStr}|${signedAt}|${token}|${nonce}"
        android.util.Log.d(TAG, "signChallenge: payload=$payload")

        val signature = try {
            val sig = signWithEd25519(identity.privateKeyJwk, payload)
            android.util.Log.d(TAG, "signChallenge: signature=$sig")
            sig
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
            .remove(KEY_PRIVATE_KEY_JWK)
            .apply()
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    private fun generateEd25519KeyPair(): KeyPair {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val kg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                ANDROID_KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(256)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            kg.initialize(spec)
            kg.generateKeyPair()
        } else {
            val kg = KeyPairGenerator.getInstance("EdDSA", "BC")
            kg.generateKeyPair()
        }
    }

    /**
     * Extract raw 32-byte public key from DER-encoded EdDSA public key.
     * DER: SEQUENCE { OID Ed25519, BIT STRING <raw 32 bytes> }
     */
    private fun extractRawPublicKey(derEncoded: ByteArray): ByteArray {
        var pos = 0
        pos++ // 0x30 SEQUENCE
        val seqLen = derEncoded[pos++].toInt() and 0xFF
        pos++ // 0x02 OID INTEGER
        val oidLen = derEncoded[pos++].toInt() and 0xFF
        pos += oidLen
        pos++ // 0x03 BIT STRING
        val bitStringLen = derEncoded[pos++].toInt() and 0xFF
        pos++ // 0x00 unused bits
        val rawKey = ByteArray(32)
        System.arraycopy(derEncoded, pos, rawKey, 0, 32)
        return rawKey
    }

    /**
     * Export PKCS8 DER private key as OKP JWK.
     * PKCS8 Ed25519 structure:
     * SEQUENCE { INTEGER(0) OCTET STRING(32 privKey) [0](BIT STRING pubKey) }
     */
    private fun exportPrivateKeyAsJwk(pkcs8Der: ByteArray): String {
        var pos = 0
        pos++ // SEQUENCE
        val seqLen = pkcs8Der[pos++].toInt() and 0xFF
        pos++ // INTEGER 0
        val zeroLen = pkcs8Der[pos++].toInt() and 0xFF
        pos += zeroLen
        pos++ // OCTET STRING (private key)
        val privLen = pkcs8Der[pos++].toInt() and 0xFF
        val privateKeyRaw = ByteArray(32)
        System.arraycopy(pkcs8Der, pos, privateKeyRaw, 0, 32)
        pos += privLen
        pos++ // [0] context tag
        val pubBitStringLen = pkcs8Der[pos++].toInt() and 0xFF
        pos++ // BIT STRING tag
        val pubLen = pkcs8Der[pos++].toInt() and 0xFF
        val publicKeyRaw = ByteArray(32)
        System.arraycopy(pkcs8Der, pos, publicKeyRaw, 0, 32)

        val dBase64Url = Base64.encodeToString(privateKeyRaw, Base64.NO_WRAP)
            .replace("+", "-").replace("/", "_").trimEnd('=')
        val xBase64Url = Base64.encodeToString(publicKeyRaw, Base64.NO_WRAP)
            .replace("+", "-").replace("/", "_").trimEnd('=')

        return """
        {
            "kty": "OKP",
            "crv": "Ed25519",
            "x": "$xBase64Url",
            "d": "$dBase64Url"
        }
        """.trimIndent()
    }

    /**
     * Sign using Ed25519. Uses BC directly with PKCS8 key material.
     */
    private fun signWithEd25519(privateKeyJwk: String, payload: String): String {
        val jwk = org.json.JSONObject(privateKeyJwk)
        val dBase64 = jwk.getString("d").replace("-", "+").replace("_", "/")
        val dWithPadding = when (dBase64.length % 4) {
            2 -> dBase64 + "=="
            3 -> dBase64 + "="
            else -> dBase64
        }
        val privateKeyRaw = Base64.decode(dWithPadding, Base64.NO_WRAP)

        val messageBytes = payload.toByteArray(Charsets.UTF_8)
        val signatureBytes = ByteArray(64)
        org.bouncycastle.math.ec.rfc8032.Ed25519.sign(privateKeyRaw, 0, messageBytes, 0, messageBytes.size, signatureBytes, 0)

        return Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
            .replace("+", "-").replace("/", "_").trimEnd('=')
    }
}

package com.openclaw.webchat.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Manages Ed25519 device identity for OpenClaw Gateway pairing.
 * Uses AndroidKeyStore for key storage + BouncyCastle for Ed25519 signing.
 */
class DeviceIdentityManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "openclaw_device_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_PRIVATE_KEY = "private_key"
        private const val KEY_DEVICE_TOKEN = "device_token"

        // OpenClaw client identity constants (from ClawControl)
        const val OPENCLAW_CLIENT_ID = "openclaw-webchat"
        const val OPENCLAW_CLIENT_MODE = "ui"
        const val OPENCLAW_ROLE = "operator"

        // AndroidKeyStore key alias
        private const val ANDROID_KEYSTORE_ALIAS = "openclaw_ed25519_key"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Register BouncyCastle as crypto provider
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class DeviceIdentity(
        val id: String,
        val publicKeyBase64: String,
        val privateKeyBase64: String
    )

    data class DeviceConnectField(
        val id: String,
        val publicKey: String,
        val signature: String,
        val signedAt: Long,
        val nonce: String
    )

    /**
     * Get or create device identity. Returns null if crypto not available.
     */
    fun getOrCreateDeviceIdentity(): DeviceIdentity? {
        // Try loading from storage first
        val storedId = prefs.getString(KEY_DEVICE_ID, null)
        val storedPub = prefs.getString(KEY_PUBLIC_KEY, null)
        val storedPriv = prefs.getString(KEY_PRIVATE_KEY, null)

        if (storedId != null && storedPub != null && storedPriv != null) {
            return DeviceIdentity(storedId, storedPub, storedPriv)
        }

        // Generate new keypair
        return try {
            // Try native AndroidKeyStore EdDSA first (API 28+)
            val keyPair = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                generateAndroidKeyStoreKeyPair()
            } else {
                generateBCKeyPair()
            }

            // Extract raw public key for device ID
            val publicKeyRaw = extractEd25519PublicKeyRaw(keyPair.public.encoded)
            val idHash = MessageDigest.getInstance("SHA-256").digest(publicKeyRaw)
            val deviceId = idHash.joinToString("") { "%02x".format(it) }

            val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
            val privateKeyBase64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

            // Store
            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_PUBLIC_KEY, publicKeyBase64)
                .putString(KEY_PRIVATE_KEY, privateKeyBase64)
                .apply()

            DeviceIdentity(deviceId, publicKeyBase64, privateKeyBase64)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Sign the challenge payload for OpenClaw Gateway pairing.
     * Returns the device field to include in connect request.
     */
    fun signChallenge(
        nonce: String,
        token: String,
        scopes: List<String>
    ): DeviceConnectField? {
        val identity = getOrCreateDeviceIdentity() ?: return null
        val signedAt = System.currentTimeMillis()

        val scopesStr = scopes.sorted().joinToString(",")

        // v2 signing payload format (from ClawControl)
        // v2|deviceId|clientId|clientMode|role|scopes|signedAt|token|nonce
        val payload = "v2|${identity.id}|${OPENCLAW_CLIENT_ID}|${OPENCLAW_CLIENT_MODE}|${OPENCLAW_ROLE}|${scopesStr}|${signedAt}|${token}|${nonce}"

        val signature = try {
            val privateKeyBytes = Base64.decode(identity.privateKeyBase64, Base64.NO_WRAP)
            val messageBytes = payload.toByteArray(Charsets.UTF_8)
            val signatureBytes = ByteArray(64)
            Ed25519.sign(privateKeyBytes, 0, messageBytes, 0, messageBytes.size, signatureBytes, 0)
            Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        return DeviceConnectField(
            id = identity.id,
            publicKey = identity.publicKeyBase64,
            signature = signature,
            signedAt = signedAt,
            nonce = nonce
        )
    }

    /**
     * Save the device token received after successful pairing.
     */
    fun saveDeviceToken(serverHost: String, token: String) {
        prefs.edit().putString("${KEY_DEVICE_TOKEN}_$serverHost", token).apply()
    }

    /**
     * Get the saved device token for a server.
     */
    fun getDeviceToken(serverHost: String): String? {
        return prefs.getString("${KEY_DEVICE_TOKEN}_$serverHost", null)
    }

    /**
     * Clear stored identity (for reset).
     */
    fun clearIdentity() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_PUBLIC_KEY)
            .remove(KEY_PRIVATE_KEY)
            .apply()
    }

    /**
     * Clear all stored data including device tokens.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ─────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Generate Ed25519 keypair using AndroidKeyStore (Android P+).
     * Keys are hardware-backed if available.
     */
    private fun generateAndroidKeyStoreKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )
        val builder = KeyGenParameterSpec.Builder(
            ANDROID_KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(256)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)

        keyPairGenerator.initialize(builder.build())
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Generate Ed25519 keypair using BouncyCastle (fallback).
     */
    private fun generateBCKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("EdDSA", "BC")
        return keyGen.generateKeyPair()
    }

    /**
     * Extract raw 32-byte public key from DER-encoded EdDSA public key.
     * DER format: SEQUENCE { OID Ed25519, BIT STRING <32 bytes raw> }
     */
    private fun extractEd25519PublicKeyRaw(derEncoded: ByteArray): ByteArray {
        // DER structure:
        // 30 [len]           - SEQUENCE
        // 02 [len] [data]    - OID INTEGER (Ed25519 = 1.3.101.112)
        // 03 [len] 00 [32 bytes raw key]
        var pos = 0
        pos++ // skip SEQUENCE tag 0x30
        val seqLen = derEncoded[pos++].toInt() and 0xFF
        pos++ // skip OID tag 0x02
        val oidLen = derEncoded[pos++].toInt() and 0xFF
        pos += oidLen // skip OID value
        pos++ // skip BIT STRING tag 0x03
        val bitStringLen = derEncoded[pos++].toInt() and 0xFF
        pos++ // skip unused bits byte 0x00

        val rawKey = ByteArray(32)
        System.arraycopy(derEncoded, pos, rawKey, 0, 32)
        return rawKey
    }
}

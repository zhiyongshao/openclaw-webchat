package com.openclaw.webchat.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.jce.interfaces.EdECPrivateKey
import org.spongycastle.jce.interfaces.EdECPublicKey
import org.spongycastle.jce.ECNamedParameterTable
import org.spongycastle.jce.spec.EdECParameterSpec
import org.spongycastle.jce.spec.EdECPrivateKeySpec
import org.spongycastle.jce.spec.EdECPublicKeySpec
import org.spongycastle.math.ec.rfc8032.Ed25519
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages Ed25519 device identity for OpenClaw Gateway pairing.
 * Uses Android Keystore with SpongyCastle for Ed25519 operations.
 */
class DeviceIdentityManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "openclaw_device_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_PUBLIC_KEY = "public_key"
        private const val KEY_PRIVATE_KEY = "private_key"  // encrypted with Keystore
        private const val KEY_DEVICE_TOKEN = "device_token"

        // OpenClaw client identity constants (from ClawControl)
        const val OPENCLAW_CLIENT_ID = "openclaw-webchat"
        const val OPENCLAW_CLIENT_MODE = "ui"
        const val OPENCLAW_ROLE = "operator"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Register SpongyCastle as crypto provider
        if (Security.getProvider("SC") == null) {
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
            val keyPair = generateEd25519KeyPair()
            val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
            val privateKeyBase64 = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

            // Compute device ID as SHA-256 of public key (raw, not DER)
            val publicKeyRaw = extractEd25519PublicKeyRaw(keyPair.public)
            val idHash = MessageDigest.getInstance("SHA-256").digest(publicKeyRaw)
            val deviceId = idHash.joinToString("") { "%02x".format(it) }

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
            val privateKeySpec = PKCS8EncodedKeySpec(Base64.decode(identity.privateKeyBase64, Base64.NO_WRAP))
            val keyFactory = KeyFactory.getInstance("EdDSA", "SC")
            val privateKey = keyFactory.generatePrivate(privateKeySpec)

            val messageBytes = payload.toByteArray(Charsets.UTF_8)
            val signatureBytes = Ed25519.sign(privateKey, messageBytes)
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

    // --- Private helpers ---

    private fun generateEd25519KeyPair(): KeyPair {
        val keyGen = KeyGenerator.getInstance("EdDSA", "SC")
        return keyGen.generateKeyPair()
    }

    /**
     * Extract raw 32-byte public key from EdDSA public key for ID computation.
     */
    private fun extractEd25519PublicKeyRaw(publicKey: java.security.PublicKey): ByteArray {
        // The DER encoding of Ed25519 public key starts with SEQUENCE { OID Ed25519, BIT STRING raw }
        // We need to extract just the 32-byte raw public key
        val derEncoded = publicKey.encoded
        // DER format: 30 [len] 02 20 [32 bytes] for Ed25519
        // Skip to the raw key part
        var offset = 0
        // SEQUENCE
        offset++ // 0x30
        offset++ // length
        // OID
        offset++ // 0x02
        offset++ // 0x20 (32)
        offset++ // 0x03 (BIT STRING)
        offset++ // 0x21 (33 bytes)
        offset++ // 0x00 (unused bits)
        val rawKey = ByteArray(32)
        System.arraycopy(derEncoded, offset, rawKey, 0, 32)
        return rawKey
    }
}

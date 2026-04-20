package com.openclaw.webchat.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences

    init {
        // Try to use encrypted preferences if available
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "openclaw_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback to regular shared preferences
            context.getSharedPreferences("openclaw_prefs", Context.MODE_PRIVATE)
        }
    }

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, "") ?: ""
    }

    fun saveServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun getSSHHost(): String {
        return prefs.getString(KEY_SSH_HOST, "") ?: ""
    }

    fun saveSSHHost(host: String) {
        prefs.edit().putString(KEY_SSH_HOST, host).apply()
    }

    fun getSSHPort(): Int {
        return prefs.getInt(KEY_SSH_PORT, 22)
    }

    fun saveSSHPort(port: Int) {
        prefs.edit().putInt(KEY_SSH_PORT, port).apply()
    }

    fun getSSHUser(): String {
        return prefs.getString(KEY_SSH_USER, "root") ?: "root"
    }

    fun saveSSHUser(user: String) {
        prefs.edit().putString(KEY_SSH_USER, user).apply()
    }

    fun getSSHPassword(): String {
        return prefs.getString(KEY_SSH_PASSWORD, "") ?: ""
    }

    fun saveSSHPassword(password: String) {
        prefs.edit().putString(KEY_SSH_PASSWORD, password).apply()
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SSH_HOST = "ssh_host"
        private const val KEY_SSH_PORT = "ssh_port"
        private const val KEY_SSH_USER = "ssh_user"
        private const val KEY_SSH_PASSWORD = "ssh_password"
    }
}

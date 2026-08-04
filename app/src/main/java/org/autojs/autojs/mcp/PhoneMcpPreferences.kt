package org.autojs.autojs.mcp

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.preference.PreferenceManager
import org.autojs.autojs.apkbuilder.keystore.AESUtils
import java.security.SecureRandom

object PhoneMcpPreferences {

    const val KEY_ENABLED = "phone_mcp_enabled"
    const val KEY_CONTROL_URL = "phone_mcp_control_url"
    const val KEY_AUTH_KEY = "phone_mcp_auth_key"
    const val KEY_HOSTNAME = "phone_mcp_hostname"
    const val KEY_TAILNET_PORT = "phone_mcp_tailnet_port"
    const val KEY_LOCAL_PORT = "phone_mcp_local_port"
    const val KEY_PAIRING_TOKEN = "phone_mcp_pairing_token"
    const val KEY_LOCAL_ONLY = "phone_mcp_local_only"

    private const val KEY_AUTH_KEY_ENCRYPTED = "phone_mcp_auth_key_encrypted"
    private const val KEY_PAIRING_TOKEN_ENCRYPTED = "phone_mcp_pairing_token_encrypted"

    const val DEFAULT_PORT = 8765

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun controlUrl(context: Context): String = prefs(context).getString(KEY_CONTROL_URL, "")?.trim().orEmpty()

    fun authKey(context: Context): String = readSecret(context, KEY_AUTH_KEY_ENCRYPTED, KEY_AUTH_KEY).trim()

    fun setAuthKey(context: Context, authKey: String) {
        writeSecret(context, KEY_AUTH_KEY_ENCRYPTED, KEY_AUTH_KEY, authKey.trim())
    }

    fun clearAuthKey(context: Context) {
        prefs(context).edit().remove(KEY_AUTH_KEY).remove(KEY_AUTH_KEY_ENCRYPTED).apply()
    }

    fun hostname(context: Context): String = prefs(context).getString(KEY_HOSTNAME, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: defaultHostname()

    fun tailnetPort(context: Context): Int = parsePort(prefs(context).getString(KEY_TAILNET_PORT, null))

    fun localPort(context: Context): Int = parsePort(prefs(context).getString(KEY_LOCAL_PORT, null))

    fun localOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCAL_ONLY, false)

    fun pairingToken(context: Context): String {
        readSecret(context, KEY_PAIRING_TOKEN_ENCRYPTED, KEY_PAIRING_TOKEN)
            .takeIf { it.length >= 24 }
            ?.let { return it }
        return regeneratePairingToken(context)
    }

    fun regeneratePairingToken(context: Context): String = generatePairingToken().also {
        writeSecret(context, KEY_PAIRING_TOKEN_ENCRYPTED, KEY_PAIRING_TOKEN, it)
    }

    fun ensureDefaults(context: Context) {
        val preferences = prefs(context)
        val editor = preferences.edit()
        if (!preferences.contains(KEY_HOSTNAME)) editor.putString(KEY_HOSTNAME, defaultHostname())
        if (!preferences.contains(KEY_TAILNET_PORT)) editor.putString(KEY_TAILNET_PORT, DEFAULT_PORT.toString())
        if (!preferences.contains(KEY_LOCAL_PORT)) editor.putString(KEY_LOCAL_PORT, DEFAULT_PORT.toString())
        editor.apply()
        authKey(context)
        pairingToken(context)
    }

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun readSecret(context: Context, encryptedKey: String, legacyKey: String): String {
        val preferences = prefs(context)
        preferences.getString(encryptedKey, null)?.let { encrypted ->
            return runCatching { AESUtils.decrypt(encrypted) }.getOrElse {
                preferences.edit().remove(encryptedKey).apply()
                ""
            }
        }
        val legacy = preferences.getString(legacyKey, null).orEmpty()
        if (legacy.isNotEmpty()) writeSecret(context, encryptedKey, legacyKey, legacy)
        return legacy
    }

    private fun writeSecret(context: Context, encryptedKey: String, legacyKey: String, value: String) {
        val editor = prefs(context).edit().remove(legacyKey)
        if (value.isEmpty()) editor.remove(encryptedKey)
        else editor.putString(encryptedKey, AESUtils.encrypt(value))
        editor.apply()
    }

    private fun parsePort(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT

    private fun defaultHostname(): String {
        val model = Build.MODEL.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .take(28)
            .ifBlank { "android" }
        return "phone-mcp-$model"
    }

    private fun generatePairingToken(): String {
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }
}

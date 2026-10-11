package fi.mitsukanny.phevbridge

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved car Wi-Fi credentials, shared by every entry point (the manual test UI and
 * the call-triggered [PhevCallScreeningService]) so there is exactly one place that
 * knows how the password is encrypted, and no duplicated AES/Keystore logic.
 *
 * Deliberately the same SharedPreferences file/Keystore alias [Rc4TestActivity]
 * already uses, so whatever the user saved through the UI once is what the
 * call-triggered path uses too -- no separate "credentials for calls" setup.
 */
object CarCredentials {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "phev_bridge_test_wifi_password_v1"
    private const val PREFS_NAME = "phevbridge_test_prefs"
    private const val PREF_SSID = "ssid"
    private const val PREF_HOST = "host"
    private const val PREF_PORT = "port"
    private const val PREF_CIPHERTEXT = "password_ciphertext"
    private const val PREF_IV = "password_iv"

    data class Saved(val ssid: String, val password: String, val host: String, val port: Int)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Null if no SSID+password is saved yet, or the saved password can't be decrypted. */
    fun load(context: Context): Saved? {
        val p = prefs(context)
        val ssid = p.getString(PREF_SSID, null) ?: return null
        val password = loadPassword(context, ssid) ?: return null
        val host = p.getString(PREF_HOST, null)?.ifEmpty { null } ?: "192.168.8.46"
        val port = p.getString(PREF_PORT, null)?.toIntOrNull() ?: 8080
        return Saved(ssid, password, host, port)
    }

    fun save(context: Context, ssid: String, host: String, port: String) {
        prefs(context).edit()
            .putString(PREF_SSID, ssid)
            .putString(PREF_HOST, host)
            .putString(PREF_PORT, port)
            .apply()
    }

    fun savePassword(context: Context, ssid: String, password: String) {
        try {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            prefs(context).edit()
                .putString(PREF_SSID, ssid)
                .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
        } catch (error: Exception) {
            BridgeLog.detail("Could not save password: ${error.message}")
        }
    }

    fun loadPassword(context: Context, ssid: String): String? {
        val p = prefs(context)
        if (ssid != p.getString(PREF_SSID, null)) return null
        val ciphertextText = p.getString(PREF_CIPHERTEXT, null) ?: return null
        val ivText = p.getString(PREF_IV, null) ?: return null
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ciphertextText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (error: Exception) {
            null
        }
    }

    fun hasSavedPassword(context: Context): Boolean {
        val p = prefs(context)
        return p.contains(PREF_SSID) && p.contains(PREF_CIPHERTEXT) && p.contains(PREF_IV)
    }

    fun savedSsid(context: Context): String? = prefs(context).getString(PREF_SSID, null)
    fun savedHost(context: Context): String = prefs(context).getString(PREF_HOST, null)?.ifEmpty { null } ?: "192.168.8.46"
    fun savedPort(context: Context): String = prefs(context).getString(PREF_PORT, "") ?: ""

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}

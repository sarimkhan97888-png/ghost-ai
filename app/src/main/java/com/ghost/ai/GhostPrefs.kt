package com.ghost.ai

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
 * Stores the Gemini API key in private SharedPreferences, encrypted with an
 * AES-256-GCM key that lives in the Android Keystore (no extra dependencies).
 */
object GhostPrefs {

    private const val PREFS_NAME = "ghost_ai_prefs"
    private const val KEY_API = "gemini_api_key"

    /** SharedPreferences key of the hands-free wake word switch. */
    const val KEY_WAKE = "wake_word_enabled"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ghost_ai_api_key_alias"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX_ENC = "enc:"
    private const val PREFIX_PLAIN = "plain:"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveApiKey(context: Context, apiKey: String) {
        val clean = apiKey.trim()
        val stored = try {
            PREFIX_ENC + encrypt(clean)
        } catch (e: Exception) {
            // Some devices ship a broken Keystore. Fall back to app-private storage
            // (still sandboxed to this app) instead of losing the key.
            PREFIX_PLAIN + Base64.encodeToString(clean.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        prefs(context).edit().putString(KEY_API, stored).apply()
    }

    fun getApiKey(context: Context): String {
        val stored = prefs(context).getString(KEY_API, null) ?: return ""
        return try {
            when {
                stored.startsWith(PREFIX_ENC) -> decrypt(stored.removePrefix(PREFIX_ENC))
                stored.startsWith(PREFIX_PLAIN) -> String(
                    Base64.decode(stored.removePrefix(PREFIX_PLAIN), Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                else -> ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun hasApiKey(context: Context): Boolean = getApiKey(context).isNotBlank()

    /** Hands-free "Ghost" / "Jarvis" activation. On by default. */
    fun isWakeEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WAKE, true)

    fun setWakeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE, enabled).apply()
    }

    fun registerListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun maskedKey(key: String): String =
        if (key.length <= 8) "****" else key.take(4) + "..." + key.takeLast(4)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(":")
        require(parts.size == 2) { "Malformed payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}

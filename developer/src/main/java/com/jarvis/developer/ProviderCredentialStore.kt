package com.jarvis.developer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-install encrypted provider credentials. The AES key never leaves Android Keystore. */
internal object ProviderCredentialStore {
    private const val ALIAS = "jarvis_developer_provider_credentials_v1"
    private const val PREFS = "developer_provider_credentials"
    private const val IV_SIZE = 12

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    fun save(context: Context, provider: String, apiKey: String, model: String) {
        require(provider == "Gemini" || provider == "OpenAI")
        require(apiKey.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.iv + cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("provider", provider)
            .putString("model", model)
            .putString("encrypted_key", Base64.encodeToString(data, Base64.NO_WRAP))
            .commit().also { check(it) }
    }

    fun load(context: Context): Triple<String, String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString("encrypted_key", null) ?: return null
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > IV_SIZE + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, data.copyOfRange(0, IV_SIZE)))
            val key = String(cipher.doFinal(data.copyOfRange(IV_SIZE, data.size)), Charsets.UTF_8)
            Triple(prefs.getString("provider", "Gemini") ?: "Gemini",
                key, prefs.getString("model", "") ?: "")
        } catch (_: Exception) {
            // Never return corrupt or undecryptable credentials.
            null
        }
    }

    /** Encrypt a GitHub credential separately from provider keys, using the device Keystore. */
    fun saveGithub(context: Context, token: String) {
        require(token.isNotBlank() && token.length <= 4096 && token.none { it.isISOControl() })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.iv + cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("github_token", Base64.encodeToString(data, Base64.NO_WRAP)).commit())
    }

    fun loadGithub(context: Context): String? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("github_token", null) ?: return null
        return runCatching {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            require(data.size > IV_SIZE + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(),
                GCMParameterSpec(128, data.copyOfRange(0, IV_SIZE)))
            String(cipher.doFinal(data.copyOfRange(IV_SIZE, data.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun clearGithub(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("github_token").commit())
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

package com.assistant.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores OAuth credentials encrypted with a non-exportable Android Keystore AES-GCM key. */
internal class SecureTokenStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_oauth_secure", Context.MODE_PRIVATE)
    private val alias = "jarvis_openai_oauth_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(accessToken: String, refreshToken: String?, clientId: String, subject: String) {
        require(accessToken.isNotBlank() && clientId.startsWith("oaiapp_") && subject.isNotBlank())
        val plaintext = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken ?: "")
            .put("client_id", clientId)
            .put("subject", subject)
            .toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plaintext)
        prefs.edit()
            .putString("blob", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun load(): JSONObject? {
        val blob = prefs.getString("blob", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            JSONObject(String(cipher.doFinal(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (_: Exception) {
            clear()
            null
        }
    }
}

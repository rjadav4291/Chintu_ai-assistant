package com.chintu.assistant

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Keeps keys encrypted (AES-256-GCM) with a key held by the Android Keystore.
// The saved text starts with "enc1:". An old plain-text value is encrypted the first time it is read.
object KeyVault {
    private const val ALIAS = "chintu_api_key"
    private const val NAME = "openai_key"
    private const val PREFIX = "enc1:"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = c.iv
        val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? {
        return try {
            val parts = stored.removePrefix(PREFIX).split(":")
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // Returns the saved value, or "" if none (or if it can't be decrypted).
    fun load(p: SharedPreferences, name: String = NAME): String {
        val v = p.getString(name, null) ?: return ""
        if (v.startsWith(PREFIX)) return decrypt(v) ?: ""
        if (v.isBlank()) return ""
        try {
            val e = encrypt(v)
            if (decrypt(e) == v) p.edit().putString(name, e).commit()
        } catch (ex: Exception) {
        }
        return v
    }

    // True only if the value was saved AND read back correctly.
    fun save(p: SharedPreferences, key: String, name: String = NAME): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        return try {
            p.edit().putString(name, encrypt(k)).commit() && load(p, name) == k
        } catch (e: Exception) {
            false
        }
    }

    fun clear(p: SharedPreferences, name: String = NAME): Boolean = p.edit().remove(name).commit()
}

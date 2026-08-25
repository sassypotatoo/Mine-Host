package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small Android Keystore backed key/value store.
 *
 * Values are encrypted with AES/GCM. Only ciphertext, IV and format version are
 * written to SharedPreferences. The AES key is non-exportable and remains in
 * Android Keystore.
 */
class EncryptedPreferences(
    context: Context,
    namespace: String,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("minehost_secure_$namespace", Context.MODE_PRIVATE)
    private val alias = "minehost_secure_$namespace"

    fun putString(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        prefs.edit()
            .putInt("${key}_version", FORMAT_VERSION)
            .putString("${key}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${key}_ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun getString(key: String): String? {
        val version = prefs.getInt("${key}_version", 0)
        val iv = prefs.getString("${key}_iv", null) ?: return null
        val ciphertext = prefs.getString("${key}_ciphertext", null) ?: return null
        if (version != FORMAT_VERSION) {
            remove(key)
            return null
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(
                cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)),
                StandardCharsets.UTF_8,
            )
        }.getOrElse {
            // A changed lock-screen/key state or corrupt preferences must not leave
            // unreadable secrets around forever.
            remove(key)
            null
        }
    }

    fun remove(key: String) {
        prefs.edit()
            .remove("${key}_version")
            .remove("${key}_iv")
            .remove("${key}_ciphertext")
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION = 1
    }
}

package com.example.tunnel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class TunnelCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("minehost_tunnel_credentials", Context.MODE_PRIVATE)
    private val keyAlias = "minehost_frp_token_aes_v1"

    fun saveToken(serverId: String, token: String) {
        if (token.isBlank()) {
            prefs.edit().remove("$serverId.iv").remove("$serverId.data").apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("$serverId.iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$serverId.data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun readToken(serverId: String): String? = runCatching {
        val iv = prefs.getString("$serverId.iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val data = prefs.getString("$serverId.data", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(data).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

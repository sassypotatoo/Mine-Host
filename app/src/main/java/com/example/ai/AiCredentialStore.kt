package com.example.ai

import android.content.Context
import com.example.security.EncryptedPreferences

/** Stores only a user-supplied key in Android Keystore-backed encrypted preferences. */
class AiCredentialStore(context: Context) {
    private val store = EncryptedPreferences(context.applicationContext, "ai_credentials")

    fun saveUserGeminiKey(rawKey: String): Result<Unit> = runCatching {
        val key = rawKey.trim()
        require(key.length in 20..512) { "The AI API key length is invalid." }
        require(key.none(Char::isWhitespace)) { "The AI API key contains whitespace." }
        store.putString(KEY_GEMINI, key)
    }

    fun readUserGeminiKey(): String? = store.getString(KEY_GEMINI)?.takeIf(String::isNotBlank)

    fun clearUserGeminiKey() = store.remove(KEY_GEMINI)

    fun hasUserGeminiKey(): Boolean = !readUserGeminiKey().isNullOrBlank()

    companion object { private const val KEY_GEMINI = "gemini_api_key" }
}

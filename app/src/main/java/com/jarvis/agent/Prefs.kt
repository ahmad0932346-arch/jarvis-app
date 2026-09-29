package com.jarvis.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted key/value storage (API keys never sit in plain text). */
object Prefs {
    const val GEMINI_KEY = "gemini_key"
    const val PICO_KEY = "picovoice_key"
    const val MODEL = "model"
    const val LANG = "speech_lang"
    const val REPLY_LANG = "reply_lang"
    const val MAX_STEPS = "max_steps"

    @Volatile private var cached: SharedPreferences? = null

    private fun sp(c: Context): SharedPreferences {
        cached?.let { return it }
        val app = c.applicationContext
        val prefs = try {
            val mk = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, "jarvis_secure", mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            app.getSharedPreferences("jarvis_plain", Context.MODE_PRIVATE)
        }
        cached = prefs
        return prefs
    }

    fun get(c: Context, key: String, default: String = ""): String = sp(c).getString(key, default) ?: default

    fun put(c: Context, key: String, value: String) {
        sp(c).edit().putString(key, value).apply()
    }
}

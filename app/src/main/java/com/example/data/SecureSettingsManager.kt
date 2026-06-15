package com.example.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureSettingsManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secret_shared_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveTextApiKey(key: String) {
        sharedPreferences.edit().putString("text_api_key", key).apply()
    }

    fun getTextApiKey(): String {
        return sharedPreferences.getString("text_api_key", "") ?: ""
    }

    fun clearTextApiKey() {
        sharedPreferences.edit().remove("text_api_key").apply()
    }
}

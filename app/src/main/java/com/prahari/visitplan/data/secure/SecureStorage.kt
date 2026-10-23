package com.prahari.visitplan.data.secure

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "visitplan_prefs")

/**
 * Secure storage — Prahari token + Gemini key
 * DataStore me save hota hai (phone ke andar, app ke private storage me)
 * Kabhi code me ya GitHub pe save nahi hota
 */
class SecureStorage(private val context: Context) {

    companion object {
        private val KEY_TOKEN = stringPreferencesKey("prahari_token")
        private val KEY_USERNAME = stringPreferencesKey("prahari_username")
        private val KEY_GEMINI = stringPreferencesKey("gemini_key")
    }

    suspend fun savePrahariToken(token: String) {
        context.dataStore.edit { it[KEY_TOKEN] = token }
    }

    suspend fun getPrahariToken(): String? {
        return context.dataStore.data.map { it[KEY_TOKEN] }.first()
    }

    suspend fun savePrahariUsername(username: String) {
        context.dataStore.edit { it[KEY_USERNAME] = username }
    }

    suspend fun getPrahariUsername(): String? {
        return context.dataStore.data.map { it[KEY_USERNAME] }.first()
    }

    suspend fun saveGeminiKey(key: String) {
        context.dataStore.edit { it[KEY_GEMINI] = key }
    }

    suspend fun getGeminiKey(): String? {
        return context.dataStore.data.map { it[KEY_GEMINI] }.first()
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }
}

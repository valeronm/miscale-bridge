package com.miscalebridge.app.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProfileStore(private val ds: DataStore<Preferences>) {
    val config: Flow<UserProfile?> = ds.data.map { prefs ->
        prefs[KEY]?.let { runCatching { Json.decodeFromString<UserProfile>(it) }.getOrNull() }
    }

    val autoWrite: Flow<Boolean> = ds.data.map { it[AUTO_WRITE] ?: false }

    suspend fun save(profile: UserProfile) {
        ds.edit { it[KEY] = Json.encodeToString(profile) }
    }

    suspend fun setAutoWrite(enabled: Boolean) {
        ds.edit { it[AUTO_WRITE] = enabled }
    }

    private companion object {
        val KEY = stringPreferencesKey("user_profile_json")
        val AUTO_WRITE = booleanPreferencesKey("auto_write_hc")
    }
}

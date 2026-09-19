package com.autoclickerplus.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.normalized
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.automationDataStore by preferencesDataStore(name = "automation")

class AutomationRepository(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    val config: Flow<AutomationConfig> = context.automationDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            preferences[CONFIG_KEY]
                ?.let { runCatching { json.decodeFromString<AutomationConfig>(it) }.getOrNull() }
                ?.normalized()
                ?: AutomationConfig()
        }

    suspend fun save(config: AutomationConfig) {
        context.automationDataStore.edit { preferences ->
            preferences[CONFIG_KEY] = json.encodeToString(config.normalized())
        }
    }

    private companion object {
        val CONFIG_KEY = stringPreferencesKey("automation_config")
    }
}

package com.autoclickerplus.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.autoclickerplus.model.AutomationConfig
import com.autoclickerplus.model.ScriptLibrary
import com.autoclickerplus.model.ScriptLibraryEditor
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

    val library: Flow<ScriptLibrary> = context.automationDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            decodeLibrary(preferences)
        }

    val config: Flow<AutomationConfig> = library.map { it.activeScript.config }

    suspend fun save(config: AutomationConfig) {
        context.automationDataStore.edit { preferences ->
            val library = decodeLibrary(preferences)
            val updated = ScriptLibraryEditor.updateActiveConfig(library) { config }
            preferences[LIBRARY_KEY] = json.encodeToString(updated)
            preferences.remove(CONFIG_KEY)
        }
    }

    suspend fun saveLibrary(library: ScriptLibrary) {
        context.automationDataStore.edit { preferences ->
            preferences[LIBRARY_KEY] = json.encodeToString(library.normalized())
            preferences.remove(CONFIG_KEY)
        }
    }

    suspend fun migrateLegacyIfNeeded() {
        context.automationDataStore.edit { preferences ->
            if (preferences[LIBRARY_KEY] == null) {
                preferences[LIBRARY_KEY] = json.encodeToString(decodeLibrary(preferences))
                preferences.remove(CONFIG_KEY)
            }
        }
    }

    private fun decodeLibrary(preferences: Preferences): ScriptLibrary {
        preferences[LIBRARY_KEY]
            ?.let { runCatching { json.decodeFromString<ScriptLibrary>(it) }.getOrNull() }
            ?.let { return it.normalized() }

        val oldConfig = preferences[CONFIG_KEY]
            ?.let { runCatching { json.decodeFromString<AutomationConfig>(it) }.getOrNull() }
            ?.normalized()
        if (oldConfig != null) {
            return ScriptLibrary.fromLegacy(oldConfig)
        }
        return ScriptLibrary.default()
    }

    private companion object {
        val CONFIG_KEY = stringPreferencesKey("automation_config")
        val LIBRARY_KEY = stringPreferencesKey("script_library")
    }
}

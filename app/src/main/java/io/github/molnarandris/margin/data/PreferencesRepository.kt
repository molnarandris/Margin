package io.github.molnarandris.margin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "margin_prefs")

class PreferencesRepository(private val context: Context) {

    private val directoryUriKey = stringPreferencesKey("directory_uri")

    val directoryUriString: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[directoryUriKey]
    }

    suspend fun saveDirectoryUri(uriString: String) {
        context.dataStore.edit { prefs ->
            prefs[directoryUriKey] = uriString
        }
    }
}

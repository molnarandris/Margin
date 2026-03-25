package io.github.molnarandris.margin.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "margin_prefs")

class PreferencesRepository(private val context: Context) {

    private val directoryUriKey = stringPreferencesKey("directory_uri")
    private val penColorKey = stringPreferencesKey("pen_color")
    private val penThicknessKey = stringPreferencesKey("pen_thickness")
    private fun lastPageKey(uri: Uri) = intPreferencesKey("last_page_${uri.toString().hashCode()}")

    val directoryUriString: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[directoryUriKey]
    }

    suspend fun saveDirectoryUri(uriString: String) {
        context.dataStore.edit { prefs ->
            prefs[directoryUriKey] = uriString
        }
    }

    suspend fun savePenColor(name: String) {
        context.dataStore.edit { it[penColorKey] = name }
    }

    suspend fun getPenColor(): String? = context.dataStore.data.map { it[penColorKey] }.first()

    suspend fun savePenThickness(name: String) {
        context.dataStore.edit { it[penThicknessKey] = name }
    }

    suspend fun getPenThickness(): String? = context.dataStore.data.map { it[penThicknessKey] }.first()

    suspend fun saveLastPage(uri: Uri, page: Int) {
        context.dataStore.edit { it[lastPageKey(uri)] = page }
    }

    suspend fun getLastPage(uri: Uri): Int? = context.dataStore.data.map { it[lastPageKey(uri)] }.first()

    suspend fun clearPdfData(uri: Uri) {
        context.dataStore.edit { it.remove(lastPageKey(uri)) }
    }
}

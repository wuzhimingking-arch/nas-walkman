package tech.peakedge.naswalkman.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

data class AppSettings(
    val cacheLimitBytes: Long = 5L * 1024L * 1024L * 1024L,
    val allowMobileCache: Boolean = false,
    val autoConnectOnStart: Boolean = true,
    val autoScanOnStart: Boolean = false,
    val themeMode: String = "system",
    val language: String = "system",
)

class SettingsStore(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            cacheLimitBytes = prefs[CACHE_LIMIT_BYTES] ?: 5L * 1024L * 1024L * 1024L,
            allowMobileCache = prefs[ALLOW_MOBILE_CACHE] ?: false,
            autoConnectOnStart = prefs[AUTO_CONNECT] ?: true,
            autoScanOnStart = prefs[AUTO_SCAN] ?: false,
            themeMode = prefs[THEME_MODE] ?: "system",
            language = prefs[LANGUAGE] ?: "system",
        )
    }

    suspend fun setCacheLimit(bytes: Long) {
        context.settingsDataStore.edit { it[CACHE_LIMIT_BYTES] = bytes }
    }

    suspend fun setAllowMobileCache(value: Boolean) {
        context.settingsDataStore.edit { it[ALLOW_MOBILE_CACHE] = value }
    }

    suspend fun setAutoConnect(value: Boolean) {
        context.settingsDataStore.edit { it[AUTO_CONNECT] = value }
    }

    suspend fun setAutoScan(value: Boolean) {
        context.settingsDataStore.edit { it[AUTO_SCAN] = value }
    }

    suspend fun setThemeMode(value: String) {
        context.settingsDataStore.edit { it[THEME_MODE] = value }
    }

    suspend fun setLanguage(value: String) {
        context.settingsDataStore.edit { it[LANGUAGE] = value }
    }

    private companion object {
        val CACHE_LIMIT_BYTES = longPreferencesKey("cache_limit_bytes")
        val ALLOW_MOBILE_CACHE = booleanPreferencesKey("allow_mobile_cache")
        val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
        val AUTO_SCAN = booleanPreferencesKey("auto_scan")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
    }
}

package com.nova.files.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore by preferencesDataStore("nova_settings")

enum class ThemeChoice(val label: String) {
    SPECTRUM("Spectrum Flow"), MIDNIGHT("Midnight Flow"), MINIMAL("Pure Minimal"),
    CYBER("Cyber Pulse"), CLAY("Soft Clay"), GLASS("Glass Studio")
}
enum class DarkMode(val label: String) { SYSTEM("System"), DARK("Dark"), LIGHT("Light") }
enum class SortBy(val label: String) { NAME("Name"), DATE("Date"), SIZE("Size"), TYPE("Type") }

data class AppSettings(
    val theme: ThemeChoice = ThemeChoice.SPECTRUM,
    val darkMode: DarkMode = DarkMode.DARK,
    val deviceName: String = "",
    val receiveDir: String = "",
    val autoAcceptTrusted: Boolean = false,
    val discoverable: Boolean = true,
    val showHidden: Boolean = false,
    val gridView: Boolean = false,
    val sortBy: SortBy = SortBy.NAME,
    val sortDesc: Boolean = false,
)

class SettingsRepository(private val ctx: Context, scope: CoroutineScope) {
    private object K {
        val theme = stringPreferencesKey("theme"); val dark = stringPreferencesKey("dark")
        val name = stringPreferencesKey("device_name"); val recv = stringPreferencesKey("receive_dir")
        val auto = booleanPreferencesKey("auto_accept_trusted"); val disc = booleanPreferencesKey("discoverable")
        val hidden = booleanPreferencesKey("show_hidden"); val grid = booleanPreferencesKey("grid")
        val sort = stringPreferencesKey("sort"); val desc = booleanPreferencesKey("sort_desc")
    }

    private fun map(p: Preferences) = AppSettings(
        theme = p[K.theme]?.let { runCatching { ThemeChoice.valueOf(it) }.getOrNull() } ?: ThemeChoice.SPECTRUM,
        darkMode = p[K.dark]?.let { runCatching { DarkMode.valueOf(it) }.getOrNull() } ?: DarkMode.DARK,
        deviceName = p[K.name] ?: "",
        receiveDir = p[K.recv] ?: "",
        autoAcceptTrusted = p[K.auto] ?: false,
        discoverable = p[K.disc] ?: true,
        showHidden = p[K.hidden] ?: false,
        gridView = p[K.grid] ?: false,
        sortBy = p[K.sort]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME,
        sortDesc = p[K.desc] ?: false,
    )

    val settings: StateFlow<AppSettings> = ctx.dataStore.data.map(::map).stateIn(scope, SharingStarted.Eagerly, AppSettings())
    private val scope = scope

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { ctx.dataStore.edit(block) }
    }

    suspend fun load(): AppSettings = map(ctx.dataStore.data.first())

    fun setTheme(t: ThemeChoice) = edit { it[K.theme] = t.name }
    fun setDarkMode(d: DarkMode) = edit { it[K.dark] = d.name }
    fun setDeviceName(n: String) = edit { it[K.name] = n.trim().take(40) }
    fun setReceiveDir(d: String) = edit { it[K.recv] = d }
    fun setAutoAccept(b: Boolean) = edit { it[K.auto] = b }
    fun setDiscoverable(b: Boolean) = edit { it[K.disc] = b }
    fun setShowHidden(b: Boolean) = edit { it[K.hidden] = b }
    fun setGrid(b: Boolean) = edit { it[K.grid] = b }
    fun setSort(s: SortBy, desc: Boolean) = edit { it[K.sort] = s.name; it[K.desc] = desc }
}

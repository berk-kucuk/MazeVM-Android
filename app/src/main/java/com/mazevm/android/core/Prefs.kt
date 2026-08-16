package com.mazevm.android.core

import android.content.Context
import androidx.core.content.edit
import com.mazevm.android.ui.theme.AccentColor
import com.mazevm.android.ui.theme.ThemeVariant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User-visible settings. Small enough that SharedPreferences is the right tool. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("maze_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state

    data class Settings(
        val theme: ThemeVariant,
        val accent: AccentColor,
        val language: AppLanguage,
        val defaultCpus: Int,
        val defaultRamMb: Int,
        val keepScreenOn: Boolean,
        val wifiOnlyDownloads: Boolean,
    )

    private fun read() = Settings(
        theme = ThemeVariant.fromName(sp.getString(KEY_THEME, null)),
        accent = AccentColor.fromName(sp.getString(KEY_ACCENT, null)),
        language = AppLanguage.fromTag(sp.getString(KEY_LANGUAGE, null)),
        defaultCpus = sp.getInt(KEY_CPUS, 2),
        defaultRamMb = sp.getInt(KEY_RAM, 2048),
        keepScreenOn = sp.getBoolean(KEY_KEEP_AWAKE, true),
        wifiOnlyDownloads = sp.getBoolean(KEY_WIFI_ONLY, false),
    )

    private fun mutate(block: android.content.SharedPreferences.Editor.() -> Unit) {
        sp.edit(action = block)
        _state.value = read()
    }

    fun setTheme(value: ThemeVariant) = mutate { putString(KEY_THEME, value.name) }
    fun setAccent(value: AccentColor) = mutate { putString(KEY_ACCENT, value.name) }
    fun setLanguage(value: AppLanguage) = mutate { putString(KEY_LANGUAGE, value.tag) }
    fun setDefaultCpus(value: Int) = mutate { putInt(KEY_CPUS, value) }
    fun setDefaultRamMb(value: Int) = mutate { putInt(KEY_RAM, value) }
    fun setKeepScreenOn(value: Boolean) = mutate { putBoolean(KEY_KEEP_AWAKE, value) }
    fun setWifiOnlyDownloads(value: Boolean) = mutate { putBoolean(KEY_WIFI_ONLY, value) }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_ACCENT = "accent"
        const val KEY_LANGUAGE = "language"
        const val KEY_CPUS = "default_cpus"
        const val KEY_RAM = "default_ram_mb"
        const val KEY_KEEP_AWAKE = "keep_screen_on"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}

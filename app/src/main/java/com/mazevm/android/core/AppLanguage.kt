package com.mazevm.android.core

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * MazeVM ships exactly two translations. Anything outside this set is not offered,
 * and `resourceConfigurations` in the Gradle file keeps other locales out of the APK.
 */
enum class AppLanguage(val tag: String) {
    /** Use whichever of the two matches the device, falling back to English. */
    System(""),
    English("en"),
    Turkish("tr");

    companion object {
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: System
    }
}

/**
 * Applies [language] through AppCompat, which persists the choice itself (via the
 * metadata service declared in the manifest on API < 33, and via the platform
 * per-app locale API on 33+). Safe to call from any thread.
 */
fun applyAppLanguage(language: AppLanguage) {
    val locales = if (language == AppLanguage.System) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(language.tag)
    }
    AppCompatDelegate.setApplicationLocales(locales)
}

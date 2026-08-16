package com.mazevm.android.core

import java.util.Locale
import kotlin.math.abs

/** Binary-prefix byte formatting, e.g. `1.4 GiB`. Locale-aware decimal separator. */
fun formatBytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    val decimals = if (value >= 100 || unit == 0) 0 else 1
    return String.format(locale, "%.${decimals}f %s", value, units[unit])
}

/** `2.4 MiB/s`, for download rows. */
fun formatRate(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String =
    formatBytes(bytesPerSecond, locale)

/** Memory sizes are always expressed in MiB internally; show GiB once it is tidy. */
fun formatMemory(mb: Int, locale: Locale = Locale.getDefault()): String =
    if (mb >= 1024 && mb % 512 == 0) {
        val gib = mb / 1024.0
        val decimals = if (gib == gib.toInt().toDouble()) 0 else 1
        String.format(locale, "%.${decimals}f GiB", gib)
    } else {
        "$mb MiB"
    }

/** `1h 04m`, `12m 30s`, `45s`. Used for VM uptime. */
fun formatDuration(millis: Long, locale: Locale = Locale.getDefault()): String {
    val total = abs(millis) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return when {
        hours > 0 -> String.format(locale, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(locale, "%dm %02ds", minutes, seconds)
        else -> String.format(locale, "%ds", seconds)
    }
}

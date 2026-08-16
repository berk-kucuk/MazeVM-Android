package com.mazevm.android.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Whether Android will leave a running machine alone.
 *
 * A foreground service and a wake lock keep the CPU on, but they do not exempt the app
 * from Doze and from the vendor "battery optimisation" layers that sit on top of it,
 * several of which will freeze or kill a background process regardless. A machine that
 * is part-way through an install has no way to recover from that, so the exemption is
 * worth asking for — and worth telling the user about rather than letting them find out
 * from a half-installed system in the morning.
 */
object BatteryPolicy {

    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the system dialog asking for the exemption.
     *
     * Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which Google Play restricts to a
     * short list of app categories. MazeVM is distributed outside Play, where the
     * permission behaves normally; a Play build would have to fall back to
     * [openSettings] and let the user grant it by hand.
     */
    fun requestExemption(context: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrElse { openSettings(context) }

    /** The list of every app's battery setting, for devices that refuse the direct ask. */
    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}

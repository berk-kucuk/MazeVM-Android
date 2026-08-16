package com.mazevm.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazevm.android.BuildConfig
import com.mazevm.android.R
import com.mazevm.android.core.BatteryPolicy
import com.mazevm.android.core.AppLanguage
import com.mazevm.android.core.applyAppLanguage
import com.mazevm.android.core.formatBytes
import com.mazevm.android.core.formatMemory
import com.mazevm.android.ui.MazeViewModel
import com.mazevm.android.ui.NavigationBarClearance
import com.mazevm.android.ui.components.BrandHeader
import com.mazevm.android.ui.components.MazeCard
import com.mazevm.android.ui.components.SectionHeader
import com.mazevm.android.ui.components.SettingRow
import com.mazevm.android.ui.components.SwitchRow
import com.mazevm.android.ui.theme.AccentColor
import com.mazevm.android.ui.theme.ThemeVariant
import com.mazevm.android.ui.theme.maze

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MazeViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val prefs = viewModel.prefs

    val context = LocalContext.current
    var qemuVersion by remember { mutableStateOf<String?>(null) }
    var usedBytes by remember { mutableStateOf(0L) }
    var freeBytes by remember { mutableStateOf(0L) }

    // Re-read on every resume: the exemption is granted in a system dialog, so the
    // answer only arrives when the user comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    var batteryExempt by remember { mutableStateOf(BatteryPolicy.isExempt(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BatteryPolicy.isExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        qemuVersion = viewModel.runtime.version()
        usedBytes = viewModel.storage.usedBytes()
        freeBytes = viewModel.storage.freeBytes()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BrandHeader()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.settings_section_appearance))
            MazeCard {
                ChoiceRow(
                    title = stringResource(R.string.settings_theme_oled),
                    subtitle = stringResource(R.string.settings_theme_oled_desc),
                    selected = settings.theme == ThemeVariant.Oled,
                    onClick = { prefs.setTheme(ThemeVariant.Oled) },
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_theme_dark),
                    selected = settings.theme == ThemeVariant.DarkGrey,
                    onClick = { prefs.setTheme(ThemeVariant.DarkGrey) },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_accent),
                    style = MaterialTheme.typography.labelMedium,
                    color = maze.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentColor.entries.forEach { accent ->
                        AccentSwatch(
                            colour = accent.color,
                            selected = settings.accent == accent,
                            onClick = { prefs.setAccent(accent) },
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_section_language))
            MazeCard {
                // MazeVM ships two translations only; there is deliberately no third
                // option and no "add language" affordance.
                LanguageRow(
                    label = stringResource(R.string.settings_language_system),
                    language = AppLanguage.System,
                    current = settings.language,
                    onSelect = {
                        prefs.setLanguage(it)
                        applyAppLanguage(it)
                    },
                )
                LanguageRow(
                    label = stringResource(R.string.settings_language_english),
                    language = AppLanguage.English,
                    current = settings.language,
                    onSelect = {
                        prefs.setLanguage(it)
                        applyAppLanguage(it)
                    },
                )
                LanguageRow(
                    label = stringResource(R.string.settings_language_turkish),
                    language = AppLanguage.Turkish,
                    current = settings.language,
                    onSelect = {
                        prefs.setLanguage(it)
                        applyAppLanguage(it)
                    },
                )
            }

            SectionHeader(stringResource(R.string.settings_section_defaults))
            MazeCard {
                SettingRow(
                    title = stringResource(R.string.settings_default_cpus),
                    trailing = {
                        Stepper(
                            value = settings.defaultCpus,
                            range = 1..8,
                            onChange = prefs::setDefaultCpus,
                            format = { "$it" },
                        )
                    },
                )
                SettingRow(
                    title = stringResource(R.string.settings_default_ram),
                    trailing = {
                        Stepper(
                            value = settings.defaultRamMb,
                            range = 512..8192,
                            step = 512,
                            onChange = prefs::setDefaultRamMb,
                            format = { formatMemory(it) },
                        )
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_keep_awake),
                    checked = settings.keepScreenOn,
                    onCheckedChange = prefs::setKeepScreenOn,
                )
                SettingRow(
                    title = stringResource(R.string.settings_background),
                    subtitle = if (batteryExempt) {
                        stringResource(R.string.settings_background_granted)
                    } else {
                        stringResource(R.string.settings_background_desc)
                    },
                    onClick = {
                        if (!batteryExempt) BatteryPolicy.requestExemption(context)
                        else BatteryPolicy.openSettings(context)
                    },
                    trailing = {
                        if (batteryExempt) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = maze.success,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.settings_background_action),
                                style = MaterialTheme.typography.labelLarge,
                                color = maze.accent,
                            )
                        }
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_wifi_only),
                    checked = settings.wifiOnlyDownloads,
                    onCheckedChange = prefs::setWifiOnlyDownloads,
                )
            }

            SectionHeader(stringResource(R.string.settings_section_storage))
            MazeCard {
                SettingRow(
                    title = stringResource(R.string.settings_storage_used, formatBytes(usedBytes)),
                    subtitle = stringResource(
                        R.string.settings_storage_free,
                        formatBytes(freeBytes),
                    ),
                )
            }

            SectionHeader(stringResource(R.string.settings_section_about))
            MazeCard {
                SettingRow(
                    title = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    subtitle = qemuVersion
                        ?.let { stringResource(R.string.settings_qemu_version, it) }
                        ?: stringResource(R.string.setup_missing_title),
                )
                if (qemuVersion == null) {
                    Text(
                        text = stringResource(R.string.setup_missing_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = maze.warning,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.error_no_kvm),
                    style = MaterialTheme.typography.bodySmall,
                    color = maze.textTertiary,
                )
            }

            Spacer(Modifier.height(24.dp + NavigationBarClearance))
        }
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = maze.accent)
            }
        },
    )
}

@Composable
private fun LanguageRow(
    label: String,
    language: AppLanguage,
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    ChoiceRow(
        title = label,
        selected = current == language,
        onClick = { onSelect(language) },
    )
}

@Composable
private fun AccentSwatch(colour: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(colour)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.background,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Stepper(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    format: (Int) -> String,
    step: Int = 1,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StepperButton("−", enabled = value > range.first) {
            onChange((value - step).coerceIn(range))
        }
        Text(
            text = format(value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        StepperButton("+", enabled = value < range.last) {
            onChange((value + step).coerceIn(range))
        }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(maze.surface2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) maze.accent else maze.textTertiary,
        )
    }
}

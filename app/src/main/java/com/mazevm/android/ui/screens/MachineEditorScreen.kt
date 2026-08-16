package com.mazevm.android.ui.screens

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazevm.android.R
import com.mazevm.android.core.formatMemory
import com.mazevm.android.data.model.Arch
import com.mazevm.android.data.model.DisplayMode
import com.mazevm.android.data.model.PortForward
import com.mazevm.android.data.model.VmConfig
import com.mazevm.android.ui.MazeViewModel
import com.mazevm.android.ui.components.MazeCard
import com.mazevm.android.ui.components.MonoText
import com.mazevm.android.ui.components.SectionHeader
import com.mazevm.android.ui.components.SwitchRow
import com.mazevm.android.ui.theme.maze
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineEditorScreen(
    viewModel: MazeViewModel,
    vmId: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val existing = remember(vmId) { vmId?.let { viewModel.findMachine(it) } }
    var config by remember { mutableStateOf(existing ?: viewModel.defaultMachine()) }
    var nameError by remember { mutableStateOf(false) }

    val deviceRamMb = remember { deviceMemoryMb(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    stringResource(
                        if (existing == null) R.string.editor_new_title
                        else R.string.editor_edit_title
                    )
                )
            },
            navigationIcon = {
                IconButton(onClick = onDone) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            },
            actions = {
                TextButton(
                    onClick = {
                        if (config.name.isBlank()) {
                            nameError = true
                        } else {
                            viewModel.save(config)
                            onDone()
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_save), color = maze.accent)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionHeader(stringResource(R.string.editor_section_general))
            MazeCard {
                MazeTextField(
                    value = config.name,
                    onValueChange = { config = config.copy(name = it); nameError = false },
                    label = stringResource(R.string.editor_name),
                    placeholder = stringResource(R.string.editor_name_hint),
                    isError = nameError,
                    supportingText = if (nameError) {
                        stringResource(R.string.editor_error_name_required)
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(12.dp))
                Picker(
                    label = stringResource(R.string.editor_arch),
                    selected = config.arch.label,
                    options = Arch.entries.map { it.label },
                    onSelect = { index -> config = config.copy(arch = Arch.entries[index]) },
                )
            }

            SectionHeader(stringResource(R.string.editor_section_hardware))
            MazeCard {
                StepperRow(
                    label = stringResource(R.string.editor_cpu_count),
                    value = config.cpuCount,
                    range = 1..8,
                    format = { "$it" },
                    onChange = { config = config.copy(cpuCount = it) },
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "${stringResource(R.string.editor_ram)}  ·  ${formatMemory(config.ramMb)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(
                    value = config.ramMb.toFloat(),
                    onValueChange = {
                        // Snap to 256 MiB so the label never shows an odd figure.
                        config = config.copy(ramMb = (it / 256f).roundToInt() * 256)
                    },
                    valueRange = 256f..(deviceRamMb * 0.75f).coerceAtLeast(2048f),
                    colors = SliderDefaults.colors(
                        thumbColor = maze.accent,
                        activeTrackColor = maze.accent,
                        inactiveTrackColor = maze.surface3,
                    ),
                )
                if (config.ramMb > deviceRamMb / 2) {
                    Text(
                        text = stringResource(R.string.editor_ram_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = maze.warning,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Picker(
                    label = stringResource(R.string.editor_cpu_model),
                    selected = config.cpuModel,
                    options = cpuModels(config.arch),
                    onSelect = { index ->
                        config = config.copy(cpuModel = cpuModels(config.arch)[index])
                    },
                )
            }

            SectionHeader(stringResource(R.string.editor_section_storage))
            MazeCard {
                PathRow(
                    label = stringResource(R.string.editor_disk),
                    path = config.diskPath,
                    onClear = { config = config.copy(diskPath = null) },
                )
                Spacer(Modifier.height(10.dp))
                PathRow(
                    label = stringResource(R.string.editor_cdrom),
                    path = config.cdromPath,
                    onClear = { config = config.copy(cdromPath = null) },
                )
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = stringResource(R.string.editor_snapshot),
                    subtitle = stringResource(R.string.editor_snapshot_desc),
                    checked = config.snapshotMode,
                    onCheckedChange = { config = config.copy(snapshotMode = it) },
                )
            }

            SectionHeader(stringResource(R.string.editor_section_display))
            MazeCard {
                Picker(
                    label = stringResource(R.string.editor_display_mode),
                    selected = displayLabel(config.display),
                    options = DisplayMode.entries.map { displayLabel(it) },
                    onSelect = { index ->
                        config = config.copy(display = DisplayMode.entries[index])
                    },
                )
                if (config.display.hasVnc) {
                    Spacer(Modifier.height(12.dp))
                    Picker(
                        label = stringResource(R.string.editor_vga),
                        selected = config.vgaDevice,
                        options = VGA_DEVICES,
                        onSelect = { config = config.copy(vgaDevice = VGA_DEVICES[it]) },
                    )
                    Spacer(Modifier.height(12.dp))
                    Picker(
                        label = stringResource(R.string.editor_resolution),
                        selected = "${config.screenWidth}x${config.screenHeight}",
                        options = RESOLUTIONS.map { "${it.first}x${it.second}" },
                        onSelect = { index ->
                            val (w, h) = RESOLUTIONS[index]
                            config = config.copy(screenWidth = w, screenHeight = h)
                        },
                    )
                }
                Spacer(Modifier.height(4.dp))
                SwitchRow(
                    title = stringResource(R.string.editor_uefi),
                    subtitle = stringResource(R.string.editor_uefi_desc),
                    checked = config.useUefi,
                    onCheckedChange = { config = config.copy(useUefi = it) },
                )
            }

            SectionHeader(stringResource(R.string.editor_section_network))
            MazeCard {
                SwitchRow(
                    title = stringResource(R.string.editor_network_enabled),
                    subtitle = stringResource(R.string.editor_network_desc),
                    checked = config.networkEnabled,
                    onCheckedChange = { config = config.copy(networkEnabled = it) },
                )
                if (config.networkEnabled) {
                    Spacer(Modifier.height(8.dp))
                    PortForwardEditor(
                        forwards = config.portForwards,
                        onChange = { config = config.copy(portForwards = it) },
                    )
                }
            }

            SectionHeader(stringResource(R.string.editor_section_advanced))
            MazeCard {
                MazeTextField(
                    value = config.kernelAppend.orEmpty(),
                    onValueChange = {
                        config = config.copy(kernelAppend = it.takeIf(String::isNotBlank))
                    },
                    label = stringResource(R.string.editor_append),
                    monospace = true,
                )
                Spacer(Modifier.height(12.dp))
                MazeTextField(
                    value = config.extraArgs,
                    onValueChange = { config = config.copy(extraArgs = it) },
                    label = stringResource(R.string.editor_extra_args),
                    placeholder = stringResource(R.string.editor_extra_args_hint),
                    monospace = true,
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    if (config.name.isBlank()) {
                        nameError = true
                    } else {
                        viewModel.save(config)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = maze.accent,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) { Text(stringResource(R.string.action_save)) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private val VGA_DEVICES = listOf("virtio-gpu-pci", "ramfb", "VGA", "bochs-display")

private val RESOLUTIONS = listOf(
    1024 to 600,
    1280 to 720,
    1280 to 800,
    1600 to 900,
    1920 to 1080,
)

private fun cpuModels(arch: Arch): List<String> = when (arch) {
    Arch.AARCH64 -> listOf("cortex-a53", "cortex-a57", "cortex-a72", "cortex-a76", "max")
    Arch.X86_64 -> listOf("qemu64", "Nehalem", "Skylake-Client", "max")
}

@Composable
private fun displayLabel(mode: DisplayMode): String = stringResource(
    when (mode) {
        DisplayMode.SERIAL -> R.string.editor_display_serial
        DisplayMode.VNC -> R.string.editor_display_vnc
        DisplayMode.BOTH -> R.string.editor_display_both
    }
)

/** Total device RAM in MiB, used to bound the memory slider. */
private fun deviceMemoryMb(context: Context): Int {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return 4096
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return (info.totalMem / (1024 * 1024)).toInt().coerceAtLeast(2048)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MazeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = maze.textTertiary) } },
        isError = isError,
        supportingText = supportingText?.let { { Text(it, color = maze.danger) } },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        ),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = keyboardType,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = maze.accent,
            unfocusedBorderColor = maze.border,
            focusedLabelColor = maze.accent,
            unfocusedLabelColor = maze.textSecondary,
            cursorColor = maze.accent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** A labelled dropdown. Material's ExposedDropdownMenu is heavier than this needs. */
@Composable
private fun Picker(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = maze.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Box {
            AssistChip(
                onClick = { open = true },
                label = { Text(selected) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = maze.surface2,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = null,
            )
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = maze.surface2,
            ) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { open = false; onSelect(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    format: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onChange((value - 1).coerceIn(range)) },
            enabled = value > range.first,
        ) { Text("−", style = MaterialTheme.typography.titleLarge, color = maze.accent) }
        Text(
            text = format(value),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
            onClick = { onChange((value + 1).coerceIn(range)) },
            enabled = value < range.last,
        ) { Text("+", style = MaterialTheme.typography.titleLarge, color = maze.accent) }
    }
}

@Composable
private fun PathRow(label: String, path: String?, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = maze.textSecondary,
            )
            Spacer(Modifier.height(3.dp))
            MonoText(
                text = path?.substringAfterLast('/') ?: stringResource(R.string.editor_disk_none),
                color = if (path == null) maze.textTertiary else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (path != null) {
            IconButton(onClick = onClear) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = maze.textSecondary)
            }
        }
    }
}

@Composable
private fun PortForwardEditor(
    forwards: List<PortForward>,
    onChange: (List<PortForward>) -> Unit,
) {
    Column {
        Text(
            text = stringResource(R.string.editor_port_forwards),
            style = MaterialTheme.typography.labelMedium,
            color = maze.textSecondary,
        )
        Spacer(Modifier.height(8.dp))

        if (forwards.isEmpty()) {
            Text(
                text = stringResource(R.string.editor_port_forward_empty),
                style = MaterialTheme.typography.bodySmall,
                color = maze.textTertiary,
            )
        }

        forwards.forEachIndexed { index, forward ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                MonoText(
                    text = "${forward.hostPort} → ${forward.guestPort}/${forward.protocol}",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    onChange(forwards.filterIndexed { i, _ -> i != index })
                }) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = maze.textSecondary,
                    )
                }
            }
        }

        AddPortForwardRow(
            onAdd = { host, guest -> onChange(forwards + PortForward(host, guest)) },
        )
    }
}

@Composable
private fun AddPortForwardRow(onAdd: (Int, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var guest by remember { mutableStateOf("") }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MazeTextField(
            value = host,
            onValueChange = { host = it.filter(Char::isDigit).take(5) },
            label = stringResource(R.string.editor_port_forward_host),
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
        MazeTextField(
            value = guest,
            onValueChange = { guest = it.filter(Char::isDigit).take(5) },
            label = stringResource(R.string.editor_port_forward_guest),
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                val h = host.toIntOrNull()
                val g = guest.toIntOrNull()
                if (h != null && g != null && h in 1..65535 && g in 1..65535) {
                    onAdd(h, g)
                    host = ""
                    guest = ""
                }
            },
            enabled = host.isNotBlank() && guest.isNotBlank(),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = maze.accent)
        }
    }
}

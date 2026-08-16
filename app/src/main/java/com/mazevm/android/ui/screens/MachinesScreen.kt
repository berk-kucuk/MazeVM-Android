package com.mazevm.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazevm.android.R
import com.mazevm.android.core.formatMemory
import com.mazevm.android.data.model.ContainerConfig
import com.mazevm.android.data.model.VmConfig
import com.mazevm.android.data.model.VmState
import com.mazevm.android.ui.MazeViewModel
import com.mazevm.android.ui.NavigationBarClearance
import com.mazevm.android.ui.components.EmptyState
import com.mazevm.android.ui.components.BrandHeader
import com.mazevm.android.ui.components.MazeCard
import com.mazevm.android.ui.components.PillButton
import com.mazevm.android.ui.components.StatusPill
import com.mazevm.android.ui.components.Tag
import com.mazevm.android.ui.theme.maze

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesScreen(
    viewModel: MazeViewModel,
    onCreate: () -> Unit,
    onEdit: (VmConfig) -> Unit,
    onOpenConsole: (VmConfig) -> Unit,
    onOpenShell: (ContainerConfig) -> Unit,
    onBrowseImages: () -> Unit,
) {
    val machines by viewModel.machines.collectAsStateWithLifecycle()
    val states by viewModel.vmStates.collectAsStateWithLifecycle()
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val containerStates by viewModel.containerStates.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<VmConfig?>(null) }
    var pendingContainerDelete by remember { mutableStateOf<ContainerConfig?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        BrandHeader()

        if (machines.isEmpty() && containers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Dns,
                    title = stringResource(R.string.machines_empty_title),
                    body = stringResource(R.string.machines_empty_body),
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                text = stringResource(R.string.machines_browse_images),
                                onClick = onBrowseImages,
                            )
                            PillButton(
                                text = stringResource(R.string.machines_new),
                                onClick = onCreate,
                                tonal = true,
                            )
                        }
                    },
                )
            }
            return@Column
        }

        LazyColumn(
            // Bottom padding clears the floating navigation bar, which is drawn over
            // this list rather than beside it so content shows through its glass.
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 16.dp,
                bottom = 16.dp + NavigationBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(containers, key = { "c-${it.id}" }) { container ->
                ContainerRow(
                    container = container,
                    state = containerStates[container.id] ?: VmState.STOPPED,
                    onStart = { viewModel.startContainer(container) },
                    onStop = { viewModel.stopContainer(container.id) },
                    onOpen = { onOpenShell(container) },
                    onDelete = { pendingContainerDelete = container },
                )
            }
            items(machines, key = { it.id }) { machine ->
                MachineRow(
                    machine = machine,
                    state = states[machine.id] ?: VmState.STOPPED,
                    onStart = { viewModel.start(machine) },
                    onStop = { viewModel.stop(machine.id) },
                    onForceStop = { viewModel.forceStop(machine.id) },
                    onOpenConsole = { onOpenConsole(machine) },
                    onEdit = { onEdit(machine) },
                    onDuplicate = { viewModel.duplicate(machine) },
                    onDelete = { pendingDelete = machine },
                )
            }
            item {
                Spacer(Modifier.height(4.dp))
                PillButton(
                    text = stringResource(R.string.machines_new),
                    onClick = onCreate,
                    tonal = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    pendingContainerDelete?.let { container ->
        AlertDialog(
            onDismissRequest = { pendingContainerDelete = null },
            containerColor = maze.surface1,
            title = { Text(stringResource(R.string.machine_delete_title)) },
            text = { Text(stringResource(R.string.container_delete_body, container.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteContainer(container)
                    pendingContainerDelete = null
                }) {
                    Text(stringResource(R.string.action_delete), color = maze.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingContainerDelete = null }) {
                    Text(stringResource(R.string.action_cancel), color = maze.textSecondary)
                }
            },
        )
    }

    pendingDelete?.let { machine ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = maze.surface1,
            title = { Text(stringResource(R.string.machine_delete_title)) },
            text = { Text(stringResource(R.string.machine_delete_body, machine.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(machine)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete), color = maze.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel), color = maze.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun MachineRow(
    machine: VmConfig,
    state: VmState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onOpenConsole: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    MazeCard(onClick = if (state.isActive) onOpenConsole else onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = machine.name.ifBlank { machine.id },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.machine_summary,
                        machine.arch.label,
                        machine.cpuCount,
                        formatMemory(machine.ramMb),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = maze.textSecondary,
                )
            }

            StatusPill(text = state.label(), color = state.colour())

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = null,
                        tint = maze.textSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = maze.surface2,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.machine_open_console)) },
                        onClick = { menuOpen = false; onOpenConsole() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = { menuOpen = false; onEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_clone)) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    if (state.isActive) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.machine_force_stop)) },
                            onClick = { menuOpen = false; onForceStop() },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.action_delete), color = maze.danger)
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (machine.useUefi) Tag("UEFI")
            if (machine.display.hasVnc) Tag(stringResource(R.string.console_tab_display))
            if (machine.networkEnabled) Tag("SLIRP")

            Spacer(Modifier.weight(1f))

            if (state.isActive) {
                PillButton(
                    text = stringResource(R.string.machine_stop),
                    onClick = onStop,
                    leading = Icons.Rounded.Stop,
                    tonal = true,
                )
            } else {
                PillButton(
                    text = stringResource(R.string.machine_start),
                    onClick = onStart,
                    leading = Icons.Rounded.PlayArrow,
                )
            }
        }
    }
}

/**
 * A container in the same list as the machines.
 *
 * Deliberately the same card shape as a machine: to the user these are both "things I
 * can run", and the difference that matters is on the badge, not in the layout.
 */
@Composable
private fun ContainerRow(
    container: ContainerConfig,
    state: VmState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    MazeCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = container.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = container.image.canonical,
                    style = MaterialTheme.typography.bodySmall,
                    color = maze.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            StatusPill(text = state.label(), color = state.colour())

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = null,
                        tint = maze.textSecondary,
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = maze.surface2,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.container_shell)) },
                        onClick = { menuOpen = false; onOpen() },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.action_delete), color = maze.danger)
                        },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tag(stringResource(R.string.instance_container))
            Tag("ARM64")

            Spacer(Modifier.weight(1f))

            if (state.isActive) {
                PillButton(
                    text = stringResource(R.string.machine_stop),
                    onClick = onStop,
                    leading = Icons.Rounded.Stop,
                    tonal = true,
                )
            } else {
                PillButton(
                    text = stringResource(R.string.container_start),
                    onClick = onStart,
                    leading = Icons.Rounded.PlayArrow,
                )
            }
        }
    }
}

@Composable
private fun VmState.label(): String = stringResource(
    when (this) {
        VmState.STOPPED -> R.string.machine_state_stopped
        VmState.STARTING -> R.string.machine_state_starting
        VmState.RUNNING -> R.string.machine_state_running
        VmState.STOPPING -> R.string.machine_state_stopping
        VmState.FAILED -> R.string.machine_state_failed
    }
)

@Composable
private fun VmState.colour(): Color = when (this) {
    VmState.RUNNING -> maze.success
    VmState.STARTING, VmState.STOPPING -> maze.warning
    VmState.FAILED -> maze.danger
    VmState.STOPPED -> maze.textTertiary
}

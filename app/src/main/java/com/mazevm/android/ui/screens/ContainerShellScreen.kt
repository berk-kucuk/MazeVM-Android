package com.mazevm.android.ui.screens

import android.app.Activity
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazevm.android.R
import com.mazevm.android.terminal.TerminalController
import com.mazevm.android.terminal.TerminalEmulator
import com.mazevm.android.terminal.TerminalKeys
import com.mazevm.android.terminal.TerminalView
import com.mazevm.android.ui.MazeViewModel
import com.mazevm.android.ui.components.EmptyState
import com.mazevm.android.ui.components.PillButton
import com.mazevm.android.ui.theme.CapsuleShape
import com.mazevm.android.ui.theme.maze

/**
 * A container's shell.
 *
 * Simpler than the machine console: a container has no firmware, no display and no
 * QEMU log, so there is one tab and it is the terminal. The emulator and key row are
 * the same ones the serial console uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerShellScreen(
    viewModel: MazeViewModel,
    containerId: String,
    onBack: () -> Unit,
) {
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val states by viewModel.containerStates.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val view = LocalView.current

    val config = remember(containerId, containers) { viewModel.findContainer(containerId) }
    val process = remember(containerId, states[containerId]) {
        viewModel.containerProcess(containerId)
    }

    if (config == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            ShellTopBar(title = containerId, onBack = onBack, actions = {})
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.Stop,
                    title = stringResource(R.string.console_not_running),
                    body = stringResource(R.string.machines_empty_body),
                )
            }
        }
        return
    }

    if (process == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            ShellTopBar(title = config.name, onBack = onBack, actions = {})
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.PlayArrow,
                    title = stringResource(R.string.console_not_running),
                    body = config.image.canonical,
                    action = {
                        PillButton(
                            text = stringResource(R.string.container_start),
                            onClick = { viewModel.startContainer(config) },
                        )
                    },
                )
            }
        }
        return
    }

    val emulator = remember(process) { TerminalEmulator() }
    val controller = remember { TerminalController() }
    val revision = remember(process) { mutableLongStateOf(0L) }

    LaunchedEffect(process) {
        process.output.collect { chunk ->
            synchronized(emulator.lock) { emulator.write(chunk) }
            revision.longValue = emulator.revision
        }
    }

    // The guest needs to be told the window size or anything full-screen draws wrong.
    LaunchedEffect(emulator.columns, emulator.rows) {
        process.resize(emulator.columns, emulator.rows)
    }

    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var chromeRevealed by remember { mutableStateOf(false) }
    val showChrome = !landscape || chromeRevealed

    LaunchedEffect(landscape) { chromeRevealed = false }

    DisposableEffect(landscape, chromeRevealed) {
        val window = (view.context as? Activity)?.window
        val controllerCompat = window?.let { WindowCompat.getInsetsController(it, view) }
        if (landscape && !chromeRevealed) {
            controllerCompat?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controllerCompat?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controllerCompat?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controllerCompat?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showChrome) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    ShellTopBar(
                        title = config.name,
                        onBack = onBack,
                        actions = {
                            IconButton(onClick = { viewModel.stopContainer(containerId) }) {
                                Icon(
                                    Icons.Rounded.Stop,
                                    contentDescription = stringResource(R.string.machine_stop),
                                )
                            }
                        },
                    )
                }
            }

            TerminalView(
                emulator = emulator,
                revision = revision,
                controller = controller,
                onInput = { process.write(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            ShellKeyRow(
                onKey = { process.write(it) },
                onKeyboard = { controller.showKeyboard() },
            )
        }

        if (!showChrome) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(CapsuleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { chromeRevealed = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Rounded.FullscreenExit,
                    contentDescription = stringResource(R.string.console_show_controls),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShellTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun ShellKeyRow(onKey: (ByteArray) -> Unit, onKeyboard: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(maze.surface1)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onKeyboard) {
            Icon(
                Icons.Rounded.Keyboard,
                contentDescription = stringResource(R.string.console_keyboard),
                tint = maze.textSecondary,
            )
        }
        listOf(
            "ESC" to TerminalKeys.Escape,
            "TAB" to TerminalKeys.Tab,
            "^C" to TerminalKeys.CtrlC,
            "^D" to TerminalKeys.CtrlD,
            "^Z" to TerminalKeys.CtrlZ,
            "^L" to TerminalKeys.CtrlL,
            "↑" to TerminalKeys.Up,
            "↓" to TerminalKeys.Down,
            "←" to TerminalKeys.Left,
            "→" to TerminalKeys.Right,
            "/" to "/".toByteArray(),
            "|" to "|".toByteArray(),
            "-" to "-".toByteArray(),
        ).forEach { (label, bytes) ->
            androidx.compose.material3.TextButton(
                onClick = { onKey(bytes) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 4.dp,
                ),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

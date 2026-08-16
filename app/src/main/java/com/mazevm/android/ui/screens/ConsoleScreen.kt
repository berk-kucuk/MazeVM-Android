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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazevm.android.R
import com.mazevm.android.data.model.DisplayMode
import com.mazevm.android.data.model.VmState
import com.mazevm.android.terminal.TerminalController
import com.mazevm.android.terminal.TerminalEmulator
import com.mazevm.android.terminal.TerminalKeys
import com.mazevm.android.terminal.TerminalView
import com.mazevm.android.ui.MazeViewModel
import com.mazevm.android.ui.components.EmptyState
import com.mazevm.android.ui.theme.CapsuleShape
import com.mazevm.android.ui.theme.maze
import com.mazevm.android.vnc.RfbClient
import com.mazevm.android.vnc.TouchMode
import com.mazevm.android.vnc.VncController
import com.mazevm.android.vnc.VncView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    viewModel: MazeViewModel,
    vmId: String,
    onBack: () -> Unit,
) {
    val machines by viewModel.machines.collectAsStateWithLifecycle()
    val vmStates by viewModel.vmStates.collectAsStateWithLifecycle()
    val view = LocalView.current

    val machine = remember(vmId, machines) { viewModel.findMachine(vmId) }
    // Re-resolve whenever the machine's state changes: the process object only exists
    // once it has been started, and this screen is often opened before that.
    val process = remember(vmId, vmStates[vmId]) { viewModel.vmProcess(vmId) }

    val settings by viewModel.settings.collectAsStateWithLifecycle()

    if (machine == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            ConsoleTopBar(title = vmId, onBack = onBack, actions = {})
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
            ConsoleTopBar(title = machine.name, onBack = onBack, actions = {})
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.PlayArrow,
                    title = stringResource(R.string.console_not_running),
                    body = stringResource(R.string.notice_first_boot),
                    action = {
                        Button(
                            onClick = { viewModel.start(machine) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = maze.accent,
                                contentColor = MaterialTheme.colorScheme.background,
                            ),
                        ) { Text(stringResource(R.string.machine_start)) }
                    },
                )
            }
        }
        return
    }

    val state by process.state.collectAsStateWithLifecycle()
    val log by process.log.collectAsStateWithLifecycle()

    val tabs = buildList {
        if (machine.display.hasSerial) add(TabKind.Serial)
        if (machine.display.hasVnc) add(TabKind.Display)
        add(TabKind.Log)
    }
    var tabIndex by remember { mutableIntStateOf(0) }
    val activeTab = tabs.getOrElse(tabIndex) { TabKind.Log }

    // Emulated boots take minutes; letting the screen sleep mid-install is worse than
    // the battery cost of keeping it on while this screen is in front.
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Turned sideways, the phone is being used as a monitor, so the app's own chrome
    // and the system bars both get out of the way and the guest takes the whole panel.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var chromeRevealed by remember { mutableStateOf(false) }
    val showChrome = !landscape || chromeRevealed

    // Coming back to portrait should not leave the console stuck in the revealed state.
    LaunchedEffect(landscape) { chromeRevealed = false }

    DisposableEffect(landscape, chromeRevealed) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (landscape && !chromeRevealed) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showChrome) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    ConsoleTopBar(
                        title = machine.name,
                        onBack = onBack,
                        actions = {
                            if (landscape) {
                                IconButton(onClick = { chromeRevealed = false }) {
                                    Icon(
                                        Icons.Rounded.Fullscreen,
                                        contentDescription = stringResource(
                                            R.string.console_fullscreen
                                        ),
                                    )
                                }
                            }
                            ConsoleMenu(
                                isRunning = state.isActive,
                                onStop = { viewModel.stop(vmId) },
                                onForceStop = { viewModel.forceStop(vmId) },
                                onShowLog = { tabIndex = tabs.lastIndex },
                            )
                        },
                    )

                    if (tabs.size > 1) {
                        ConsoleTabs(
                            tabs = tabs,
                            selected = tabIndex,
                            onSelect = { tabIndex = it },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (activeTab) {
                    TabKind.Serial -> SerialTab(process)
                    TabKind.Display -> DisplayTab(process, state)
                    TabKind.Log -> LogTab(log, process.commandLine)
                }
            }
        }

        // The only way back to the app's controls once the bars are gone.
        if (!showChrome) {
            RevealChromeButton(
                onClick = { chromeRevealed = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
            )
        }
    }
}

/** Capsule tab strip, in place of Material's underlined TabRow. */
@Composable
private fun ConsoleTabs(
    tabs: List<TabKind>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(CapsuleShape)
            .background(maze.surface2)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, kind ->
            val active = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(CapsuleShape)
                    .background(if (active) maze.accent else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(kind.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) {
                        MaterialTheme.colorScheme.background
                    } else {
                        maze.textSecondary
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun RevealChromeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CapsuleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
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

private enum class TabKind(val labelRes: Int) {
    Serial(R.string.console_tab_serial),
    Display(R.string.console_tab_display),
    Log(R.string.console_tab_log),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsoleTopBar(
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
private fun ConsoleMenu(
    isRunning: Boolean,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onShowLog: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = maze.surface2,
        ) {
            if (isRunning) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.machine_stop)) },
                    onClick = { open = false; onStop() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.machine_force_stop)) },
                    onClick = { open = false; onForceStop() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.console_tab_log)) },
                onClick = { open = false; onShowLog() },
            )
        }
    }
}

@Composable
private fun SerialTab(process: com.mazevm.android.qemu.QemuProcess) {
    // The emulator and its revision counter are keyed to the machine, not to this
    // composable's position, so switching tabs or rotating does not lose the console.
    val emulator = remember(process) { TerminalEmulator() }
    val controller = remember { TerminalController() }
    val revision = remember(process) { mutableLongStateOf(0L) }

    // Serial bytes arrive on a background thread; feeding them under the emulator's own
    // lock keeps the draw pass from reading a half-applied escape sequence. Publishing
    // the revision afterwards is what tells the canvas to repaint.
    LaunchedEffect(process) {
        process.serialOutput.collect { chunk ->
            synchronized(emulator.lock) { emulator.write(chunk) }
            revision.longValue = emulator.revision
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TerminalView(
            emulator = emulator,
            revision = revision,
            controller = controller,
            onInput = { process.writeSerial(it) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        KeyRow(
            onKey = { process.writeSerial(it) },
            onKeyboard = { controller.showKeyboard() },
        )
    }
}

/** The keys a phone keyboard does not have but a shell needs constantly. */
@Composable
private fun KeyRow(onKey: (ByteArray) -> Unit, onKeyboard: () -> Unit) {
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
        KeyChip("ESC") { onKey(TerminalKeys.Escape) }
        KeyChip("TAB") { onKey(TerminalKeys.Tab) }
        KeyChip("^C") { onKey(TerminalKeys.CtrlC) }
        KeyChip("^D") { onKey(TerminalKeys.CtrlD) }
        KeyChip("^Z") { onKey(TerminalKeys.CtrlZ) }
        KeyChip("^L") { onKey(TerminalKeys.CtrlL) }
        KeyChip("↑") { onKey(TerminalKeys.Up) }
        KeyChip("↓") { onKey(TerminalKeys.Down) }
        KeyChip("←") { onKey(TerminalKeys.Left) }
        KeyChip("→") { onKey(TerminalKeys.Right) }
        KeyChip("/") { onKey("/".toByteArray()) }
        KeyChip("|") { onKey("|".toByteArray()) }
        KeyChip("-") { onKey("-".toByteArray()) }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 4.dp,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DisplayTab(process: com.mazevm.android.qemu.QemuProcess, state: VmState) {
    val socket = process.sockets.vnc
    if (socket == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.console_disconnected),
                color = maze.textSecondary,
            )
        }
        return
    }

    val scope = rememberCoroutineScope()
    val client = remember(socket.absolutePath) { RfbClient(socket, scope) }
    val controller = remember { VncController() }
    var touchMode by remember { mutableStateOf(TouchMode.Direct) }
    var fitToScreen by remember { mutableStateOf(true) }

    DisposableEffect(client) {
        client.connect()
        onDispose { client.disconnect() }
    }

    val status by client.status.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            VncView(
                client = client,
                controller = controller,
                touchMode = touchMode,
                fitToScreen = fitToScreen,
                modifier = Modifier.fillMaxSize(),
            )
            when (val current = status) {
                is RfbClient.Status.Connecting -> ConsoleOverlay(
                    stringResource(R.string.console_connecting),
                    showSpinner = true,
                )
                is RfbClient.Status.Failed -> ConsoleOverlay(
                    text = current.reason,
                    showSpinner = false,
                    onRetry = { client.connect() },
                )
                is RfbClient.Status.Disconnected -> ConsoleOverlay(
                    text = stringResource(R.string.console_disconnected),
                    showSpinner = false,
                    onRetry = { client.connect() },
                )
                else -> Unit
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(maze.surface1)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { controller.showKeyboard() }) {
                Icon(
                    Icons.Rounded.Keyboard,
                    contentDescription = stringResource(R.string.console_keyboard),
                    tint = maze.textSecondary,
                )
            }
            KeyChip(
                if (fitToScreen) stringResource(R.string.console_scale_actual)
                else stringResource(R.string.console_scale_fit)
            ) { fitToScreen = !fitToScreen }
            KeyChip(
                if (touchMode == TouchMode.Direct) stringResource(R.string.console_touch_trackpad)
                else stringResource(R.string.console_touch_direct)
            ) {
                touchMode =
                    if (touchMode == TouchMode.Direct) TouchMode.Trackpad else TouchMode.Direct
            }
            KeyChip("C-A-Del") { client.sendCtrlAltDel() }
            KeyChip("ESC") { client.tapKey(com.mazevm.android.vnc.Keysyms.ESCAPE) }
            KeyChip("↑") { client.tapKey(com.mazevm.android.vnc.Keysyms.UP) }
            KeyChip("↓") { client.tapKey(com.mazevm.android.vnc.Keysyms.DOWN) }
            KeyChip("←") { client.tapKey(com.mazevm.android.vnc.Keysyms.LEFT) }
            KeyChip("→") { client.tapKey(com.mazevm.android.vnc.Keysyms.RIGHT) }
        }
    }
}

@Composable
private fun ConsoleOverlay(
    text: String,
    showSpinner: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.82f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showSpinner) {
                CircularProgressIndicator(color = maze.accent, strokeWidth = 2.dp)
                Spacer(Modifier.height(14.dp))
            }
            Text(text = text, color = maze.textSecondary)
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry), color = maze.accent)
                }
            }
        }
    }
}

@Composable
private fun LogTab(log: String, commandLine: String) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = commandLine,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = maze.textTertiary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = log.ifBlank { stringResource(R.string.console_log_empty) },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = maze.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.TextButton(
            onClick = { clipboard.setText(AnnotatedString("$commandLine\n\n$log")) },
        ) {
            Text(stringResource(R.string.console_copy_log), color = maze.accent)
        }
    }
}

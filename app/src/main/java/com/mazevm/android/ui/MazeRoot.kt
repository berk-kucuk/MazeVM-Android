package com.mazevm.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mazevm.android.ui.theme.CapsuleShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mazevm.android.MazeApp
import com.mazevm.android.R
import com.mazevm.android.ui.components.GlassSurface
import com.mazevm.android.ui.screens.CatalogScreen
import com.mazevm.android.ui.screens.ConsoleScreen
import com.mazevm.android.ui.screens.ContainerShellScreen
import com.mazevm.android.ui.screens.MachineEditorScreen
import com.mazevm.android.ui.screens.MachinesScreen
import com.mazevm.android.ui.screens.SettingsScreen
import com.mazevm.android.ui.theme.maze

object Routes {
    const val MACHINES = "machines"
    const val CATALOG = "catalog"
    const val SETTINGS = "settings"
    const val EDITOR = "editor"
    const val CONSOLE = "console"
    const val SHELL = "shell"

    fun editor(vmId: String?) = "$EDITOR?vmId=${vmId.orEmpty()}"
    fun console(vmId: String) = "$CONSOLE/$vmId"
    fun shell(containerId: String) = "$SHELL/$containerId"
}

private data class Tab(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

private val tabs = listOf(
    Tab(Routes.MACHINES, Icons.Outlined.Dns, R.string.nav_machines),
    Tab(Routes.CATALOG, Icons.Outlined.Download, R.string.nav_images),
    Tab(Routes.SETTINGS, Icons.Outlined.Tune, R.string.nav_settings),
)

@Composable
fun MazeRoot(app: MazeApp) {
    val viewModel: MazeViewModel = viewModel(factory = MazeViewModel.Factory(app))
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.text)
            viewModel.consumeMessage()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination
    // The console owns the whole screen; a bottom bar there would steal rows from the
    // terminal and get in the way of the on-screen key row.
    val showBottomBar = tabs.any { tab ->
        currentRoute?.hierarchy?.any { it.route == tab.route } == true
    }

    // The console goes edge to edge and manages its own insets, because in landscape it
    // hides the system bars entirely to give the guest the whole panel.
    val isConsole = currentRoute?.route?.let {
        it.startsWith(Routes.CONSOLE) || it.startsWith(Routes.SHELL)
    } == true

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        // The bar is drawn over the content rather than beside it. A translucent
        // surface with nothing passing underneath is just a darker rectangle; the
        // effect only exists because cards scroll behind it. Each screen adds its own
        // bottom padding so the last item still clears the bar.
        bottomBar = {},
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    // The app draws edge to edge, so the status bar inset has to be
                    // honoured here or every top bar sits under the clock.
                    top = if (isConsole) 0.dp else padding.calculateTopPadding(),
                )
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.MACHINES,
            ) {
                composable(Routes.MACHINES) {
                    MachinesScreen(
                        viewModel = viewModel,
                        onCreate = { navController.navigate(Routes.editor(null)) },
                        onEdit = { navController.navigate(Routes.editor(it.id)) },
                        onOpenConsole = { navController.navigate(Routes.console(it.id)) },
                        onOpenShell = { navController.navigate(Routes.shell(it.id)) },
                        onBrowseImages = { navController.navigate(Routes.CATALOG) },
                    )
                }
                composable(Routes.CATALOG) {
                    CatalogScreen(
                        viewModel = viewModel,
                        onMachineCreated = { navController.navigate(Routes.console(it.id)) },
                        onContainerCreated = { navController.navigate(Routes.shell(it.id)) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(viewModel = viewModel)
                }
                composable("${Routes.EDITOR}?vmId={vmId}") { entry ->
                    MachineEditorScreen(
                        viewModel = viewModel,
                        vmId = entry.arguments?.getString("vmId")?.takeIf { it.isNotBlank() },
                        onDone = { navController.popBackStack() },
                    )
                }
                composable("${Routes.SHELL}/{containerId}") { entry ->
                    ContainerShellScreen(
                        viewModel = viewModel,
                        containerId = entry.arguments?.getString("containerId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("${Routes.CONSOLE}/{vmId}") { entry ->
                    ConsoleScreen(
                        viewModel = viewModel,
                        vmId = entry.arguments?.getString("vmId").orEmpty(),
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            if (showBottomBar) {
                MazeNavigationBar(
                    navController = navController,
                    currentDestination = currentRoute,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * Height the tab screens reserve at the bottom so their last row is not left sitting
 * under the floating navigation bar.
 */
val NavigationBarClearance = 92.dp

/**
 * A floating capsule navigation bar.
 *
 * One UI puts navigation in a detached pill rather than an edge-to-edge bar, and gives
 * the selected destination a filled pill with its label beside the icon while the
 * others stay icon-only. That asymmetry is what makes the current tab obvious without
 * a separate indicator.
 */
@Composable
private fun MazeNavigationBar(
    navController: NavHostController,
    currentDestination: androidx.navigation.NavDestination?,
    modifier: Modifier = Modifier,
) {
    val palette = maze
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        GlassSurface(shape = CapsuleShape) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    Row(
                        modifier = Modifier
                            .weight(if (selected) 1.4f else 1f)
                            .clip(CapsuleShape)
                            .background(if (selected) palette.accent else Color.Transparent)
                            .clickable(enabled = !selected) {
                                navController.navigate(tab.route) {
                                    // Machines is the start destination, so anchoring
                                    // the pop on it and then navigating to it made the
                                    // whole operation a no-op: launchSingleTop saw it
                                    // was already on top and skipped. Popping it
                                    // inclusively when it is the target is what makes
                                    // selecting it actually land.
                                    popUpTo(Routes.MACHINES) {
                                        inclusive = tab.route == Routes.MACHINES
                                    }
                                    launchSingleTop = true
                                }
                            }
                            .padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = stringResource(tab.labelRes),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.background
                            } else {
                                palette.textTertiary
                            },
                            modifier = Modifier.size(21.dp),
                        )
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.background,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

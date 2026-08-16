package com.mazevm.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazevm.android.R
import com.mazevm.android.core.formatBytes
import com.mazevm.android.data.model.BootStyle
import com.mazevm.android.data.model.Distro
import com.mazevm.android.data.model.DistroImage
import com.mazevm.android.data.model.DistroRelease
import com.mazevm.android.data.model.ImageRef
import com.mazevm.android.data.model.VmConfig
import com.mazevm.android.download.DownloadState
import com.mazevm.android.ui.MazeViewModel
import com.mazevm.android.ui.NavigationBarClearance
import com.mazevm.android.ui.components.ChoiceChip
import com.mazevm.android.ui.components.GlassSurface
import com.mazevm.android.ui.components.EmptyState
import com.mazevm.android.ui.components.BrandHeader
import com.mazevm.android.ui.components.MazeCard
import com.mazevm.android.ui.components.PillButton
import com.mazevm.android.ui.components.Tag
import com.mazevm.android.ui.theme.CapsuleShape
import com.mazevm.android.ui.theme.maze

/**
 * Distributions, one card each.
 *
 * Publishers ship several near-identical images per release, so nothing is downloaded
 * straight from this list. Tapping a distribution opens a sheet that asks which release
 * and, where the images genuinely differ by desktop, which desktop.
 */
@Composable
fun CatalogScreen(
    viewModel: MazeViewModel,
    onMachineCreated: (VmConfig) -> Unit,
    onContainerCreated: (com.mazevm.android.data.model.ContainerConfig) -> Unit,
) {
    // The two backends get their images from completely different places, so they are
    // separate sources rather than one merged list: a distribution ISO and an OCI
    // image have nothing in common beyond both being downloads.
    var source by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        BrandHeader()

        GlassSurface(
            shape = CapsuleShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(R.string.hub_tab_distros, R.string.hub_tab_hub).forEachIndexed { index, label ->
                val active = source == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CapsuleShape)
                        .background(
                            if (active) maze.accent
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .clickable { source = index }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) MaterialTheme.colorScheme.background
                        else maze.textSecondary,
                    )
                }
            }
        }

        }

        if (source == 0) {
            DistributionCatalog(viewModel, onMachineCreated)
        } else {
            DockerHubScreen(viewModel, onContainerCreated)
        }
    }
}

@Composable
private fun DistributionCatalog(
    viewModel: MazeViewModel,
    onMachineCreated: (VmConfig) -> Unit,
) {
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadStates.collectAsStateWithLifecycle()
    val installed by viewModel.installedImages.collectAsStateWithLifecycle()
    val refreshing by viewModel.catalogRefreshing.collectAsStateWithLifecycle()

    var openDistro by remember { mutableStateOf<Distro?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = viewModel::refreshCatalog, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = maze.accent,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.catalog_refresh),
                        tint = maze.textSecondary,
                    )
                }
            }
        }

        if (catalog.distros.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Outlined.Inventory2,
                    title = stringResource(R.string.catalog_empty_downloaded),
                    body = stringResource(R.string.catalog_subtitle),
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = 24.dp + NavigationBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(catalog.distros, key = { it.id }) { distro ->
                DistroCard(
                    distro = distro,
                    installedCount = distro.images.count { installed.containsKey(it.id) },
                    busy = distro.images.any { downloads[it.id]?.isBusy == true },
                    onClick = { openDistro = distro },
                )
            }
        }
    }

    openDistro?.let { distro ->
        DistroSheet(
            distro = distro,
            viewModel = viewModel,
            downloads = downloads,
            installedIds = installed.keys,
            onDismiss = { openDistro = null },
            onMachineCreated = {
                openDistro = null
                onMachineCreated(it)
            },
        )
    }
}

@Composable
private fun DistroCard(
    distro: Distro,
    installedCount: Int,
    busy: Boolean,
    onClick: () -> Unit,
) {
    MazeCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = distro.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = distro.tagline,
                    style = MaterialTheme.typography.bodyMedium,
                    color = maze.textSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = maze.accent,
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = maze.textTertiary,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(
                if (distro.releases.size == 1) distro.releases.first().name
                else stringResource(R.string.catalog_release_count, distro.releases.size)
            )
            val desktops = distro.images.mapNotNull { it.desktop }.distinct()
            if (desktops.size > 1) {
                Tag(stringResource(R.string.catalog_desktop_count, desktops.size))
            }
            if (installedCount > 0) {
                Tag(stringResource(R.string.catalog_on_device_count, installedCount))
            }
        }
    }
}

/**
 * The picker. Release first, then desktop when there is a real choice, then the
 * edition. Everything below reacts to the selection above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DistroSheet(
    distro: Distro,
    viewModel: MazeViewModel,
    downloads: Map<String, DownloadState>,
    installedIds: Set<String>,
    onDismiss: () -> Unit,
    onMachineCreated: (VmConfig) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var release by remember(distro.id) {
        mutableStateOf(distro.latestRelease ?: distro.releases.first())
    }
    var desktopFilter by remember(distro.id) { mutableStateOf<String?>(null) }
    var selected by remember(distro.id) { mutableStateOf(release.editions.firstOrNull()) }
    var creating by remember { mutableStateOf<ImageRef?>(null) }
    var deleting by remember { mutableStateOf<DistroImage?>(null) }

    val desktops = release.desktops
    val editions = remember(release, desktopFilter) {
        if (desktopFilter == null) release.editions
        else release.editions.filter { it.desktop == desktopFilter }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = maze.surface1,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = distro.name,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = distro.tagline,
                style = MaterialTheme.typography.bodyMedium,
                color = maze.textSecondary,
            )

            if (distro.releases.size > 1) {
                SheetSection(stringResource(R.string.catalog_choose_release))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    distro.releases.forEach { candidate ->
                        ChoiceChip(
                            text = candidate.name,
                            selected = candidate.id == release.id,
                            onClick = {
                                release = candidate
                                desktopFilter = null
                                selected = candidate.editions.firstOrNull()
                            },
                        )
                    }
                }
                release.note?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = maze.textTertiary,
                    )
                }
            }

            if (desktops.size > 1) {
                SheetSection(stringResource(R.string.catalog_choose_desktop))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(
                        text = stringResource(R.string.catalog_desktop_all),
                        selected = desktopFilter == null,
                        onClick = { desktopFilter = null },
                    )
                    desktops.forEach { desktop ->
                        ChoiceChip(
                            text = desktop,
                            selected = desktopFilter == desktop,
                            onClick = {
                                desktopFilter = desktop
                                selected = release.editions.firstOrNull { it.desktop == desktop }
                            },
                        )
                    }
                }
            }

            SheetSection(stringResource(R.string.catalog_choose_edition))
            editions.forEach { edition ->
                EditionRow(
                    edition = edition,
                    selected = edition.id == selected?.id,
                    state = downloads[edition.id],
                    installed = edition.id in installedIds,
                    onSelect = { selected = edition },
                )
                Spacer(Modifier.height(8.dp))
            }

            selected?.let { edition ->
                EditionDetail(
                    distro = distro,
                    release = release,
                    edition = edition,
                    state = downloads[edition.id],
                    installed = edition.id in installedIds,
                    needsLiveInstaller = edition.bootStyle == BootStyle.ROOTFS_TAR &&
                        !viewModel.hasLiveInstaller(),
                    onDownload = { viewModel.download(edition) },
                    onPause = { viewModel.pauseDownload(edition) },
                    onCancel = { viewModel.cancelDownload(edition) },
                    onDelete = { deleting = edition },
                    onCreate = { creating = ImageRef(distro, release, edition) },
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    creating?.let { ref ->
        CreateMachineDialog(
            ref = ref,
            onDismiss = { creating = null },
            onConfirm = { name, diskGb, credentials ->
                creating = null
                viewModel.createMachineFrom(ref, name, diskGb, credentials, onMachineCreated)
            },
        )
    }

    deleting?.let { edition ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = maze.surface2,
            shape = RoundedCornerShape(28.dp),
            title = { Text(stringResource(R.string.download_delete_title)) },
            text = { Text(stringResource(R.string.download_delete_body, edition.edition)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteImage(edition); deleting = null }) {
                    Text(stringResource(R.string.action_delete), color = maze.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel), color = maze.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CapsuleShape)
                .background(maze.borderStrong)
        )
    }
}

@Composable
private fun SheetSection(text: String) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = maze.accent,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun EditionRow(
    edition: DistroImage,
    selected: Boolean,
    state: DownloadState?,
    installed: Boolean,
    onSelect: () -> Unit,
) {
    val palette = maze
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) palette.accentContainer else palette.surface2)
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = edition.desktop ?: edition.edition,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = buildString {
                    if (edition.desktop != null) append("${edition.edition} · ")
                    append(formatBytes(edition.downloadBytes))
                    if (edition.desktop == null) {
                        append(" · ")
                        append(stringResourceConsoleOnly())
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            state is DownloadState.Running -> Text(
                text = "${(state.fraction * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = palette.accent,
            )
            installed || state is DownloadState.Completed -> Icon(
                Icons.Rounded.Check,
                contentDescription = stringResource(R.string.catalog_downloaded),
                tint = palette.success,
                modifier = Modifier.size(20.dp),
            )
            selected -> Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun stringResourceConsoleOnly() = stringResource(R.string.catalog_headless)

@Composable
private fun EditionDetail(
    distro: Distro,
    release: DistroRelease,
    edition: DistroImage,
    state: DownloadState?,
    installed: Boolean,
    needsLiveInstaller: Boolean,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onCreate: () -> Unit,
) {
    Spacer(Modifier.height(20.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tag(edition.arch.label)
        if (edition.needsUefi) Tag(stringResource(R.string.catalog_needs_uefi))
        Tag(
            stringResource(
                R.string.catalog_size,
                formatBytes(edition.downloadBytes),
                formatBytes(edition.installedBytes),
            )
        )
    }

    edition.notes?.let { notes ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = notes,
            style = MaterialTheme.typography.bodySmall,
            color = maze.textSecondary,
        )
    }

    edition.defaultUser?.let { user ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.catalog_credentials, user, edition.defaultPassword ?: "—"),
            style = MaterialTheme.typography.bodySmall,
            color = maze.textTertiary,
        )
    }

    if (needsLiveInstaller) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.catalog_needs_live_installer),
            style = MaterialTheme.typography.bodySmall,
            color = maze.warning,
        )
    }

    Spacer(Modifier.height(20.dp))

    when {
        state is DownloadState.Running -> Column {
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CapsuleShape),
                color = maze.accent,
                trackColor = maze.surface3,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        R.string.download_progress,
                        formatBytes(state.bytes),
                        formatBytes(state.totalBytes),
                        formatBytes(state.bytesPerSecond),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = maze.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onPause) {
                    Icon(
                        Icons.Rounded.Pause,
                        contentDescription = stringResource(R.string.download_pause),
                        tint = maze.textSecondary,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.download_cancel), color = maze.textSecondary)
                }
            }
        }

        state is DownloadState.Verifying -> StepProgress(
            stringResource(R.string.catalog_verifying),
            state.fraction,
        )

        state is DownloadState.Extracting -> StepProgress(
            stringResource(R.string.catalog_extracting),
            state.fraction,
        )

        state is DownloadState.Queued -> StepProgress(stringResource(R.string.download_queued), 0f)

        state is DownloadState.Paused -> Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                text = stringResource(R.string.download_resume),
                onClick = onDownload,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                text = stringResource(R.string.download_cancel),
                onClick = onCancel,
                tonal = true,
            )
        }

        state is DownloadState.Failed -> Column {
            Text(
                text = stringResource(R.string.download_failed, state.reason),
                style = MaterialTheme.typography.bodySmall,
                color = maze.danger,
            )
            Spacer(Modifier.height(12.dp))
            PillButton(
                text = stringResource(R.string.action_retry),
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        installed || state is DownloadState.Completed -> Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                text = stringResource(R.string.catalog_create_vm),
                onClick = onCreate,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = maze.textSecondary,
                )
            }
        }

        else -> PillButton(
            text = stringResource(R.string.catalog_download),
            onClick = onDownload,
            leading = Icons.Rounded.Download,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepProgress(label: String, fraction: Float) {
    Column {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CapsuleShape),
            color = maze.accent,
            trackColor = maze.surface3,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = maze.textSecondary,
        )
    }
}

/**
 * Asks for what cannot be guessed: the machine's name, how large its disk should be,
 * and — for cloud images, which ship with no password at all — the account to create
 * through cloud-init.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateMachineDialog(
    ref: ImageRef,
    onDismiss: () -> Unit,
    onConfirm: (name: String, diskGb: Int, credentials: MazeViewModel.Credentials?) -> Unit,
) {
    var name by remember { mutableStateOf(ref.machineName) }
    var diskGb by remember { mutableIntStateOf(ref.image.recommendedDiskGb) }
    var username by remember { mutableStateOf(ref.image.defaultUser ?: "maze") }
    var password by remember { mutableStateOf("") }

    val needsCredentials = ref.image.bootStyle == BootStyle.CLOUD_IMAGE

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = maze.surface2,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.catalog_create_vm)) },
        text = {
            Column {
                DialogField(name, { name = it }, stringResource(R.string.editor_name))
                Spacer(Modifier.height(14.dp))

                Text(
                    text = "${stringResource(R.string.editor_disk_size)}: $diskGb GiB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(
                    value = diskGb.toFloat(),
                    onValueChange = { diskGb = it.toInt() },
                    valueRange = 4f..128f,
                    colors = SliderDefaults.colors(
                        thumbColor = maze.accent,
                        activeTrackColor = maze.accent,
                        inactiveTrackColor = maze.surface3,
                    ),
                )

                if (needsCredentials) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.catalog_no_credentials),
                        style = MaterialTheme.typography.bodySmall,
                        color = maze.textSecondary,
                    )
                    Spacer(Modifier.height(14.dp))
                    DialogField(username, { username = it }, stringResource(R.string.field_user))
                    Spacer(Modifier.height(12.dp))
                    DialogField(password, { password = it }, stringResource(R.string.field_password))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val credentials = if (needsCredentials && password.isNotBlank()) {
                        MazeViewModel.Credentials(username.trim(), password)
                    } else {
                        null
                    }
                    onConfirm(name, diskGb, credentials)
                },
                enabled = name.isNotBlank() && (!needsCredentials || password.isNotBlank()),
            ) {
                Text(stringResource(R.string.action_continue), color = maze.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = maze.textSecondary)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = maze.accent,
            unfocusedBorderColor = maze.border,
            focusedLabelColor = maze.accent,
            unfocusedLabelColor = maze.textSecondary,
            cursorColor = maze.accent,
        ),
    )
}

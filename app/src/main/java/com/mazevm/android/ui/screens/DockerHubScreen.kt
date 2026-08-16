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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mazevm.android.R
import com.mazevm.android.core.formatBytes
import com.mazevm.android.data.model.ContainerConfig
import com.mazevm.android.data.model.ImageReference
import com.mazevm.android.data.model.PullState
import com.mazevm.android.oci.DockerHub
import com.mazevm.android.ui.MazeViewModel
import com.mazevm.android.ui.NavigationBarClearance
import com.mazevm.android.ui.components.ChoiceChip
import com.mazevm.android.ui.components.MazeCard
import com.mazevm.android.ui.components.PillButton
import com.mazevm.android.ui.components.Tag
import com.mazevm.android.ui.theme.CapsuleShape
import com.mazevm.android.ui.theme.maze
import kotlinx.coroutines.launch

/**
 * Docker Hub, browsed and pulled without a daemon.
 *
 * Hub's catalogue API says which architectures a tag publishes, so a tag with no
 * linux/arm64 build is marked before anything is downloaded rather than after the pull
 * fails halfway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DockerHubScreen(
    viewModel: MazeViewModel,
    onContainerCreated: (ContainerConfig) -> Unit,
) {
    val hub = remember { DockerHub() }
    val scope = rememberCoroutineScope()
    val pulls by viewModel.pullStates.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(hub.suggestions()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<DockerHub.Repository?>(null) }

    // Debounced so a search does not fire on every keystroke.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = hub.suggestions()
            error = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)
        searching = true
        hub.search(query)
            .onSuccess { results = it; error = null }
            .onFailure { error = it.message }
        searching = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.hub_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = maze.accent,
                    )
                }
            },
            singleLine = true,
            shape = CapsuleShape,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Search,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = maze.accent,
                unfocusedBorderColor = maze.border,
                cursorColor = maze.accent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (!viewModel.containerRuntimeAvailable) {
            Text(
                text = stringResource(R.string.hub_runtime_missing),
                style = MaterialTheme.typography.bodySmall,
                color = maze.warning,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        error?.let {
            Text(
                text = stringResource(R.string.error_generic, it),
                style = MaterialTheme.typography.bodySmall,
                color = maze.danger,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp,
                bottom = 24.dp + NavigationBarClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(results, key = { it.name }) { repository ->
                RepositoryCard(
                    repository = repository,
                    state = pulls.values.firstOrNull(),
                    onClick = { selected = repository },
                )
            }
        }
    }

    selected?.let { repository ->
        PullSheet(
            hub = hub,
            repository = repository,
            viewModel = viewModel,
            onDismiss = { selected = null },
            onCreated = {
                selected = null
                onContainerCreated(it)
            },
        )
    }
}

@Composable
private fun RepositoryCard(
    repository: DockerHub.Repository,
    state: PullState?,
    onClick: () -> Unit,
) {
    MazeCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = repository.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (repository.description.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = repository.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = maze.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (repository.isOfficial) {
                Spacer(Modifier.size(8.dp))
                Tag(stringResource(R.string.hub_official))
            }
        }

        if (repository.stars > 0) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Star,
                    contentDescription = null,
                    tint = maze.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "${repository.stars}",
                    style = MaterialTheme.typography.labelMedium,
                    color = maze.textTertiary,
                )
            }
        }
    }
}

/** Tag picker plus the pull itself. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PullSheet(
    hub: DockerHub,
    repository: DockerHub.Repository,
    viewModel: MazeViewModel,
    onDismiss: () -> Unit,
    onCreated: (ContainerConfig) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pulls by viewModel.pullStates.collectAsStateWithLifecycle()

    var tags by remember(repository.name) { mutableStateOf<List<DockerHub.Tag>>(emptyList()) }
    var selectedTag by remember(repository.name) { mutableStateOf("latest") }
    var loading by remember(repository.name) { mutableStateOf(true) }
    var naming by remember { mutableStateOf(false) }

    LaunchedEffect(repository.name) {
        loading = true
        hub.tags(repository.reference).onSuccess { tags = it }
        loading = false
    }

    val current = tags.firstOrNull { it.name == selectedTag }
    val activePull = pulls.entries.firstOrNull { it.value.isBusy }?.value

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = maze.surface1,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = repository.name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (repository.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = repository.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = maze.textSecondary,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.hub_choose_tag),
                style = MaterialTheme.typography.labelLarge,
                color = maze.accent,
            )
            Spacer(Modifier.height(10.dp))

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = maze.accent,
                )
            } else {
                // Only arm64 tags are offered: nothing else can run on this device.
                val usable = tags.filter { it.supportsArm64 }
                if (usable.isEmpty()) {
                    Text(
                        text = stringResource(R.string.hub_no_arm64),
                        style = MaterialTheme.typography.bodyMedium,
                        color = maze.danger,
                    )
                } else {
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        usable.take(12).forEach { tag ->
                            ChoiceChip(
                                text = tag.name,
                                selected = tag.name == selectedTag,
                                onClick = { selectedTag = tag.name },
                            )
                        }
                    }
                }
            }

            current?.arm64SizeBytes?.let { size ->
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("ARM64")
                    Tag(formatBytes(size))
                }
            }

            Spacer(Modifier.height(22.dp))

            when {
                activePull is PullState.Resolving -> PullProgress(
                    stringResource(R.string.hub_resolving), 0f
                )
                activePull is PullState.Downloading -> PullProgress(
                    stringResource(
                        R.string.hub_pulling,
                        activePull.layer,
                        activePull.layerCount,
                        formatBytes(activePull.bytes),
                        formatBytes(activePull.totalBytes),
                    ),
                    activePull.fraction,
                )
                activePull is PullState.Extracting -> PullProgress(
                    stringResource(
                        R.string.hub_extracting,
                        activePull.layer,
                        activePull.layerCount,
                    ),
                    1f,
                )
                else -> PillButton(
                    text = stringResource(R.string.hub_pull),
                    onClick = { naming = true },
                    leading = Icons.Rounded.Download,
                    enabled = current?.supportsArm64 == true &&
                        viewModel.containerRuntimeAvailable,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            (pulls.values.firstOrNull() as? PullState.Failed)?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.error_generic, it.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = maze.danger,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (naming) {
        NameContainerDialog(
            suggestion = repository.reference.shortName,
            onDismiss = { naming = false },
            onConfirm = { name ->
                naming = false
                viewModel.createContainer(
                    reference = repository.reference.copy(tag = selectedTag),
                    name = name,
                    onCreated = onCreated,
                )
            },
        )
    }
}

@Composable
private fun PullProgress(label: String, fraction: Float) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NameContainerDialog(
    suggestion: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(suggestion) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = maze.surface2,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.hub_name_container)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.editor_name)) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = maze.accent,
                    unfocusedBorderColor = maze.border,
                    cursorColor = maze.accent,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.hub_pull), color = maze.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = maze.textSecondary)
            }
        },
    )
}

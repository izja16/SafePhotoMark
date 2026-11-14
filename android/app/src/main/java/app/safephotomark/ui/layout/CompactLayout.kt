package app.safephotomark.ui.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.safephotomark.AppUiState
import app.safephotomark.AppViewModel
import app.safephotomark.R
import app.safephotomark.ui.AppActions
import app.safephotomark.ui.components.InspectorPane
import app.safephotomark.ui.components.PhotoStrip
import app.safephotomark.ui.components.PreviewPane
import app.safephotomark.ui.components.ProcessingBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactLayout(
    state: AppUiState,
    viewModel: AppViewModel,
    actions: AppActions
) {
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = actions.onAbout) {
                        Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about_app, stringResource(R.string.app_name)))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            ProcessingBar(
                state = state,
                onStart = actions.onStart,
                onCancel = actions.onCancel,
                onOpenOutput = actions.onOpenOutput,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            )
        }
    ) { padding ->
        if (landscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                ) {
                    PreviewPane(
                        state = state,
                        viewModel = viewModel,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                    PhotoStrip(
                        state = state,
                        onSelect = viewModel::selectPhoto,
                        onRemove = viewModel::removePhoto,
                        onClear = viewModel::clearPhotos,
                        onChoosePhotos = actions.onChoosePhotos,
                        onChooseFiles = actions.onChooseFiles,
                        onChooseFolder = actions.onChoosePhotoFolder,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                InspectorPane(
                    state = state,
                    viewModel = viewModel,
                    actions = actions,
                    showStartButton = false,
                    modifier = Modifier
                        .weight(0.95f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                PreviewPane(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                PhotoStrip(
                    state = state,
                    onSelect = viewModel::selectPhoto,
                    onRemove = viewModel::removePhoto,
                    onClear = viewModel::clearPhotos,
                    onChoosePhotos = actions.onChoosePhotos,
                    onChooseFiles = actions.onChooseFiles,
                    onChooseFolder = actions.onChoosePhotoFolder,
                    modifier = Modifier.fillMaxWidth()
                )
                InspectorPane(
                    state = state,
                    viewModel = viewModel,
                    actions = actions,
                    showStartButton = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

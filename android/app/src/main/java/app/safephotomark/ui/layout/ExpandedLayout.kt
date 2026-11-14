package app.safephotomark.ui.layout

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.safephotomark.AppUiState
import app.safephotomark.AppViewModel
import app.safephotomark.R
import app.safephotomark.ui.AppActions
import app.safephotomark.ui.components.InspectorPane
import app.safephotomark.ui.components.PhotoListPane
import app.safephotomark.ui.components.PreviewPane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandedLayout(
    state: AppUiState,
    viewModel: AppViewModel,
    actions: AppActions,
    widthClass: WindowWidthSizeClass
) {
    val listWidth = if (widthClass == WindowWidthSizeClass.Expanded) 280.dp else 240.dp
    val inspectorWidth = if (widthClass == WindowWidthSizeClass.Expanded) 360.dp else 320.dp

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = actions.onAbout) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.about_app, stringResource(R.string.app_name))
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PhotoListPane(
                state = state,
                onSelect = viewModel::selectPhoto,
                onRemove = viewModel::removePhoto,
                onClear = viewModel::clearPhotos,
                onChoosePhotos = actions.onChoosePhotos,
                onChooseFiles = actions.onChooseFiles,
                onChooseFolder = actions.onChoosePhotoFolder,
                modifier = Modifier
                    .width(listWidth)
                    .fillMaxHeight()
            )
            VerticalDivider()
            PreviewPane(
                state = state,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            VerticalDivider()
            InspectorPane(
                state = state,
                viewModel = viewModel,
                actions = actions,
                showStartButton = true,
                modifier = Modifier
                    .widthIn(max = inspectorWidth)
                    .width(inspectorWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

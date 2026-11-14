package app.safephotomark.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.safephotomark.AppViewModel
import app.safephotomark.ImportServiceLimit
import app.safephotomark.R
import app.safephotomark.ui.components.AboutScreen
import app.safephotomark.ui.layout.CompactLayout
import app.safephotomark.ui.layout.ExpandedLayout

@Composable
fun SafePhotoMarkApp(
    sizeClass: WindowSizeClass,
    viewModel: AppViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val importLimit by viewModel.importLimit.collectAsStateWithLifecycle()
    var showAbout by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(ImportServiceLimit.photoLimit)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPhotoUris(uris)
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addPhotoUris(uris)
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Import images from the chosen folder (read-only for this action).
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.addPhotoUris(listOf(uri))
        }
    }

    val watermarkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.setWatermark(uri)
    }

    val outputPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setOutputTree(uri)
    }

    val actions = remember(viewModel) {
        AppActions(
            onChoosePhotos = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onChooseFiles = {
                filePicker.launch(arrayOf("image/*", "image/jpeg", "image/png", "image/heic", "image/heif", "image/tiff", "image/gif", "image/bmp", "image/webp"))
            },
            onChoosePhotoFolder = { folderPicker.launch(null) },
            onChooseWatermark = {
                watermarkPicker.launch(arrayOf("image/png", "image/svg+xml", "text/xml", "application/xml"))
            },
            onChooseOutput = { outputPicker.launch(null) },
            onStart = { viewModel.startProcessing() },
            onCancel = { viewModel.cancelProcessing() },
            onAbout = { showAbout = true },
            onOpenOutput = {
                viewModel.outputFolderViewIntent()?.let { intent ->
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (showAbout) {
            AboutScreen(onBack = { showAbout = false })
        } else {
            val expanded = sizeClass.widthSizeClass != WindowWidthSizeClass.Compact
            if (expanded) {
                ExpandedLayout(
                    state = state,
                    viewModel = viewModel,
                    actions = actions,
                    widthClass = sizeClass.widthSizeClass
                )
            } else {
                CompactLayout(
                    state = state,
                    viewModel = viewModel,
                    actions = actions
                )
            }
        }
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeMessage() },
            title = { Text(msg.title) },
            text = { Text(msg.body) },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeMessage() }) {
                    Text(context.getString(R.string.ok))
                }
            }
        )
    }
    importLimit?.let { event ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeImportLimit() },
            title = { Text(context.getString(R.string.alert_import_limit_title)) },
            text = {
                Text(context.getString(R.string.alert_import_limit_message, event.importedCap, event.discovered))
            },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeImportLimit() }) {
                    Text(context.getString(R.string.ok))
                }
            }
        )
    }
}

data class AppActions(
    val onChoosePhotos: () -> Unit,
    val onChooseFiles: () -> Unit,
    val onChoosePhotoFolder: () -> Unit,
    val onChooseWatermark: () -> Unit,
    val onChooseOutput: () -> Unit,
    val onStart: () -> Unit,
    val onCancel: () -> Unit,
    val onAbout: () -> Unit,
    val onOpenOutput: () -> Unit
)

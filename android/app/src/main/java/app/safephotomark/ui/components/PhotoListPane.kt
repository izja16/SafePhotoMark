package app.safephotomark.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.safephotomark.AppUiState
import app.safephotomark.PhotoItem
import app.safephotomark.R
import app.safephotomark.processing.PhotoStatusKind
import java.util.UUID

@Composable
fun PhotoListPane(
    state: AppUiState,
    onSelect: (UUID) -> Unit,
    onRemove: (UUID) -> Unit,
    onClear: () -> Unit,
    onChoosePhotos: () -> Unit,
    onChooseFiles: () -> Unit,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.photos_count, state.photos.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onChoosePhotos, enabled = !state.isProcessing, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = stringResource(R.string.choose_photos))
            }
            IconButton(onClick = onChooseFiles, enabled = !state.isProcessing, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.choose_photos_files))
            }
            IconButton(onClick = onChooseFolder, enabled = !state.isProcessing, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.choose_photo_folder))
            }
            IconButton(onClick = onClear, enabled = !state.isProcessing && state.photos.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = stringResource(R.string.clear_list))
            }
        }
        if (state.photos.isEmpty()) {
            Text(
                stringResource(R.string.preview_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(state.photos, key = { it.id }) { photo ->
                    PhotoRow(
                        photo = photo,
                        selected = photo.id == state.selectedPhoto?.id,
                        statusLabel = statusLabel(photo),
                        enabled = !state.isProcessing,
                        onSelect = { onSelect(photo.id) },
                        onRemove = { onRemove(photo.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun PhotoStrip(
    state: AppUiState,
    onSelect: (UUID) -> Unit,
    onRemove: (UUID) -> Unit,
    onClear: () -> Unit,
    onChoosePhotos: () -> Unit,
    onChooseFiles: () -> Unit,
    onChooseFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Text(
                stringResource(R.string.photos_count, state.photos.size),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onChoosePhotos, enabled = !state.isProcessing) {
                Text(stringResource(R.string.choose_photos))
            }
            TextButton(onClick = onChooseFiles, enabled = !state.isProcessing) {
                Text(stringResource(R.string.choose_photos_files))
            }
            TextButton(onClick = onChooseFolder, enabled = !state.isProcessing) {
                Text(stringResource(R.string.choose_photo_folder))
            }
            if (state.photos.isNotEmpty()) {
                TextButton(onClick = onClear, enabled = !state.isProcessing) {
                    Text(stringResource(R.string.clear_list))
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.photos, key = { it.id }) { photo ->
                PhotoThumb(
                    photo = photo,
                    selected = photo.id == state.selectedPhoto?.id,
                    enabled = true,
                    onSelect = { onSelect(photo.id) },
                    onRemove = { if (!state.isProcessing) onRemove(photo.id) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoRow(
    photo: PhotoItem,
    selected: Boolean,
    statusLabel: String,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .combinedClickable(onClick = onSelect, onLongClick = { if (enabled) menu = true })
            .padding(8.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PhotoThumbImage(photo, Modifier.size(48.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(photo.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_from_list)) },
                onClick = { menu = false; onRemove() },
                enabled = enabled
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoThumb(
    photo: PhotoItem,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(borderColor)
                .padding(if (selected) 3.dp else 1.dp)
                .clip(RoundedCornerShape(6.dp))
                .combinedClickable(onClick = onSelect, onLongClick = { menu = true })
        ) {
            PhotoThumbImage(photo, Modifier.fillMaxSize())
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remove_from_list)) },
                onClick = { menu = false; onRemove() },
                enabled = enabled
            )
        }
    }
}

@Composable
private fun PhotoThumbImage(photo: PhotoItem, modifier: Modifier) {
    val thumb = photo.thumbnail
    if (thumb != null) {
        Image(
            bitmap = thumb.asImageBitmap(),
            contentDescription = photo.filename,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        CheckerboardBackground(modifier)
    }
}

@Composable
private fun statusLabel(photo: PhotoItem): String {
    return when (photo.status.kind) {
        PhotoStatusKind.WAITING -> stringResource(R.string.status_waiting)
        PhotoStatusKind.PROCESSING -> stringResource(R.string.status_processing)
        PhotoStatusKind.SUCCEEDED -> stringResource(R.string.status_succeeded)
        PhotoStatusKind.FAILED -> stringResource(R.string.status_failed, photo.status.message ?: "")
    }
}

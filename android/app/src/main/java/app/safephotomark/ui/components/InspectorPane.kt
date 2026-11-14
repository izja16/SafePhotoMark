package app.safephotomark.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.safephotomark.AppUiState
import app.safephotomark.AppViewModel
import app.safephotomark.R
import app.safephotomark.processing.OutputFormat
import app.safephotomark.processing.WatermarkCompositor
import app.safephotomark.processing.WatermarkMode
import app.safephotomark.processing.WatermarkPosition
import app.safephotomark.ui.AppActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorPane(
    state: AppUiState,
    viewModel: AppViewModel,
    actions: AppActions,
    showStartButton: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle(stringResource(R.string.section_watermark))
        WatermarkWell(state = state, onChoose = actions.onChooseWatermark)

        LabeledSlider(
            label = stringResource(R.string.size),
            valueLabel = "${state.sizePercent.toInt()}%",
            value = state.sizePercent.toFloat(),
            range = WatermarkCompositor.sizePercentRange.start.toFloat()..WatermarkCompositor.sizePercentRange.endInclusive.toFloat(),
            enabled = !state.isProcessing,
            onChange = { viewModel.setSizePercent(it.toDouble()) },
            onFinished = { viewModel.flushSettings() }
        )
        Hint(stringResource(R.string.size_hint))

        LabeledSlider(
            label = stringResource(R.string.opacity),
            valueLabel = "${(state.opacity * 100).toInt()}%",
            value = state.opacity.toFloat(),
            range = 0f..1f,
            enabled = !state.isProcessing,
            onChange = { viewModel.setOpacity(it.toDouble()) },
            onFinished = { viewModel.flushSettings() }
        )

        val modes = listOf(WatermarkMode.MANUAL, WatermarkMode.RANDOM, WatermarkMode.FIXED)
        val modeLabels = listOf(
            stringResource(R.string.mode_manual),
            stringResource(R.string.mode_random),
            stringResource(R.string.mode_fixed)
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.watermarkMode == mode,
                    onClick = { if (!state.isProcessing) viewModel.setWatermarkMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    enabled = !state.isProcessing
                ) {
                    Text(modeLabels[index])
                }
            }
        }
        when (state.watermarkMode) {
            WatermarkMode.MANUAL -> Hint(stringResource(R.string.mode_manual_hint))
            WatermarkMode.RANDOM -> Hint(stringResource(R.string.mode_random_hint))
            WatermarkMode.FIXED -> {
                PositionDropdown(
                    position = state.position,
                    enabled = !state.isProcessing,
                    onSelect = { viewModel.setPosition(it) }
                )
            }
        }

        SectionTitle(stringResource(R.string.section_output))
        FormatDropdown(
            format = state.outputFormat,
            enabled = !state.isProcessing,
            onSelect = { viewModel.setOutputFormat(it) }
        )
        if (state.outputFormat.usesLossyQuality) {
            LabeledSlider(
                label = stringResource(R.string.quality),
                valueLabel = "${(state.quality * 100).toInt()}%",
                value = state.quality.toFloat(),
                range = 0.5f..1f,
                enabled = !state.isProcessing,
                onChange = { viewModel.setQuality(it.toDouble()) },
                onFinished = { viewModel.flushSettings() }
            )
            Hint(stringResource(R.string.quality_hint))
        }

        ToggleRow(
            title = stringResource(R.string.strip_metadata),
            checked = state.stripMetadata,
            enabled = !state.isProcessing,
            onChecked = { viewModel.setStripMetadata(it) }
        )
        Hint(stringResource(R.string.strip_metadata_hint))

        ToggleRow(
            title = stringResource(R.string.random_rename),
            checked = state.randomRename,
            enabled = !state.isProcessing,
            onChecked = { viewModel.setRandomRename(it) }
        )
        Hint(stringResource(R.string.random_rename_hint))

        Text(stringResource(R.string.output_folder), style = MaterialTheme.typography.titleSmall)
        Text(
            state.outputFolderName ?: stringResource(R.string.output_folder_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = actions.onChooseOutput, enabled = !state.isProcessing) {
            Text(stringResource(R.string.choose_output_folder))
        }
        Hint(stringResource(R.string.output_folder_hint))

        SectionTitle(stringResource(R.string.section_processing))
        ToggleRow(
            title = stringResource(R.string.remember_settings),
            checked = state.rememberSettings,
            enabled = !state.isProcessing,
            onChecked = { viewModel.setRememberSettings(it) }
        )
        Hint(stringResource(R.string.remember_settings_hint))

        ToggleRow(
            title = stringResource(R.string.completion_sound),
            checked = state.playCompletionSound,
            enabled = true,
            onChecked = { viewModel.setPlayCompletionSound(it) }
        )

        if (showStartButton) {
            ProcessingControls(state = state, actions = actions)
        }

        Hint(stringResource(R.string.privacy_note))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun ProcessingControls(
    state: AppUiState,
    actions: AppActions,
    startAligned: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (state.isProcessing) {
            val total = state.processingTotal.coerceAtLeast(1)
            LinearProgressIndicator(
                progress = { state.processedCount.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                stringResource(R.string.processing_progress, state.processedCount, state.processingTotal),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            TextButton(onClick = actions.onCancel, enabled = !state.isCancelling) {
                Text(stringResource(R.string.cancel))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (startAligned) Arrangement.Start else Arrangement.Start
            ) {
                Button(
                    onClick = actions.onStart,
                    enabled = state.canStartProcessing,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.start_processing))
                }
            }
            when {
                state.photos.isEmpty() -> Hint(stringResource(R.string.import_photos_first))
                state.watermark == null -> Hint(stringResource(R.string.choose_watermark_first))
                state.outputTreeUri == null -> Hint(stringResource(R.string.choose_output_first))
            }
            if (state.successCount + state.failureCount > 0) {
                Text(
                    stringResource(R.string.finished_summary, state.successCount, state.failureCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                TextButton(
                    onClick = actions.onOpenOutput,
                    enabled = state.outputTreeUri != null || state.lastRunSucceeded.isNotEmpty()
                ) {
                    Text(stringResource(R.string.open_output_folder))
                }
            }
        }
    }
}

@Composable
fun ProcessingBar(
    state: AppUiState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenOutput: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.Surface(
        tonalElevation = 3.dp,
        modifier = modifier
    ) {
        ProcessingControls(
            state = state,
            actions = AppActions(
                onChoosePhotos = {},
                onChooseFiles = {},
                onChoosePhotoFolder = {},
                onChooseWatermark = {},
                onChooseOutput = {},
                onStart = onStart,
                onCancel = onCancel,
                onAbout = {},
                onOpenOutput = onOpenOutput
            ),
            startAligned = true,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatDropdown(
    format: OutputFormat,
    enabled: Boolean,
    onSelect: (OutputFormat) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = formatLabel(format)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.format)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OutputFormat.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(formatLabel(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PositionDropdown(
    position: WatermarkPosition,
    enabled: Boolean,
    onSelect: (WatermarkPosition) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = positionLabel(position),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.position)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            WatermarkPosition.entries.forEach { item ->
                DropdownMenuItem(
                    text = { Text(positionLabel(item)) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun formatLabel(format: OutputFormat): String = stringResource(
    when (format) {
        OutputFormat.JPEG -> R.string.format_jpeg
        OutputFormat.PNG -> R.string.format_png
        OutputFormat.HEIF -> R.string.format_heif
        OutputFormat.TIFF -> R.string.format_tiff
        OutputFormat.SAME_AS_SOURCE -> R.string.format_same_as_source
    }
)

@Composable
private fun positionLabel(position: WatermarkPosition): String = stringResource(
    when (position) {
        WatermarkPosition.TOP_LEFT -> R.string.pos_top_left
        WatermarkPosition.TOP_CENTER -> R.string.pos_top_center
        WatermarkPosition.TOP_RIGHT -> R.string.pos_top_right
        WatermarkPosition.MIDDLE_LEFT -> R.string.pos_middle_left
        WatermarkPosition.CENTER -> R.string.pos_center
        WatermarkPosition.MIDDLE_RIGHT -> R.string.pos_middle_right
        WatermarkPosition.BOTTOM_LEFT -> R.string.pos_bottom_left
        WatermarkPosition.BOTTOM_CENTER -> R.string.pos_bottom_center
        WatermarkPosition.BOTTOM_RIGHT -> R.string.pos_bottom_right
    }
)

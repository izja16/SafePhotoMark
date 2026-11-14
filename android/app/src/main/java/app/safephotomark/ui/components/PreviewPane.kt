package app.safephotomark.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.safephotomark.AppUiState
import app.safephotomark.AppViewModel
import app.safephotomark.R
import app.safephotomark.processing.PointD
import app.safephotomark.processing.RectD
import app.safephotomark.processing.SizeD
import app.safephotomark.processing.WatermarkCompositor
import app.safephotomark.processing.WatermarkMode
import kotlin.math.min

@Composable
fun PreviewPane(
    state: AppUiState,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            state.selectedPhoto?.filename?.let { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            CheckerboardBackground(Modifier.fillMaxSize())
            val preview = state.previewImage
            if (preview != null) {
                WatermarkPreviewOverlay(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.Visibility,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        state.previewNote ?: stringResource(R.string.preview_select_photo),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            val hint = when {
                state.previewImage != null && state.watermark != null ->
                    state.previewNote ?: stringResource(R.string.preview_drag_hint)
                state.previewImage != null -> state.previewNote
                else -> null
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (state.previewImage != null && state.watermark != null) {
                if (state.selectedPlacementIsManual) {
                    TextButton(onClick = { viewModel.resetSelectedPlacement() }, enabled = !state.isProcessing) {
                        Text(stringResource(R.string.reset_position))
                    }
                }
                if (state.watermarkMode == WatermarkMode.RANDOM) {
                    TextButton(onClick = { viewModel.rerollSelectedPlacement() }, enabled = !state.isProcessing) {
                        Text(stringResource(R.string.reroll_position))
                    }
                }
            }
        }
    }
}

@Composable
private fun WatermarkPreviewOverlay(
    state: AppUiState,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier
) {
    val preview = state.previewImage ?: return
    val imageBitmap = remember(preview) { preview.asImageBitmap() }
    val canvas = SizeD(preview.width.toDouble(), preview.height.toDouble())
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var dragCenter by remember { mutableStateOf<PointD?>(null) }
    val density = LocalDensity.current

    val display = fittedRect(
        imageWidth = preview.width.toFloat(),
        imageHeight = preview.height.toFloat(),
        containerWidth = boxSize.width.toFloat(),
        containerHeight = boxSize.height.toFloat()
    )

    val layout = if (dragCenter != null && state.watermark != null) {
        val mark = WatermarkCompositor.markSize(canvas, state.sizePercent, state.watermark!!.aspectRatio)
        val center = WatermarkCompositor.clampedCenter(canvas, mark, dragCenter!!)
        val rect = RectD(
            x = center.x * canvas.width - mark.width / 2.0,
            y = center.y * canvas.height - mark.height / 2.0,
            width = mark.width,
            height = mark.height
        )
        Triple(mark, rect, center)
    } else {
        viewModel.displayLayout(canvas)?.let { Triple(it.first, it.second, it.third.normalizedCenter) }
    }

    val overlayBitmap = remember(state.watermark, layout?.first, display.width) {
        val frameWidth = if (layout != null && display.width > 0f && canvas.width > 0) {
            (layout.second.width * (display.width / canvas.width)).toFloat()
        } else 0f
        if (frameWidth > 0f) state.watermark?.bitmapForDisplay(frameWidth * density.density) else null
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .fillMaxSize()
    ) {
        if (display.width > 0f && display.height > 0f) {
            Image(
                bitmap = imageBitmap,
                contentDescription = state.selectedPhoto?.filename,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = with(density) { display.left.toDp() },
                        top = with(density) { display.top.toDp() }
                    )
                    .size(
                        width = with(density) { display.width.toDp() },
                        height = with(density) { display.height.toDp() }
                    )
            )
            if (layout != null && overlayBitmap != null && canvas.width > 0) {
                val scale = display.width / canvas.width.toFloat()
                val frame = Rect(
                    left = display.left + layout.second.minX.toFloat() * scale,
                    top = display.top + layout.second.minY.toFloat() * scale,
                    right = display.left + layout.second.maxX.toFloat() * scale,
                    bottom = display.top + layout.second.maxY.toFloat() * scale
                )
                val mark = layout.first
                Image(
                    bitmap = overlayBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    alpha = state.opacity.toFloat().coerceIn(0f, 1f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = with(density) { frame.left.toDp() },
                            top = with(density) { frame.top.toDp() }
                        )
                        .size(
                            width = with(density) { frame.width.toDp() },
                            height = with(density) { frame.height.toDp() }
                        )
                        .pointerInput(state.isProcessing, display, canvas, mark) {
                            if (state.isProcessing) return@pointerInput
                            detectDragGestures(
                                onDragStart = {
                                    dragCenter = layout.third
                                },
                                onDrag = { _, amount ->
                                    if (display.width <= 0f || display.height <= 0f) return@detectDragGestures
                                    val current = dragCenter ?: layout.third
                                    dragCenter = PointD(
                                        x = current.x + amount.x / display.width,
                                        y = current.y + amount.y / display.height
                                    )
                                },
                                onDragEnd = {
                                    val current = dragCenter
                                    if (current != null) {
                                        viewModel.setManualCenter(current, canvas, mark)
                                    }
                                    dragCenter = null
                                },
                                onDragCancel = { dragCenter = null }
                            )
                        }
                )
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = with(density) { frame.left.toDp() },
                            top = with(density) { frame.top.toDp() }
                        )
                        .size(
                            width = with(density) { frame.width.toDp() },
                            height = with(density) { frame.height.toDp() }
                        )
                ) {
                    val stroke = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.9f),
                        style = stroke,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.35f),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()), 4.dp.toPx())
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }
        }
    }
}

private fun fittedRect(imageWidth: Float, imageHeight: Float, containerWidth: Float, containerHeight: Float): Rect {
    if (imageWidth <= 0f || imageHeight <= 0f || containerWidth <= 0f || containerHeight <= 0f) {
        return Rect.Zero
    }
    val scale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    val x = (containerWidth - w) / 2f
    val y = (containerHeight - h) / 2f
    return Rect(Offset(x, y), Size(w, h))
}


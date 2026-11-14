package app.safephotomark.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import app.safephotomark.ui.theme.CheckerDark
import app.safephotomark.ui.theme.CheckerDarkDarkTheme
import app.safephotomark.ui.theme.CheckerLight
import app.safephotomark.ui.theme.CheckerLightDarkTheme

@Composable
fun CheckerboardBackground(modifier: Modifier = Modifier, cellDp: Float = 8f) {
    val darkTheme = isSystemInDarkTheme()
    val light = if (darkTheme) CheckerLightDarkTheme else CheckerLight
    val dark = if (darkTheme) CheckerDarkDarkTheme else CheckerDark
    Canvas(modifier = modifier) {
        val cell = cellDp.dp.toPx()
        drawRect(light)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = 0
            while (x < size.width) {
                if ((row + col) % 2 == 0) {
                    drawRect(dark, topLeft = Offset(x, y), size = Size(cell, cell))
                }
                x += cell
                col++
            }
            y += cell
            row++
        }
    }
}

package app.safephotomark.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colors sampled from the app icon: deep teal landscape, warm sand shield.
private val Teal = Color(0xFF1A5A73)
private val TealDark = Color(0xFF7FB4C8)
private val Sand = Color(0xFFE8C9A8)
private val Deep = Color(0xFF0B334D)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7E4EE),
    onPrimaryContainer = Color(0xFF003044),
    secondary = Color(0xFF8A5A3B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3DDCC),
    onSecondaryContainer = Color(0xFF2F1600),
    tertiary = Color(0xFF3D6B5A),
    background = Color(0xFFF7F4EF),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFF7F4EF),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFE3E3DC),
    onSurfaceVariant = Color(0xFF454743),
    outline = Color(0xFF757873)
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF003044),
    primaryContainer = Color(0xFF0F4560),
    onPrimaryContainer = Color(0xFFC7E4EE),
    secondary = Sand,
    onSecondary = Color(0xFF2F1600),
    secondaryContainer = Color(0xFF5C3B26),
    onSecondaryContainer = Color(0xFFF3DDCC),
    tertiary = Color(0xFFA5D0BE),
    background = Color(0xFF0E1A22),
    onBackground = Color(0xFFE4E2DD),
    surface = Color(0xFF0E1A22),
    onSurface = Color(0xFFE4E2DD),
    surfaceVariant = Color(0xFF2C3A42),
    onSurfaceVariant = Color(0xFFC4C7C3),
    outline = Color(0xFF8E918D)
)

@Composable
fun SafePhotoMarkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

val CheckerLight = Color(0xFFEDECE8)
val CheckerDark = Color(0xFFD5D4CF)
val CheckerLightDarkTheme = Color(0xFF1A262E)
val CheckerDarkDarkTheme = Color(0xFF24323B)

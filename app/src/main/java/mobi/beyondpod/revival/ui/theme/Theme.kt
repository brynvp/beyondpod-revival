package mobi.beyondpod.revival.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary          = BeyondPodBlueLight,
    onPrimary        = Color.White,
    primaryContainer = BeyondPodBlueDark,
    secondary        = BeyondPodOrange,
    background       = SurfaceDark,
    surface          = SurfaceDark,
    surfaceVariant   = SurfaceVariantDark,
    onBackground     = OnSurfaceDark,
    onSurface        = OnSurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary    = BeyondPodBlue,
    secondary  = BeyondPodOrange
)

@Composable
fun BeyondPodTheme(
    // Default: dark (§12 — "Default theme: Dark"). Light/System offered in Phase 7 Settings.
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
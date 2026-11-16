package fr.reunion.enregistreur.ui.theme

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
import fr.reunion.enregistreur.export.speakerColorHex

private val Light = lightColorScheme(
    primary = Color(0xFF2E4A7D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF565E71), tertiary = Color(0xFF8B4A62),
    error = Color(0xFFBA1A1A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFADC6FF), onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF16336A), onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC), tertiary = Color(0xFFFFB0C9),
)

@Composable
fun EnregistreurTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Couleur d'un intervenant, éclaircie en thème sombre pour rester lisible. */
@Composable
fun speakerColor(index: Int): Color {
    val c = Color(("FF" + speakerColorHex(index)).toLong(16))
    return if (isSystemInDarkTheme()) lerp(c, Color.White, 0.35f) else c
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

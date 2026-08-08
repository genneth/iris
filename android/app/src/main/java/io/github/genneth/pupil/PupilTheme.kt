package io.github.genneth.pupil

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

private val BluetoothBlue = Color(0xFF0082FC)
private val FallbackLight = lightColorScheme(primary = BluetoothBlue)
private val FallbackDark = darkColorScheme(primary = BluetoothBlue)

@Composable
fun PupilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Deterministic screenshot tests opt out; the real app follows the Find N6's wallpaper.
    val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) FallbackDark else FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

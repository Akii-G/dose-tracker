package io.github.akiig.dose.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The same colours as Sottovoce: its icon's violet, lavender, and the soft red of "Bad". */
object Palette {
    val accent = Color(0xff5c56b4)
    val taken = Color(0xff6f67c9)
    val some = Color(0xffc9c4f2)
    val none = Color(0xffeb9a94)
    val warning = Color(0xffcf3f3f)

    /** iOS's switch green. */
    val on @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xff30d158) else Color(0xff34c759)
}

// Grouped-list colours, as on the iPhone: grey page, white cards (black and dark grey at night).
private val light = lightColorScheme(
    primary = Color(0xff5c56b4), onPrimary = Color.White,
    background = Color(0xfff2f2f7), onBackground = Color.Black,
    surface = Color(0xfff2f2f7), onSurface = Color.Black,
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerLow = Color.White,
    surfaceContainerHighest = Color(0xffe5e5ea), surfaceVariant = Color(0xffe5e5ea),
    onSurfaceVariant = Color(0xff8a8a8e), outline = Color(0xffc6c6c8), outlineVariant = Color(0xffd8d8dc),
    error = Color(0xffff3b30), secondaryContainer = Color(0xffe5e5ea), onSecondaryContainer = Color.Black,
    primaryContainer = Color(0xffe4e1f7), onPrimaryContainer = Color(0xff3d3880),
)
private val dark = darkColorScheme(
    primary = Color(0xff8e86d8), onPrimary = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceContainer = Color(0xff1c1c1e), surfaceContainerHigh = Color(0xff1c1c1e), surfaceContainerLow = Color(0xff1c1c1e),
    surfaceContainerHighest = Color(0xff2c2c2e), surfaceVariant = Color(0xff2c2c2e),
    onSurfaceVariant = Color(0xff8e8e93), outline = Color(0xff48484a), outlineVariant = Color(0xff38383a),
    error = Color(0xffff453a), secondaryContainer = Color(0xff2c2c2e), onSecondaryContainer = Color.White,
    primaryContainer = Color(0xff34306a), onPrimaryContainer = Color(0xffe4e1f7),
)

private val type = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        headlineSmall = headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontSize = 17.sp, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontSize = 13.sp, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.sp),
    )
}

@Composable
fun DoseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, typography = type, content = content)
}

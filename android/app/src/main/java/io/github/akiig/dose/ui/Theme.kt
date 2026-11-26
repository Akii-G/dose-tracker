package io.github.akiig.dose.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** A colour tone: accent, accent at night, "all taken", "some taken". The PC has the same (TONES). */
data class Tone(val key: String, val name: String, val accent: Color, val night: Color, val taken: Color, val some: Color)

object Tones {
    val all = listOf(
        Tone("violet", "Violet", Color(0xff5c56b4), Color(0xff8e86d8), Color(0xff6f67c9), Color(0xffc9c4f2)),
        Tone("blue", "Blue", Color(0xff2f6fcf), Color(0xff6fa3ee), Color(0xff3f7fe0), Color(0xffc3d8f7)),
        Tone("teal", "Teal", Color(0xff1f7f7c), Color(0xff4fc2bd), Color(0xff2a9a96), Color(0xffbfe6e3)),
        Tone("green", "Green", Color(0xff3a7f47), Color(0xff6cc47c), Color(0xff48a058), Color(0xffcbe9cf)),
        Tone("rose", "Rose", Color(0xffb0466f), Color(0xffe48ab0), Color(0xffc85a8c), Color(0xfff3cadb)),
        Tone("amber", "Amber", Color(0xffa8681a), Color(0xffe8a64a), Color(0xffcc8526), Color(0xfff5dcb3)),
    )
    /** The chosen tone; changing it recolours the app at once. */
    var current by androidx.compose.runtime.mutableStateOf(all[0])
    fun choose(key: String) { current = all.firstOrNull { it.key == key } ?: all[0] }
    fun label(t: Tone) = when (t.key) {
        "blue" -> io.github.akiig.dose.tr("Blue"); "teal" -> io.github.akiig.dose.tr("Teal"); "green" -> io.github.akiig.dose.tr("Green")
        "rose" -> io.github.akiig.dose.tr("Rose"); "amber" -> io.github.akiig.dose.tr("Amber"); else -> io.github.akiig.dose.tr("Violet")
    }
}

/** Violet by default, like Sottovoce's icon, with lavender and the soft red of "Bad". */
object Palette {
    val accent get() = Tones.current.accent
    val taken get() = Tones.current.taken
    val some get() = Tones.current.some
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
    val t = Tones.current
    val scheme = if (isSystemInDarkTheme()) dark.copy(primary = t.night, primaryContainer = t.accent.copy(alpha = 0.35f).compositeOver(Color.Black),
                                                      onPrimaryContainer = t.some)
                 else light.copy(primary = t.accent, primaryContainer = t.some.copy(alpha = 0.6f).compositeOver(Color.White),
                                 onPrimaryContainer = t.accent)
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}

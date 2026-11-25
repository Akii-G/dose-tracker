package io.github.akiig.dose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import io.github.akiig.dose.tr

/** True with large text (Android's font size at 130% or more): rows stack instead of squeezing. */
@Composable
fun isLarge(): Boolean = LocalDensity.current.fontScale >= 1.3f

/** A size that grows with the text, but only up to `cap` times (for fixed-width things like the calendar). */
@Composable
fun capped(size: TextUnit, cap: Float = 1.35f): TextUnit {
    val scale = LocalDensity.current.fontScale
    return if (scale <= cap) size else (size.value * cap / scale).sp
}

/** The iPhone's small centred title, with buttons on the right (Today, History). */
@Composable
fun InlineTitle(title: String, trailing: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 56.dp)
        .padding(horizontal = 16.dp)) {
        Text(title, Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** A scrolling grouped-list page, leaving room for the floating tab bar. */
@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        content()
        Spacer(Modifier.height(TabBarSpace))
    }
}

/** A plain section header that isn't upper-cased, like a time "08:00". */
@Composable
fun HeaderSection(header: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(start = 16.dp, bottom = 6.dp)) { header() }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp), content = content)
    }
}

/** iOS's .borderedProminent (filled violet) and .bordered (light violet) buttons. */
@Composable
fun PillButton(label: String, prominent: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val ink = if (prominent) Color.White else MaterialTheme.colorScheme.primary
    Box(modifier.heightIn(min = 34.dp).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick)
        .semantics { role = Role.Button }.padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
        Text(label, color = ink, style = MaterialTheme.typography.bodyMedium, fontWeight = if (prominent) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/**
 * A list row: the main part and the trailing things (buttons, a status) side by side; with large
 * text the trailing things go on their own line under it, full width, so nothing is squeezed.
 */
@Composable
fun AdaptiveRow(modifier: Modifier = Modifier, main: @Composable (Modifier) -> Unit, trailing: @Composable RowScope.() -> Unit) {
    if (isLarge()) {
        Column(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            main(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            main(Modifier.weight(1f))
            trailing()
        }
    }
}

/** Buttons under a row share its width with large text. */
@Composable
fun RowScope.wide(): Modifier = if (isLarge()) Modifier.weight(1f) else Modifier

/** iOS's stepper: a label, then a grey "− | +" pill. */
@Composable
fun StepperRow(label: String, canMinus: Boolean = true, canPlus: Boolean = true, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 10.dp), style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).height(stepH()),
            verticalAlignment = Alignment.CenterVertically) {
            StepHalf(Icons.Filled.Remove, tr("Less"), canMinus, onMinus)
            VerticalDivider(Modifier.height(18.dp), color = MaterialTheme.colorScheme.outline)
            StepHalf(Icons.Filled.Add, tr("More"), canPlus, onPlus)
        }
    }
}

@Composable
private fun stepH() = 32.dp * minOf(LocalDensity.current.fontScale, 1.6f).coerceAtLeast(1f)

@Composable
private fun StepHalf(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val k = minOf(LocalDensity.current.fontScale, 1.6f).coerceAtLeast(1f)
    Box(Modifier.width(47.dp * k).height(stepH()).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(20.dp * k),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

/** A medicine's name in bold with a grey line under it. */
@Composable
fun TwoLines(title: String, detail: String, modifier: Modifier = Modifier, detailColor: Color = Color.Unspecified) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = if (detailColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else detailColor)
    }
}

/** A plus in the top bar. */
@Composable
fun AddButton(onClick: () -> Unit) = CircleButton(Icons.Filled.Add, tr("Add a medicine"), onClick)

/** The empty Today: what Dose does and one button. */
@Composable
fun Empty(icon: ImageVector, title: String, text: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 120.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        PillButton(button, prominent = true, onClick = onClick)
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(9.dp).clip(CircleShape).background(color))

/** A choice in a list, with a check mark when chosen (like iOS's inline Picker). */
@Composable
fun CheckRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (checked) Icon(androidx.compose.material.icons.Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

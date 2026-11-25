package io.github.akiig.dose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.akiig.dose.Change
import io.github.akiig.dose.Day
import io.github.akiig.dose.Describe
import io.github.akiig.dose.LocalApp
import io.github.akiig.dose.Medicine
import io.github.akiig.dose.Units
import io.github.akiig.dose.tr

@Composable
fun HistoryScreen(start: String = Day.today) {
    val store = LocalApp.current.store
    var month by rememberSaveable { mutableStateOf(start.take(7) + "-01") }
    var selected by rememberSaveable { mutableStateOf(start) }
    Column(Modifier.fillMaxSize()) {
        InlineTitle(tr("History"))
        Page {
            Section { Calendar(month, selected, { month = Day.date(month)!!.plusMonths(it.toLong()).toString() }) { selected = it } }
            Section {
                Text(Day.pretty(selected, "EEEE d MMMM yyyy"), Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
            DayList(selected)
            if (store.slots(selected).isEmpty() && store.extra(selected).isEmpty() && store.active.none { it.asNeeded }) {
                Section {
                    Text(tr("Nothing scheduled on this day."), Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
            }
            val changes = store.medicines.values.flatMap { m -> m.history.map { m to it } }
                .sortedWith(compareByDescending<Pair<Medicine, Change>> { it.second.date }.thenByDescending { it.first.name })
            if (changes.isNotEmpty()) Section(tr("Changes")) {
                changes.take(60).forEachIndexed { i, (m, c) ->
                    if (i > 0) Line()
                    TwoLines(kindText(c.kind) + " · " + m.name, changeDetail(c), Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Calendar(month: String, selected: String, onShift: (Int) -> Unit, onPick: (String) -> Unit) {
    val store = LocalApp.current.store
    val first = Day.date(month)!!
    val days = (0 until first.lengthOfMonth()).map { first.plusDays(it.toLong()).toString() }
    val lead = Day.isoWeekday(days[0]) - 1
    val today = Day.today
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Day.pretty(month, "LLLL yyyy"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton({ onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Previous month"), tint = MaterialTheme.colorScheme.primary) }
            val canNext = month.take(7) < today.take(7)
            IconButton({ onShift(1) }, enabled = canNext) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Next month"),
                    tint = if (canNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        }
        val cells: List<String?> = Day.weekdayShort.map { "w:$it" } + List(lead) { null } + days
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (cell in week + List(7 - week.size) { null }) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        when {
                            cell == null -> Spacer(Modifier.height(36.dp))
                            cell.startsWith("w:") -> Text(cell.drop(2), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> DayCell(cell, cell == selected, today) { onPick(cell) }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(Palette.taken, tr("All taken")); Legend(Palette.some, tr("Some")); Legend(Palette.none, tr("None marked"))
        }
    }
}

@Composable
private fun DayCell(d: String, isSelected: Boolean, today: String, onClick: () -> Unit) {
    val store = LocalApp.current.store
    val c = if (d <= today) store.counts(d) else null
    var fill: Color? = null
    if (c != null && c.due > 0 && (d < today || c.taken > 0)) {
        fill = if (c.taken == c.due) Palette.taken else if (c.taken > 0) Palette.some else Palette.none
    }
    val future = d > today
    val ink = when {
        fill == Palette.taken -> Color.White
        fill != null -> Color(0xff1b1b1b)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(Modifier.fillMaxWidth().height(36.dp).alpha(if (future) 0.35f else 1f).clip(RoundedCornerShape(9.dp))
        .background(fill ?: MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSystemInDarkTheme()) 0.08f else 0.05f))
        .then(if (isSelected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)) else Modifier)
        .clickable(enabled = !future, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(d.takeLast(2).toInt().toString(), style = MaterialTheme.typography.bodyMedium, color = ink,
            fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color); Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun kindText(kind: String) = when (kind) {
    "started" -> tr("Started")
    "stopped" -> tr("Stopped")
    "restarted" -> tr("Started again")
    else -> tr("Dose changed")
}

private fun changeDetail(c: Change): String {
    val parts = mutableListOf(Day.pretty(c.date, "d MMM yyyy"))
    val d = c.detail
    if (d != null && c.kind != "stopped") {
        parts.add(Units.amount(d.amount, d.unit))
        parts.add(Describe.schedule(d.schedule.cleaned()))
    }
    return parts.joinToString(" · ")
}

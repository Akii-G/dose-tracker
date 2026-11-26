package io.github.akiig.dose.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akiig.dose.AppStore
import io.github.akiig.dose.Day
import io.github.akiig.dose.DoseRecord
import io.github.akiig.dose.LocalApp
import io.github.akiig.dose.Medicine
import io.github.akiig.dose.Units
import io.github.akiig.dose.tr
import io.github.akiig.dose.trn
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
fun TodayScreen(onEdit: (Medicine?) -> Unit) {
    val store = LocalApp.current.store
    var minute by remember { mutableStateOf(Day.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); minute = Day.now() } }
    Column(Modifier.fillMaxSize()) {
        InlineTitle(tr("Today")) { AddButton { onEdit(null) } }
        if (store.medicines.isEmpty()) {
            Empty(Icons.Filled.Medication, tr("No medicines yet"),
                tr("Add the medicines you take, and Dose will remind you when it's time and remember what you took."),
                tr("Add a medicine")) { onEdit(null) }
        } else Page {
            Section {
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Day.pretty(Day.today), style = MaterialTheme.typography.headlineSmall)
                    Text(summary(store), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val low = store.active.filter { store.isLow(it) }
                if (low.isNotEmpty()) {
                    Line()
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Running low: %@", low.joinToString(", ") { m ->
                            "${m.name} (${trn("%@ day left", "%@ days left", store.daysLeft(m) ?: 0)})" }),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            DayList(Day.today, minute)
        }
    }
}

private fun summary(store: AppStore): String {
    val c = store.counts(Day.today)
    if (c.due == 0) return tr("Nothing scheduled today")
    var line = if (c.taken < c.due) tr("%@ of %@ taken", c.taken, c.due) else tr("All taken today")
    if (c.skipped > 0) line += " · " + trn("%@ skipped", "%@ skipped", c.skipped)
    return line
}

/** The doses of one day, by time, then the medicines taken when needed. */
@Composable
fun ColumnScope.DayList(day: String, minute: String = "") {
    val store = LocalApp.current.store
    val byTime = store.slots(day).groupBy { it.time }
    for (time in byTime.keys.sorted()) {
        HeaderSection(header = {
            Text(time, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Default),
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                fontSize = 20.sp)
        }) {
            byTime[time]!!.forEachIndexed { i, slot ->
                if (i > 0) Line()
                DoseRow(day, slot, minute)
            }
        }
    }
    val needed = store.active.filter { it.asNeeded }
    val extra = store.extra(day)
    val ids = (needed.map { it.id } + extra.map { it.med }).distinct()
    if (ids.isNotEmpty()) {
        Section(tr("When needed")) {
            ids.mapNotNull { store.medicines[it] }.forEachIndexed { i, med ->
                if (i > 0) Line()
                NeededRow(day, med, extra.filter { it.med == med.id })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DoseRow(day: String, slot: AppStore.Slot, minute: String) {
    val store = LocalApp.current.store
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val dose = slot.dose
    val detail = buildList {
        add(Units.amount(slot.med.amountAt(slot.time), slot.med.unit))
        if (slot.med.note.isNotEmpty()) add(slot.med.note)
        if (dose == null && (day < Day.today || (day == Day.today && minute > slot.time))) add(tr("Not taken"))
    }.joinToString(" · ")
    fun mark(status: String) {
        // on a past day "Taken" means at the time it was due; for another time, hold the row
        val at = if (status == "taken" && day != Day.today) slot.time else null
        store.mark(slot.med.id, day, slot.time, status, at)
    }
    Column {
        val status: @Composable () -> Unit = {
            val taken = dose?.status == "taken"
            Text(if (taken) tr("Taken at %@", dose!!.at) else tr("Skipped"), style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (taken) FontWeight.SemiBold else FontWeight.Normal,
                color = if (taken) Palette.taken else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AdaptiveRow(Modifier.combinedClickable(onClick = { if (dose != null) menu = true }, onLongClick = { menu = true }),
            main = { MedicineLine(slot.med.unit, slot.med.title, detail, it) }) {
            if (dose != null) status()
            else {
                PillButton(tr("Skip"), prominent = false, wide()) { mark("skipped") }
                PillButton(tr("Taken"), prominent = true, wide()) { mark("taken") }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            if (dose != null) {
                DropdownMenuItem(text = { Text(tr("Undo")) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) },
                    onClick = { menu = false; store.unmark(dose.id) })
            } else {
                DropdownMenuItem(text = { Text(tr("Taken at another time")) }, leadingIcon = { Icon(Icons.Filled.Schedule, null) },
                    onClick = { menu = false; picking = true })
            }
        }
    }
    if (picking) TimePickerDialog(LocalTime.parse(slot.time), onDismiss = { picking = false }) { t ->
        store.mark(slot.med.id, day, slot.time, "taken", t.toString().take(5))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NeededRow(day: String, med: Medicine, doses: List<DoseRecord>) {
    val store = LocalApp.current.store
    var menu by remember { mutableStateOf(false) }
    val detail = if (doses.isEmpty()) Units.amount(med.amount, med.unit) + " · " + tr("when needed")
                 else doses.joinToString(", ") { "${it.at} (${Units.amount(it.amount, med.unit)})" }
    Column {
        AdaptiveRow(Modifier.combinedClickable(onClick = {}, onLongClick = { if (doses.isNotEmpty()) menu = true }),
            main = { MedicineLine(med.unit, med.title, detail, it) }) {
            PillButton(if (day == Day.today) tr("Take now") else tr("Add"), prominent = false, wide()) {
                store.takeNow(med.id, day, if (day == Day.today) null else "12:00")
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(tr("Remove the last one")) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Undo, null) },
                onClick = { menu = false; doses.lastOrNull()?.let { store.unmark(it.id) } })
        }
    }
}

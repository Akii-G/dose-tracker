package io.github.akiig.dose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.akiig.dose.Day
import io.github.akiig.dose.Describe
import io.github.akiig.dose.Kinds
import io.github.akiig.dose.LocalApp
import io.github.akiig.dose.Medicine
import io.github.akiig.dose.MedicineList
import io.github.akiig.dose.Schedule
import io.github.akiig.dose.Stamp
import io.github.akiig.dose.Stock
import io.github.akiig.dose.Units
import io.github.akiig.dose.tr
import java.time.LocalTime
import kotlin.math.abs

/** Add or change a medicine: search the list, then the dose, the schedule and the stock. */
@Composable
fun EditorScreen(original: Medicine?, onClose: () -> Unit) {
    val app = LocalApp.current
    val store = app.store
    val context = LocalContext.current
    val m = original ?: Medicine(name = "")
    var query by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(m.name) }
    var strength by remember { mutableStateOf(m.strength) }
    var form by remember { mutableStateOf(m.form) }
    var ingredient by remember { mutableStateOf(m.ingredient) }
    var note by remember { mutableStateOf(m.note) }
    var amount by remember { mutableStateOf(m.amount) }
    var unit by remember { mutableStateOf(m.unit) }
    var kind by remember { mutableStateOf(m.schedule.kind) }
    val times = remember { mutableStateListOf(*m.schedule.times.ifEmpty { listOf("08:00") }.toTypedArray()) }
    // all seven ticked is "every day": start with none, so tapping Friday means only Friday
    var weekdays by remember { mutableStateOf(if (m.schedule.kind == "weekdays" && m.schedule.weekdays.size < 7) m.schedule.weekdays.toSet() else emptySet()) }
    var every by remember { mutableStateOf(maxOf(m.schedule.every, 2)) }
    var start by remember { mutableStateOf(m.schedule.start) }
    var hasEnd by remember { mutableStateOf(m.schedule.end.isNotEmpty()) }
    var end by remember { mutableStateOf(m.schedule.end.ifEmpty { Day.adding(6, Day.today) }) }
    var counted by remember { mutableStateOf(m.stock != null) }
    var count by remember { mutableStateOf(original?.let { store.remaining(it) } ?: 30.0) }
    var refillDays by remember { mutableStateOf(m.refillDays) }
    var askDelete by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<Int?>(null) }
    var pickDay by remember { mutableStateOf<String?>(null) }   // "start" or "end"
    val list = remember { MedicineList.load(context) }
    val results = remember(query) { MedicineList.search(list, query) }

    fun save() {
        val schedule = Schedule(kind, times.toList(), weekdays.sorted(), every, start,
            if (hasEnd) maxOf(end, start) else "").cleaned()
        val left = original?.let { store.remaining(it) }
        val stock = if (!counted) null
            else if (original?.stock != null && left != null && abs(left - count) < 0.001) original.stock   // unchanged
            else Stock(count, Stamp.now())
        store.put(m.copy(name = name.trim(), strength = strength.trim(), form = form.trim(), ingredient = ingredient,
            note = note.trim(), amount = amount, unit = unit, schedule = schedule, stock = stock, refillDays = refillDays))
        if (original == null) app.askNotifications()
        onClose()
    }

    EditorDialog(if (original == null) tr("Add a medicine") else original.name, canSave = name.isNotBlank() && (kind != "weekdays" || weekdays.isNotEmpty()),
        onCancel = onClose, onSave = ::save) {
        if (original == null) {
            Section(tr("Medicine"), footer = tr("Not in the list? Just type the name below.")) {
                FieldRow("", query, { query = it }, placeholder = tr("Search by name or active ingredient"),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                results.forEach { item ->
                    Line()
                    Column(Modifier.fillMaxWidth().clickable {
                        name = item.name; strength = item.strength; form = item.form; ingredient = item.ingredient
                        unit = MedicineList.unitFor(item.form); query = ""
                    }.padding(vertical = 8.dp)) {
                        Text(if (item.strength.isEmpty()) item.name else "${item.name} ${item.strength}", style = MaterialTheme.typography.bodyLarge)
                        Text(listOf(item.form, item.ingredient).filter { it.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Section {
            FieldRow("", name, { name = it }, placeholder = tr("Name"), keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            Line()
            FieldRow("", strength, { strength = it }, placeholder = tr("Strength, e.g. 50 mg"))
            Line()
            FieldRow("", form, { form = it }, placeholder = tr("Form, e.g. tablets"))
            Line()
            FieldRow("", note, { note = it }, placeholder = tr("Note, e.g. with food"), keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
        Section(tr("Each time")) {
            val step = if (unit == "drop" || unit == "ml") 1.0 else 0.25
            StepperRow(Units.amount(amount, unit), canMinus = amount > 0.25, canPlus = amount < 1000,
                onMinus = { amount = maxOf(0.25, amount - step) }, onPlus = { amount = minOf(1000.0, amount + step) })
            Line()
            MenuRow(tr("Unit"), Units.name(unit, true), Units.keys.map { it to Units.name(it, true) }) { unit = it }
        }
        Section(tr("When")) {
            MenuRow(tr("How often"), Kinds.all.first { it.first == kind }.second, Kinds.all) { kind = it }
            if (kind == "weekdays") {
                Line()
                Text(if (weekdays.isEmpty()) tr("Tap the days you take it.") else Describe.days(weekdays.sorted()),
                    Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (d in 1..7) {
                        val on = d in weekdays
                        Box(Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(50))
                            .background(if (on) Palette.accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                            .clickable { weekdays = if (on) weekdays - d else weekdays + d }, contentAlignment = Alignment.Center) {
                            Text(Day.weekdayShort[d - 1].take(2), style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            if (kind == "every") {
                Line()
                StepperRow(tr("Every %@ days", every), canMinus = every > 2, canPlus = every < 365,
                    onMinus = { every-- }, onPlus = { every++ })
            }
            if (kind != "needed") {
                times.forEachIndexed { i, t ->
                    Line()
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (times.size > 1) {
                            Icon(Icons.Filled.RemoveCircle, tr("Remove"), Modifier.size(24.dp).clip(RoundedCornerShape(50))
                                .clickable { times.removeAt(i) }, tint = MaterialTheme.colorScheme.error)
                            Box(Modifier.size(10.dp))
                        }
                        Text(tr("Time"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Chip(t) { pickTime = i }
                    }
                }
                Line()
                ActionRow(tr("Add a time")) { times.add("20:00") }
            }
            Line()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("From"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Chip(Day.pretty(start, "d MMM yyyy")) { pickDay = "start" }
            }
            Line()
            SwitchRow(tr("Until a date (a course, like an antibiotic)"), hasEnd) { hasEnd = it }
            if (hasEnd) {
                Line()
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Until"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Chip(Day.pretty(maxOf(end, start), "d MMM yyyy")) { pickDay = "end" }
                }
            }
        }
        Section(tr("Stock"), footer = tr("Each dose you take is subtracted. Change the number when you buy more.")) {
            SwitchRow(tr("Count what's left"), counted) { counted = it }
            if (counted) {
                Line()
                StepperRow(tr("Now: %@", number(count)), canMinus = count > 0, onMinus = { count = maxOf(0.0, count - 1) }, onPlus = { count += 1 })
                Line()
                StepperRow(tr("Tell me when it lasts fewer than %@ days", refillDays), canMinus = refillDays > 0, canPlus = refillDays < 90,
                    onMinus = { refillDays-- }, onPlus = { refillDays++ })
            }
        }
        if (original != null) {
            Section(footer = tr("Stopped medicines keep their history. Delete removes the medicine and every dose you marked.")) {
                ActionRow(if (original.archived) tr("Start taking again") else tr("Stopped taking it")) {
                    store.setArchived(original.id, !original.archived); onClose()
                }
                Line()
                ActionRow(tr("Delete"), destructive = true) { askDelete = true }
            }
        }
    }

    pickTime?.let { i ->
        TimePickerDialog(LocalTime.parse(times[i]), onDismiss = { pickTime = null }) { times[i] = it.toString().take(5) }
    }
    pickDay?.let { which ->
        DayPickerDialog(if (which == "start") start else end, allowFuture = true, onDismiss = { pickDay = null }) {
            if (which == "start") start = it else end = it
        }
    }
    if (askDelete && original != null) AlertDialog(
        onDismissRequest = { askDelete = false },
        text = { Text(tr("Delete %@ and every dose you marked for it? This can't be undone.", original.name)) },
        confirmButton = { TextButton({ askDelete = false; store.delete(original.id); onClose() }) {
            Text(tr("Delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ askDelete = false }) { Text(tr("Cancel")) } },
    )
}

/** iOS's grey date/time chip on the right of a row. */
@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        .clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 6.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A row with the choice on the right in violet; tapping opens a menu, like iOS's Picker. */
@Composable
private fun MenuRow(label: String, value: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                Icon(Icons.Filled.UnfoldMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(key) }) }
            }
        }
    }
}

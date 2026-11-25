package io.github.akiig.dose.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.akiig.dose.Describe
import io.github.akiig.dose.LocalApp
import io.github.akiig.dose.Medicine
import io.github.akiig.dose.Units
import io.github.akiig.dose.tr
import io.github.akiig.dose.trn

@Composable
fun MedicinesScreen(onEdit: (Medicine?) -> Unit) {
    val store = LocalApp.current.store
    Column(Modifier.fillMaxSize()) {
        LargeTitle(tr("Medicines")) { AddButton { onEdit(null) } }
        Page {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Section {
                    if (store.active.isEmpty()) {
                        Text(tr("No medicines yet."), Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge)
                    }
                    store.active.forEachIndexed { i, m -> if (i > 0) Line(); MedicineRow(m) { onEdit(m) } }
                }
                if (store.stopped.isEmpty()) Footer()
            }
            if (store.stopped.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Section(tr("No longer taken")) {
                    store.stopped.forEachIndexed { i, m -> if (i > 0) Line(); MedicineRow(m) { onEdit(m) } }
                }
                Footer()
            }
        }
    }
}

@Composable
private fun Footer() {
    Text(tr("Medicine names: AIFA open data (CC BY 4.0). Dose doesn't give medical advice: follow what your doctor prescribed."),
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MedicineRow(m: Medicine, onClick: () -> Unit) {
    val store = LocalApp.current.store
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TwoLines(m.title, Units.amount(m.amount, m.unit) + " · " + Describe.schedule(m.schedule), Modifier.weight(1f))
        val left = store.remaining(m)
        if (left != null) {
            val color = if (store.isLow(m)) Palette.warning else MaterialTheme.colorScheme.onSurfaceVariant
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text(tr("%@ left", number(left)), style = MaterialTheme.typography.bodySmall, color = color)
                store.daysLeft(m)?.let { Text(trn("%@ day", "%@ days", it), style = MaterialTheme.typography.bodySmall, color = color) }
            }
        }
    }
}

/** 30 -> "30", 12.5 -> "12.5" (like %g). */
fun number(x: Double): String = if (x == Math.floor(x)) x.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, x).trimEnd('0').trimEnd('.')

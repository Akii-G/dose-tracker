package io.github.akiig.dose.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import io.github.akiig.dose.LocalApp
import io.github.akiig.dose.Medicine
import io.github.akiig.dose.tr

private data class Tab(val title: String, val icon: ImageVector)

/** What the editor is showing: a new medicine (null original) or one to change. */
class EditTarget(val original: Medicine?)

@Composable
fun DoseApp(startTab: Int = 0) {
    val app = LocalApp.current
    var tab by rememberSaveable { mutableIntStateOf(startTab) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var reporting by remember { mutableStateOf(false) }

    // Android 13+: ask for notifications the first time a medicine is added
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { app.replan() }
    app.askNotifications = { if (Build.VERSION.SDK_INT >= 33) ask.launch(Manifest.permission.POST_NOTIFICATIONS) }

    LaunchedEffect(app.openToday) {
        if (app.openToday) { app.openToday = false; editing = null; tab = 0 }
    }
    BackHandler(enabled = tab != 0) { tab = 0 }

    val tabs = listOf(
        Tab(tr("Today"), Icons.AutoMirrored.Filled.FactCheck),
        Tab(tr("Medicines"), Icons.Filled.Medication),
        Tab(tr("History"), Icons.Filled.CalendarMonth),
        Tab(tr("Settings"), Icons.Filled.Settings),
    )
    val edit: (Medicine?) -> Unit = { editing = EditTarget(it) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> TodayScreen(onEdit = edit)
                1 -> MedicinesScreen(onEdit = edit)
                2 -> HistoryScreen(onReport = { reporting = true })
                else -> SettingsScreen(onReport = { reporting = true })
            }
            TabBar(tabs, tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
            editing?.let { EditorScreen(it.original) { editing = null } }
            if (reporting) ReportScreen { reporting = false }
        }
    }
}

/** The floating, rounded tab bar of the iPhone app. */
@Composable
private fun TabBar(tabs: List<Tab>, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    Box(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(32.dp), ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            tabs.forEachIndexed { index, t ->
                val on = index == selected
                val ink by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, label = "ink")
                val pill by animateColorAsState(if (on) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent, label = "pill")
                Column(Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(pill)
                    .clickable { onSelect(index) }
                    .semantics { this.selected = on; role = Role.Tab }
                    .padding(vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(t.icon, null, Modifier.size(24.dp * minOf(LocalDensity.current.fontScale, 1.3f).coerceAtLeast(1f)), tint = ink)
                    FittingText(t.title, ink, if (on) FontWeight.SemiBold else FontWeight.Medium, capped(11.sp, 1.3f))
                }
            }
        }
    }
}

/** One line of text that gets smaller instead of being cut ("Impostazioni" with large text). */
@Composable
fun FittingText(text: String, color: Color, weight: FontWeight, start: TextUnit = 11.sp) {
    var size by remember(text) { mutableStateOf(start) }
    Text(text, color = color, fontSize = size, fontWeight = weight, maxLines = 1, softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow && size.value > 7f) size = (size.value - 0.5f).sp })
}

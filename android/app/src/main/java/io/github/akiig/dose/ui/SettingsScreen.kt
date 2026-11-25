package io.github.akiig.dose.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.akiig.dose.trn
import io.github.akiig.dose.Day
import io.github.akiig.dose.Backup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.akiig.dose.LocalApp
import io.github.akiig.dose.Reminders
import io.github.akiig.dose.SyncException
import io.github.akiig.dose.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onReport: () -> Unit = {}) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(app.prefs.server) }
    var token by remember { mutableStateOf(app.prefs.token) }
    var testing by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf(Reminders.canNotify(context)) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {   // back from Android's settings: look again
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { allowed = Reminders.canNotify(context) }
    }
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }
    var dataNote by remember { mutableStateOf("") }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) dataNote = try {
            context.contentResolver.openOutputStream(uri)?.use { Backup.write(app.store, it, version) }
            tr("Backed up. Keep the file somewhere safe, like your cloud or a computer.")
        } catch (e: Exception) { tr("Could not save: %@", e.localizedMessage ?: "") }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (e: Exception) { null }
            val n = text?.let { Backup.restore(app.store, it) }
            dataNote = if (n == null) tr("This isn't a Dose backup.")
                else trn("Restored: %@ change.", "Restored: %@ changes.", n) + " " + tr("What you had here stays; the backup only adds what's missing or newer.")
        }
    }

    Column(Modifier.fillMaxSize()) {
        LargeTitle(tr("Settings"))
        Page {
            Section(tr("Sync with your PC"),
                footer = tr("On the PC: Dose → ☰ → Phone sync. Switch it on, then copy the address and the token here. Everything is encrypted with the token and stays on your home Wi-Fi.")) {
                FieldRow("", server, { server = it.trim(); app.prefs.server = server }, placeholder = tr("Address, e.g. pop-os.local:8766"),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                Line()
                FieldRow("", token, { token = it.trim(); app.prefs.token = token }, placeholder = tr("Token"),
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                Line()
                SwitchRow(tr("Sync automatically"), app.autoSync) { app.chooseAutoSync(it) }
                Line()
                ActionRow(tr("Test the connection"), enabled = app.prefs.isConfigured) {
                    testing = tr("Connecting…")
                    scope.launch {
                        testing = try { withContext(Dispatchers.IO) { app.client.ping() } }
                                  catch (e: SyncException) { e.message ?: "" }
                                  catch (e: Exception) { e.localizedMessage ?: e.javaClass.simpleName }
                    }
                }
                Line()
                ActionRow(tr("Sync now"), enabled = app.prefs.isConfigured, busy = app.sync.busy) {
                    scope.launch { app.sync.run(app.store) }
                }
                if (testing.isNotEmpty()) Note(testing)
                Note(app.sync.status)
            }
            Section(tr("Reminders"),
                footer = tr("Discreet notifications say only \"Time for your 08:00 dose\", without the medicine's name.")) {
                if (!allowed) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        ActionRow(tr("Allow reminders")) { app.askNotifications() }
                        Line()
                    }
                    Text(tr("Notifications are off for Dose. Turn them on in Android's Settings → Apps → Dose → Notifications."),
                        Modifier.padding(vertical = 10.dp), color = Palette.warning, style = MaterialTheme.typography.bodyMedium)
                    Line()
                    ActionRow(tr("Open Android's settings")) {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    Line()
                }
                SwitchRow(tr("Remind me when a dose is due"), app.remind) { app.chooseRemind(it) }
                Line()
                SwitchRow(tr("Remind me again after 30 minutes"), app.followUp) { app.chooseFollowUp(it) }
                Line()
                SwitchRow(tr("Discreet notifications"), app.discreet) { app.chooseDiscreet(it) }
            }
            Section(tr("Your data"), footer = dataNote.ifEmpty {
                tr("A backup is one file with all your medicines and doses. Restoring it on a new phone brings them back.") }) {
                ActionRow(tr("Report for your doctor")) { onReport() }
                Line()
                ActionRow(tr("Back up")) { backup.launch("Dose-backup-${Day.today}.json") }
                Line()
                ActionRow(tr("Restore from a backup")) { restore.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }
            }
            Section(tr("About"),
                footer = tr("Your medicines, quietly. The sister app of Sottovoce. Medicine names: AIFA open data (CC BY 4.0). Dose doesn't give medical advice: follow what your doctor prescribed.")) {
                ValueRow(tr("Version"), version)
                Line()
                LinkRow(tr("Dose on GitHub")) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Akii-G/dose-tracker"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

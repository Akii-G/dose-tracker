package io.github.akiig.dose.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.akiig.dose.AppStore
import io.github.akiig.dose.Day
import io.github.akiig.dose.LocalApp
import io.github.akiig.dose.Report
import io.github.akiig.dose.tr
import java.io.File

/** A period, then the report as a PDF or a CSV, shared with the app you choose (email, Drive, print…). */
@Composable
fun ReportScreen(onClose: () -> Unit) {
    val store = LocalApp.current.store
    val context = LocalContext.current
    val periods = listOf(7 to tr("Last 7 days"), 14 to tr("Last 2 weeks"), 30 to tr("Last 30 days"), 90 to tr("Last 3 months"), 0 to tr("Choose the dates"))
    var choice by remember { mutableIntStateOf(2) }
    var from by remember { mutableStateOf(Day.adding(-29, Day.today)) }
    var until by remember { mutableStateOf(Day.today) }
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }

    fun dates(): Pair<String, String> {
        val n = periods[choice].first
        return if (n > 0) Day.adding(-(n - 1), Day.today) to Day.today else minOf(from, until) to maxOf(from, until)
    }

    EditorDialog(tr("Report for your doctor"), canSave = false, onCancel = onClose, onSave = {}, showSave = false) {
        Text(tr("For each medicine: the doses taken, skipped and not taken, those taken when needed and the changes; then the doses day by day."),
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Section(tr("Period")) {
            periods.forEachIndexed { i, (_, label) ->
                if (i > 0) Line()
                CheckRow(label, choice == i) { choice = i }
            }
            if (periods[choice].first == 0) {
                Line()
                ValueRow(tr("From"), Day.pretty(from, "d MMM yyyy")) { picking = "from" }
                Line()
                ValueRow(tr("Until"), Day.pretty(until, "d MMM yyyy")) { picking = "until" }
            }
        }
        Section(footer = error.ifEmpty { tr("Your Android asks which app gets it: email, Drive, a printer…") }) {
            ActionRow(tr("Share as PDF")) { error = share(context, store, dates(), "pdf") }
            Line()
            ActionRow(tr("Share as CSV (spreadsheet)")) { error = share(context, store, dates(), "csv") }
        }
    }
    picking?.let { which ->
        DayPickerDialog(if (which == "from") from else until, allowFuture = false, onDismiss = { picking = null }) {
            if (which == "from") from = it else until = it
        }
    }
}

private fun share(context: Context, store: AppStore, dates: Pair<String, String>, kind: String): String {
    val (start, end) = dates
    return try {
        val dir = File(context.cacheDir, "reports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "${tr("Dose-report")}-$start-$end.$kind")
        file.outputStream().use { if (kind == "pdf") Report.pdf(store, start, end, it) else Report.csv(store, start, end, it) }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(if (kind == "pdf") "application/pdf" else "text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, tr("Medicines report"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, tr("Medicines report")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ""
    } catch (e: Exception) {
        tr("Could not make the report: %@", e.localizedMessage ?: e.javaClass.simpleName)
    }
}

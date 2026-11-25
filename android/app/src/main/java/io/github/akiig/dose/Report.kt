package io.github.akiig.dose

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The report for the doctor, the same as the PC's (src/dose, report_pdf): for a period, each
 * medicine's doses taken, skipped and not taken, those taken when needed, the changes, then the
 * doses day by day. As a PDF, and as a CSV for a spreadsheet.
 */
object Report {
    data class Count(val med: Medicine, var due: Int = 0, var taken: Int = 0, var skipped: Int = 0, var missed: Int = 0)
    data class Row(val time: String, val med: Medicine, val status: String, val at: String, val amount: Double)  // status: taken, skipped, missed, needed
    data class Data(val meds: List<Count>, val needed: List<Pair<Medicine, Int>>, val days: List<Pair<String, List<Row>>>,
                    val changes: List<Triple<String, Medicine, Change>>)

    /** The day a stopped medicine was stopped, so it still counts on the days before. */
    private fun wasDue(m: Medicine, day: String): Boolean {
        val stop = m.history.filter { it.kind == "stopped" }.maxOfOrNull { it.date }
        val med = if (m.archived && stop != null && day < stop) m.copy(archived = false) else m
        return med.dueOn(day)
    }

    fun data(store: AppStore, start: String, end: String, now: LocalDateTime = LocalDateTime.now()): Data {
        val counts = LinkedHashMap<String, Count>()
        val needed = LinkedHashMap<String, Int>()
        val days = ArrayList<Pair<String, List<Row>>>()
        var day = start
        while (day <= end) {
            val rows = ArrayList<Row>()
            for (m in store.medicines.values) {
                if (!wasDue(m, day)) continue
                for (t in m.schedule.times) {
                    val dose = store.doses[AppStore.slotId(m.id, day, t)]
                    if (dose == null && Day.date(day)!!.atTime(LocalTime.parse(t)).isAfter(now)) continue   // still to come
                    val status = dose?.status ?: "missed"
                    val c = counts.getOrPut(m.id) { Count(m) }
                    c.due++
                    when (status) { "taken" -> c.taken++; "skipped" -> c.skipped++; else -> c.missed++ }
                    rows.add(Row(t, m, status, dose?.at ?: "", dose?.amount ?: m.amountAt(t)))
                }
            }
            for (d in store.extra(day)) {
                val m = store.medicines[d.med] ?: continue
                needed[m.id] = (needed[m.id] ?: 0) + 1
                rows.add(Row(d.at, m, "needed", d.at, d.amount))
            }
            rows.sortWith(compareBy({ it.time }, { it.med.name.lowercase() }))
            if (rows.isNotEmpty()) days.add(day to rows)
            day = Day.adding(1, day)
        }
        val changes = store.medicines.values.flatMap { m -> m.history.filter { it.date in start..end }.map { Triple(it.date, m, it) } }
            .sortedWith(compareBy({ it.first }, { it.second.name.lowercase() }))
        return Data(counts.values.sortedBy { it.med.name.lowercase() },
            needed.map { (id, n) -> store.medicines[id]!! to n }.sortedBy { it.first.name.lowercase() }, days, changes)
    }

    private fun kindWord(kind: String) = when (kind) {
        "started" -> tr("Started"); "stopped" -> tr("Stopped"); "restarted" -> tr("Started again"); else -> tr("Dose changed")
    }

    fun changeText(c: Change): String {
        val d = c.detail
        return if (d == null || c.kind == "stopped") kindWord(c.kind) else kindWord(c.kind) + " · " + Describe.line(d.amount, d.unit, d.schedule.cleaned())
    }

    private fun word(r: Row) = when (r.status) {
        "taken" -> tr("Taken at %@", r.at); "skipped" -> tr("Skipped"); "missed" -> tr("Not taken"); else -> tr("When needed, at %@", r.at)
    }

    /** Every dose of the period, one per line. ";" between columns in Italian, as Excel expects there. */
    fun csv(store: AppStore, start: String, end: String, out: OutputStream) {
        val sep = if (appLocale().language == "it") ";" else ","
        fun cell(s: String) = if (s.contains(sep) || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val words = mapOf("taken" to tr("Taken"), "skipped" to tr("Skipped"), "missed" to tr("Not taken"), "needed" to tr("Taken when needed"))
        val sb = StringBuilder("﻿")       // lets Excel read the accents
        sb.append(listOf(tr("Date"), tr("Time"), tr("Medicine"), tr("Strength"), tr("Amount"), tr("Status"), tr("Taken at")).joinToString(sep) { cell(it) }).append("\r\n")
        for ((day, rows) in data(store, start, end).days) for (r in rows) {
            sb.append(listOf(day, if (r.status == "needed") "" else r.time, r.med.name, r.med.strength, Units.amount(r.amount, r.med.unit),
                words.getValue(r.status), if (r.status == "taken" || r.status == "needed") r.at else "").joinToString(sep) { cell(it) }).append("\r\n")
        }
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** A4 pages, drawn like the PC's. */
    fun pdf(store: AppStore, start: String, end: String, out: OutputStream) {
        val data = data(store, start, end)
        val W = 595; val H = 842; val M = 48f
        val violet = Color.rgb(92, 86, 180); val ink = Color.rgb(26, 26, 31); val grey = Color.rgb(115, 115, 128); val red = Color.rgb(204, 64, 64)
        val doc = PdfDocument()
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        var y = M
        val period = Day.pretty(start, "d MMM yyyy") + " – " + Day.pretty(end, "d MMM yyyy")

        fun text(x: Float, top: Float, s: String, size: Float = 10f, bold: Boolean = false, color: Int = ink, width: Float = W - M - x,
                 align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): Float {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
            val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt().coerceAtLeast(10)).setAlignment(align).build()
            page.canvas.save(); page.canvas.translate(x, top); layout.draw(page.canvas); page.canvas.restore()
            return layout.height.toFloat()
        }
        fun newPage() {
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }
        fun footer() = text(M, H - 34f, tr("Dose · %@ · page %@", period, pageNo), 8f, color = grey, width = W - 2 * M, align = Layout.Alignment.ALIGN_CENTER)
        fun need(h: Float) { if (y + h > H - 54) { footer(); doc.finishPage(page); newPage() } }
        fun rule(at: Float) {
            page.canvas.drawLine(M, at, W - M, at, Paint().apply { color = Color.rgb(220, 220, 230); strokeWidth = 0.7f })
        }

        newPage()
        y += text(M, y, tr("Medicines report"), 22f, true, violet) + 4
        y += text(M, y, period, 12f, color = grey) + 2
        y += text(M, y, tr("Made with Dose on %@. Doses not yet due are not counted.", Day.pretty(Day.today, "d MMM yyyy")), 8f, color = grey) + 18

        val cols = listOf(M to tr("Medicine"), M + 250 to tr("Taken"), M + 305 to tr("Skipped"), M + 365 to tr("Not taken"), M + 430 to tr("% taken"))
        y += text(M, y, tr("Scheduled doses"), 13f, true) + 6
        if (data.meds.isEmpty()) y += text(M, y, tr("No scheduled doses in this period."), 10f, color = grey) + 10
        else {
            for ((x, label) in cols) text(x, y, label, 8f, true, grey, width = if (x > M) 60f else 200f)
            y += 16; rule(y); y += 6
            for (c in data.meds) {
                need(40f)
                val name = c.med.title + if (c.med.archived) " (" + tr("stopped") + ")" else ""
                var h = text(M, y, name, 10f, true, width = 240f)
                h += text(M, y + h, Describe.line(c.med.amount, c.med.unit, c.med.schedule), 8f, color = grey, width = 240f)
                val pct = if (c.due > 0) Math.round(100.0 * c.taken / c.due) else 0
                val values = listOf(c.taken.toString(), c.skipped.toString(), c.missed.toString(), "$pct%")
                val colors = listOf(ink, ink, if (c.missed > 0) red else ink, violet)
                for (i in 0 until 4) text(cols[i + 1].first, y, values[i], 10f, i == 3, colors[i], width = 60f)
                y += maxOf(h, 14f) + 6; rule(y - 3)
            }
            y += 10
        }
        if (data.needed.isNotEmpty()) {
            need(40f)
            y += text(M, y, tr("Taken when needed"), 13f, true) + 6
            for ((m, n) in data.needed) { need(18f); y += text(M, y, m.title + " · " + trn("%@ time", "%@ times", n), 10f) + 4 }
            y += 10
        }
        if (data.changes.isNotEmpty()) {
            need(40f)
            y += text(M, y, tr("Changes"), 13f, true) + 6
            for ((day, m, c) in data.changes) {
                need(18f)
                text(M, y, Day.pretty(day, "d MMM yyyy"), 9f, color = grey, width = 85f)
                y += text(M + 90, y, m.title + " · " + changeText(c), 9f) + 4
            }
            y += 10
        }
        need(40f)
        y += text(M, y, tr("Day by day"), 13f, true) + 6
        for ((day, rows) in data.days.reversed()) {
            need(22f + 14 * minOf(rows.size, 3))
            y += text(M, y, Day.pretty(day, "EEEE d MMMM yyyy").replaceFirstChar { it.uppercase() }, 10f, true, violet) + 3
            for (r in rows) {
                need(14f)
                if (r.status != "needed") text(M + 10, y, r.time, 9f, color = grey, width = 40f)
                text(M + 55, y, r.med.title + " · " + Units.amount(r.amount, r.med.unit), 9f, width = 280f)
                y += text(M + 345, y, word(r), 9f, r.status == "taken",
                    when (r.status) { "missed" -> red; "skipped" -> grey; else -> violet }) + 3
            }
            y += 6
        }
        footer()
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }
}

/** A Dose backup: everything, the same as dose.json, marked as a backup. */
object Backup {
    fun write(store: AppStore, out: OutputStream, version: String) {
        val doc = store.toJson().put("backup", org.json.JSONObject().put("app", "Dose").put("version", version).put("made", Stamp.now()))
        out.write(doc.toString(1).toByteArray(Charsets.UTF_8))
    }

    /** Merges a backup into what's here (the newer change wins); null if it isn't a Dose backup. */
    fun restore(store: AppStore, text: String): Int? {
        val doc = try { org.json.JSONObject(text) } catch (e: Exception) { return null }
        if (doc.optJSONArray("medicines") == null || doc.optJSONArray("doses") == null) return null
        val n = store.absorb(doc)
        store.onChange?.invoke()
        return n
    }
}

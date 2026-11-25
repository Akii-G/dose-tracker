package io.github.akiig.dose

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

// The same records as Dose on the PC (src/dose) and on the iPhone (ios/Dose/Models.swift):
// docs/FORMAT.md describes them. Every reader tolerates missing fields.

private fun JSONObject.str(key: String, fallback: String = "") = if (has(key) && !isNull(key)) optString(key, fallback) else fallback

data class Schedule(
    val kind: String = "daily",                 // daily, weekdays, every, needed
    val times: List<String> = listOf("08:00"),  // "HH:MM", sorted
    val weekdays: List<Int> = (1..7).toList(),  // 1 = Monday … 7 = Sunday
    val every: Int = 2,                         // for "every": every N days from start
    val start: String = Day.today,
    val end: String = "",                       // "" or the last day of a course
) {
    /** Checked like the PC's clean_schedule. */
    fun cleaned(): Schedule {
        val k = if (kind in listOf("daily", "weekdays", "every", "needed")) kind else "daily"
        var t = times.filter { Day.isTime(it) }.distinct().sorted()
        if (k != "needed" && t.isEmpty()) t = listOf("08:00")
        if (k == "needed") t = emptyList()
        val w = weekdays.filter { it in 1..7 }.distinct().sorted().ifEmpty { (1..7).toList() }
        return Schedule(k, t, w, every.coerceIn(1, 365), if (Day.date(start) != null) start else Day.today,
            if (end.isNotEmpty() && Day.date(end) == null) "" else end)
    }

    fun json(): JSONObject = JSONObject().put("kind", kind).put("times", JSONArray(times)).put("weekdays", JSONArray(weekdays))
        .put("every", every).put("start", start).put("end", end)

    companion object {
        fun from(o: JSONObject?): Schedule {
            o ?: return Schedule()
            fun ints(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optInt(i) } }
            fun strs(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }
            return Schedule(o.str("kind", "daily"), strs(o.optJSONArray("times")) ?: emptyList(),
                ints(o.optJSONArray("weekdays")) ?: (1..7).toList(), o.optInt("every", 2),
                o.str("start", Day.today), o.str("end")).cleaned()
        }
    }
}

data class Stock(val count: Double, val set: String)

/** What a medicine was at a change: the dose and the schedule. */
data class Snapshot(val amount: Double, val unit: String, val strength: String, val schedule: Schedule) {
    fun json(): JSONObject = JSONObject().put("amount", amount).put("unit", unit).put("strength", strength).put("schedule", schedule.json())

    companion object {
        fun of(m: Medicine) = Snapshot(m.amount, m.unit, m.strength, m.schedule)
        fun from(o: JSONObject?): Snapshot? = o?.let {
            Snapshot(it.optDouble("amount", 1.0), it.str("unit", "pill"), it.str("strength"), Schedule.from(it.optJSONObject("schedule")))
        }
    }
}

data class Change(val date: String, val kind: String, val detail: Snapshot?) {
    fun json(): JSONObject = JSONObject().put("date", date).put("kind", kind).also { o -> detail?.let { o.put("detail", it.json()) } }

    companion object {
        fun from(o: JSONObject) = Change(o.str("date"), o.str("kind"), Snapshot.from(o.optJSONObject("detail")))
    }
}

data class Medicine(
    val id: String = newId(),
    val name: String,
    val strength: String = "",
    val form: String = "",
    val ingredient: String = "",
    val note: String = "",
    val amount: Double = 1.0,
    val unit: String = "pill",
    val schedule: Schedule = Schedule(),
    val stock: Stock? = null,
    val refillDays: Int = 7,
    val archived: Boolean = false,
    val history: List<Change> = emptyList(),
    val updated: String = Stamp.now(),
) {
    val title get() = if (strength.isEmpty()) name else "$name $strength"
    val asNeeded get() = schedule.kind == "needed"

    /** Average amount a day, for how long the stock lasts. */
    val perDay: Double get() {
        val n = schedule.times.size * amount
        return when (schedule.kind) {
            "weekdays" -> n * schedule.weekdays.size / 7
            "every" -> n / schedule.every.coerceAtLeast(1)
            "needed" -> 0.0
            else -> n
        }
    }

    /** Whether it has doses on `day` (as-needed ones never do). */
    fun dueOn(day: String): Boolean {
        if (archived || schedule.kind == "needed" || day < schedule.start) return false
        if (schedule.end.isNotEmpty() && day > schedule.end) return false
        return when (schedule.kind) {
            "weekdays" -> Day.isoWeekday(day) in schedule.weekdays
            "every" -> Day.between(schedule.start, day) % schedule.every.coerceAtLeast(1) == 0L
            else -> true
        }
    }

    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("strength", strength).put("form", form)
        .put("ingredient", ingredient).put("note", note).put("amount", amount).put("unit", unit).put("schedule", schedule.json())
        .put("stock", stock?.let { JSONObject().put("count", it.count).put("set", it.set) } ?: JSONObject.NULL)
        .put("refillDays", refillDays).put("archived", archived).put("history", JSONArray(history.map { it.json() }))
        .put("updated", updated)

    companion object {
        fun from(o: JSONObject): Medicine? {
            val id = o.str("id"); val name = o.str("name")
            if (id.isEmpty() || name.isEmpty()) return null
            val s = o.optJSONObject("stock")
            val h = o.optJSONArray("history")
            return Medicine(id, name, o.str("strength"), o.str("form"), o.str("ingredient"), o.str("note"),
                o.optDouble("amount", 1.0).let { if (it.isNaN()) 1.0 else it.coerceIn(0.25, 1000.0) },
                o.str("unit", "pill").let { if (it in Units.keys) it else "pill" },
                Schedule.from(o.optJSONObject("schedule")),
                s?.let { Stock(it.optDouble("count", 0.0), it.str("set")) },
                o.optInt("refillDays", 7), o.optBoolean("archived", false),
                h?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.let(Change::from) } } ?: emptyList(),
                o.str("updated", Stamp.now()))
        }
    }
}

/** A dose marked taken or skipped. Scheduled ones have the id "med@day@time"; one taken when
 *  needed has its own id and an empty time. */
data class DoseRecord(
    val id: String, val med: String, val day: String, val time: String, val status: String,
    val at: String, val amount: Double, val stamp: String, val updated: String,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("med", med).put("day", day).put("time", time)
        .put("status", status).put("at", at).put("amount", amount).put("stamp", stamp).put("updated", updated)

    companion object {
        fun from(o: JSONObject): DoseRecord? {
            val d = DoseRecord(o.str("id"), o.str("med"), o.str("day"), o.str("time"), o.str("status"), o.str("at"),
                o.optDouble("amount", 1.0), o.str("stamp", Stamp.now()), o.str("updated", Stamp.now()))
            return if (d.id.isEmpty() || d.med.isEmpty() || Day.date(d.day) == null || d.status !in setOf("taken", "skipped")) null else d
        }
    }
}

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

object Stamp {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    fun now(): String = format.format(Instant.now().truncatedTo(ChronoUnit.MILLIS))
}

/** Days as "YYYY-MM-DD" in the phone's time zone, like the PC. */
object Day {
    val today: String get() = LocalDate.now().toString()
    fun date(day: String): LocalDate? = try { LocalDate.parse(day) } catch (e: Exception) { null }
    fun now(): String = LocalTime.now().toString().take(5)
    fun isTime(s: String) = s.length == 5 && s[2] == ':' && s.take(2).toIntOrNull()?.let { it in 0..23 } == true &&
        s.takeLast(2).toIntOrNull()?.let { it in 0..59 } == true
    fun adding(days: Int, day: String): String = date(day)?.plusDays(days.toLong())?.toString() ?: day
    fun between(a: String, b: String): Long {
        val x = date(a) ?: return 0; val y = date(b) ?: return 0
        return ChronoUnit.DAYS.between(x, y)
    }
    fun isoWeekday(day: String): Int = date(day)?.dayOfWeek?.value ?: 1

    private fun cap(s: String) = s.replaceFirstChar { it.titlecase(appLocale()) }

    /** "Tuesday 25 November", in the app's language. */
    fun pretty(day: String, pattern: String = "EEEE d MMMM"): String {
        val d = date(day) ?: return day
        return cap(DateTimeFormatter.ofPattern(pattern, appLocale()).format(d))
    }

    /** Mon … Sun, Monday first. */
    val weekdayShort: List<String> get() = listOf(tr("Mon"), tr("Tue"), tr("Wed"), tr("Thu"), tr("Fri"), tr("Sat"), tr("Sun"))
}

object Units {
    val keys = listOf("pill", "capsule", "drop", "ml", "puff", "sachet", "patch", "injection", "application")

    fun name(unit: String, plural: Boolean): String = when (unit) {
        "capsule" -> if (plural) tr("capsules") else tr("capsule")
        "drop" -> if (plural) tr("drops") else tr("drop")
        "ml" -> tr("ml")
        "puff" -> if (plural) tr("puffs") else tr("puff")
        "sachet" -> if (plural) tr("sachets") else tr("sachet")
        "patch" -> if (plural) tr("patches") else tr("patch")
        "injection" -> if (plural) tr("injections") else tr("injection")
        "application" -> if (plural) tr("applications") else tr("application")
        else -> if (plural) tr("pills") else tr("pill")
    }

    /** 1 -> "1 pill", 0.5 -> "½ pill", 1.5 -> "1½ pills", 2.5 ml -> "2.5 ml". */
    fun amount(amount: Double, unit: String): String {
        val whole = amount.toInt()
        val frac = Math.round((amount - whole) * 100).toInt()
        val glyph = when (frac) { 25 -> "¼"; 50 -> "½"; 75 -> "¾"; else -> null }
        val number = when {
            frac == 0 -> "$whole"
            unit != "ml" && glyph != null -> (if (whole > 0) "$whole" else "") + glyph
            else -> {
                val s = if (amount == Math.floor(amount)) "${amount.toInt()}" else "$amount".trimEnd('0')
                if (appLocale().language == "it") s.replace('.', ',') else s
            }
        }
        return "$number ${name(unit, amount > 1)}"
    }
}

object Kinds {
    val all get() = listOf("daily" to tr("Every day"), "weekdays" to tr("Some days of the week"),
        "every" to tr("Every few days"), "needed" to tr("Only when needed"))
}

object Describe {
    /** "Fri", "Mon, Thu". */
    fun days(days: List<Int>): String = days.joinToString(", ") { Day.weekdayShort[it - 1] }

    /** "Every day at 08:00 and 20:00", "Mon, Thu at 09:00", "Every 3 days at 08:00", "When needed". */
    fun schedule(s: Schedule): String {
        var out = if (s.kind == "needed") tr("When needed") else {
            val at = if (s.times.size == 1) s.times[0] else tr("%@ and %@", s.times.dropLast(1).joinToString(", "), s.times.last())
            when {
                s.kind == "weekdays" && s.weekdays.size < 7 -> tr("%@ at %@", s.weekdays.joinToString(", ") { Day.weekdayShort[it - 1] }, at)
                s.kind == "every" && s.every > 1 -> tr("Every %@ days at %@", s.every, at)
                else -> tr("Every day at %@", at)
            }
        }
        if (s.end.isNotEmpty()) out += " · " + tr("until %@", Day.pretty(s.end, "d MMM yyyy"))
        return out
    }
}

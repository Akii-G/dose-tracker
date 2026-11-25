package io.github.akiig.dose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything Dose keeps on the phone: one JSON file, the same as dose.json on the PC. */
class AppStore(private val file: File?) {
    var medicines by mutableStateOf(mapOf<String, Medicine>())
        private set
    var doses by mutableStateOf(mapOf<String, DoseRecord>())
        private set
    var deleted by mutableStateOf(mapOf<String, String>())
        private set

    /** Called after every change: sync soon and plan the reminders again. */
    var onChange: (() -> Unit)? = null

    init { load() }

    fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try { read(JSONObject(f.readText())) } catch (e: Exception) {
            f.copyTo(File(f.path + ".unreadable"), overwrite = true)   // kept, not overwritten with nothing
        }
    }

    private fun read(o: JSONObject) {
        fun objs(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
        medicines = objs("medicines").mapNotNull(Medicine::from).associateBy { it.id }
        doses = objs("doses").mapNotNull(DoseRecord::from).associateBy { it.id }
        deleted = objs("deleted").filter { it.optString("id").isNotEmpty() }.associate { it.optString("id") to it.optString("stamp") }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("medicines", JSONArray(medicines.values.sortedBy { it.name.lowercase() }.map { it.json() }))
        .put("doses", JSONArray(doses.values.sortedWith(compareBy({ it.day }, { it.time }, { it.id })).map { it.json() }))
        .put("deleted", JSONArray(deleted.entries.sortedBy { it.key }.map { JSONObject().put("id", it.key).put("stamp", it.value) }))

    fun save() {
        val f = file ?: return
        val tmp = File(f.path + ".tmp")
        tmp.writeText(toJson().toString())
        tmp.renameTo(f)
    }

    private fun changed() {
        save()
        onChange?.invoke()
    }

    /** Merge the PC's copy: the newer change wins, deletions included. Returns how many changed. */
    fun absorb(o: JSONObject): Int {
        val other = AppStore(null).also { it.read(o) }
        var changes = 0
        val dead = deleted.toMutableMap()
        for ((id, stamp) in other.deleted) if (stamp > (dead[id] ?: "")) { dead[id] = stamp; changes++ }
        val meds = medicines.toMutableMap()
        for (m in other.medicines.values) if (m.updated > (meds[m.id]?.updated ?: "") && m.updated > (dead[m.id] ?: "")) { meds[m.id] = m; changes++ }
        val ds = doses.toMutableMap()
        for (d in other.doses.values) if (d.updated > (ds[d.id]?.updated ?: "") && d.updated > (dead[d.id] ?: "")) { ds[d.id] = d; changes++ }
        for (id in meds.keys.toList()) if ((dead[id] ?: "") >= meds[id]!!.updated) { meds.remove(id); changes++ }
        for (id in ds.keys.toList()) if ((dead[id] ?: "") >= ds[id]!!.updated) { ds.remove(id); changes++ }
        deleted = dead; medicines = meds; doses = ds
        if (changes > 0) save()
        return changes
    }

    // ---------------------------------------------------------------- medicines

    val active get() = medicines.values.filter { !it.archived }.sortedBy { it.name.lowercase() }
    val stopped get() = medicines.values.filter { it.archived }.sortedBy { it.name.lowercase() }

    /** Add or change a medicine; a new one, or a new dose or schedule, goes in its history. */
    fun put(med: Medicine, day: String = Day.today) {
        var m = med.copy(schedule = med.schedule.cleaned(), updated = Stamp.now())
        val old = medicines[m.id]
        m = if (old == null) m.copy(history = listOf(Change(day, "started", Snapshot.of(m))))
        else m.copy(history = old.history + if (Snapshot.of(old) != Snapshot.of(m)) listOf(Change(day, "changed", Snapshot.of(m))) else emptyList())
        medicines = medicines + (m.id to m)
        deleted = deleted - m.id
        changed()
    }

    fun setArchived(id: String, archived: Boolean) {
        val m = medicines[id] ?: return
        val n = m.copy(archived = archived, updated = Stamp.now(),
            history = m.history + Change(Day.today, if (archived) "stopped" else "restarted", Snapshot.of(m)))
        medicines = medicines + (id to n)
        changed()
    }

    fun delete(id: String) {
        val stamp = Stamp.now()
        val gone = doses.values.filter { it.med == id }.map { it.id }
        medicines = medicines - id
        doses = doses - gone.toSet()
        deleted = deleted + (id to stamp) + gone.associateWith { stamp }
        changed()
    }

    // ---------------------------------------------------------------- doses

    data class Slot(val time: String, val med: Medicine, val dose: DoseRecord?)

    /** The scheduled doses of a day, by time then name. */
    fun slots(day: String): List<Slot> = medicines.values.filter { it.dueOn(day) }
        .flatMap { m -> m.schedule.times.map { t -> Slot(t, m, doses[slotId(m.id, day, t)]) } }
        .sortedWith(compareBy({ it.time }, { it.med.name.lowercase() }))

    /** Doses taken when needed on a day. */
    fun extra(day: String) = doses.values.filter { it.day == day && it.time.isEmpty() }.sortedBy { it.at }

    fun mark(medId: String, day: String, time: String, status: String, at: String? = null, notify: Boolean = true) {
        val m = medicines[medId] ?: return
        val key = slotId(medId, day, time)
        val now = Stamp.now()
        doses = doses + (key to DoseRecord(key, medId, day, time, status, at ?: Day.now(), m.amountAt(time), now, now))
        deleted = deleted - key
        if (notify) changed() else save()
    }

    fun takeNow(medId: String, day: String = Day.today, at: String? = null) {
        val m = medicines[medId] ?: return
        val key = newId(); val now = Stamp.now()
        doses = doses + (key to DoseRecord(key, medId, day, "", "taken", at ?: Day.now(), m.amount, now, now))
        changed()
    }

    fun unmark(id: String) {
        if (id !in doses) return
        doses = doses - id
        deleted = deleted + (id to Stamp.now())
        changed()
    }

    data class Counts(val due: Int, val taken: Int, val skipped: Int)

    fun counts(day: String): Counts {
        val s = slots(day)
        return Counts(s.size, s.count { it.dose?.status == "taken" }, s.count { it.dose?.status == "skipped" })
    }

    // ---------------------------------------------------------------- stock

    fun remaining(m: Medicine): Double? {
        val stock = m.stock ?: return null
        val used = doses.values.filter { it.med == m.id && it.status == "taken" && it.stamp > stock.set }.sumOf { it.amount }
        return (stock.count - used).coerceAtLeast(0.0)
    }

    fun daysLeft(m: Medicine): Int? {
        val left = remaining(m) ?: return null
        if (m.perDay <= 0) return null
        return (left / m.perDay).toInt()
    }

    fun isLow(m: Medicine): Boolean = daysLeft(m)?.let { it <= m.refillDays } ?: false

    companion object {
        fun slotId(med: String, day: String, time: String) = "$med@$day@$time"
    }
}

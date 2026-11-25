package io.github.akiig.dose

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Base64

class ModelTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun cryptoMatchesThePcAndIphone() {
        assertEquals("3fbf719d97272440ee91a3f08240c8bae2cb00600c386941d0a7ec35357340ca", hex(SyncCrypto.key("test-token-0123456789")))
        val doc = JSONObject(SyncCrypto.sealWith("{\"a\":1}".toByteArray(), "test-token-0123456789", "/api/e/ping", ByteArray(12), reply = false))
        assertEquals("8795b68f38be54580422caa7482cff3c237c0969349bed", hex(Base64.getDecoder().decode(doc.getString("c"))))
    }

    @Test fun schedules() {
        val daily = Schedule("daily", listOf("20:00", "08:00", "08:00"), start = "2026-11-01").cleaned()
        assertEquals(listOf("08:00", "20:00"), daily.times)
        val m = Medicine(name = "A", schedule = daily)
        assertTrue(m.dueOn("2026-11-01")); assertFalse(m.dueOn("2026-10-31"))
        val every = Medicine(name = "B", schedule = Schedule("every", listOf("09:00"), every = 3, start = "2026-11-01"))
        assertTrue(every.dueOn("2026-11-04")); assertFalse(every.dueOn("2026-11-05"))
        val week = Medicine(name = "C", schedule = Schedule("weekdays", listOf("09:00"), weekdays = listOf(1, 4), start = "2026-11-01"))
        assertTrue(week.dueOn("2026-11-02")); assertFalse(week.dueOn("2026-11-03"))   // Monday yes, Tuesday no
        val course = Medicine(name = "D", schedule = Schedule("daily", listOf("09:00"), start = "2026-11-01", end = "2026-11-07"))
        assertTrue(course.dueOn("2026-11-07")); assertFalse(course.dueOn("2026-11-08"))
        assertFalse(Medicine(name = "E", schedule = Schedule("needed", emptyList())).dueOn("2026-11-02"))
    }

    @Test fun roundTripsThePcFormat() {
        val s = AppStore(null)
        s.put(Medicine(id = "m1", name = "Risperidone", strength = "2 mg", schedule = Schedule(times = listOf("21:00"), start = "2026-11-01"),
            stock = Stock(30.0, "2026-11-01T00:00:00.000Z")))
        s.mark("m1", "2026-11-02", "21:00", "taken", "21:05")
        val back = AppStore(null).also { it.absorb(s.toJson()) }
        assertEquals(s.toJson().toString(), back.toJson().toString())
        assertEquals("m1@2026-11-02@21:00", back.doses.keys.single())
        assertEquals(29.0, back.remaining(back.medicines["m1"]!!)!!, 0.001)
        assertEquals("started", back.medicines["m1"]!!.history.single().kind)
    }

    @Test fun mergeNewerWinsAndTombstones() {
        val a = AppStore(null)
        a.put(Medicine(id = "m1", name = "A", schedule = Schedule(times = listOf("08:00"), start = "2026-11-01")))
        val b = AppStore(null).also { it.absorb(a.toJson()) }
        Thread.sleep(5)
        b.put(b.medicines["m1"]!!.copy(name = "A2"))
        assertEquals(1, a.absorb(b.toJson()).coerceAtMost(1))
        assertEquals("A2", a.medicines["m1"]!!.name)
        Thread.sleep(5)
        a.delete("m1")
        b.absorb(a.toJson())
        assertTrue(b.medicines.isEmpty())
        // an older copy coming back doesn't bring it back
        val old = JSONObject(a.toJson().toString()).put("deleted", org.json.JSONArray())
            .put("medicines", org.json.JSONArray().put(Medicine(id = "m1", name = "A", updated = "2020-01-01T00:00:00.000Z").json()))
        b.absorb(old)
        assertTrue(b.medicines.isEmpty())
    }

    @Test fun stockAndRefill() {
        val s = AppStore(null)
        s.put(Medicine(id = "m", name = "X", amount = 2.0, refillDays = 7,
            schedule = Schedule(times = listOf("08:00", "20:00"), start = "2026-11-01"), stock = Stock(30.0, "2000-01-01T00:00:00.000Z")))
        val m = s.medicines["m"]!!
        assertEquals(4.0, m.perDay, 0.001)
        assertEquals(7, s.daysLeft(m)); assertTrue(s.isLow(m))
    }

    @Test fun nextReminder() {
        val s = AppStore(null)
        s.put(Medicine(id = "m", name = "X", schedule = Schedule(times = listOf("08:00", "21:00"), start = "2026-11-01")))
        val now = LocalDateTime.of(2026, 11, 10, 8, 10)
        val a = Reminders.next(s, now, followUp = true)!!
        assertEquals(LocalDateTime.of(2026, 11, 10, 8, 30), a.at); assertTrue(a.again)
        s.mark("m", "2026-11-10", "08:00", "taken")
        val b = Reminders.next(s, now, followUp = true)!!
        assertEquals("21:00", b.time); assertFalse(b.again)
        assertEquals(LocalDateTime.of(2026, 11, 10, 21, 0), Reminders.next(s, now, followUp = false)!!.at)
        assertNull(Reminders.next(AppStore(null), now, true))
    }

    @Test fun perTimeAmounts() {
        val s = AppStore(null)
        s.put(Medicine(id = "pt", name = "Per time", schedule = Schedule(times = listOf("08:00", "20:00"), start = "2026-01-01",
            amounts = mapOf("20:00" to 0.5, "13:00" to 2.0))))
        val m = s.medicines["pt"]!!
        assertEquals(mapOf("20:00" to 0.5), m.schedule.amounts)
        assertEquals(1.5, m.perDay, 0.001)
        s.mark("pt", "2026-02-01", "20:00", "taken")
        assertEquals(0.5, s.doses["pt@2026-02-01@20:00"]!!.amount, 0.001)
        // the same as the PC writes it, and read back
        val json = m.schedule.json()
        assertEquals(0.5, json.getJSONObject("amounts").getDouble("20:00"), 0.001)
        assertEquals(m.schedule, Schedule.from(json))
        assertFalse(Schedule(times = listOf("08:00")).cleaned().json().has("amounts"))
    }

    @Test fun aicFromTheBox() {
        assertEquals("012745093", Aic.fromText("A012745093"))
        assertNull(Aic.fromText("tachipirina"))
        assertEquals("012745093", Aic.fromBarcode("A012745093"))
        assertEquals("012345676", Aic.fromBarcode("0CSSBD"))                 // Code 32, published example
        assertEquals("012745093", Aic.fromBarcode("]d20108012345678906" + "21ABC123\u001d" + "17271231" + "10L01\u001d" + "716012745093"))
        assertEquals("012745093", Aic.fromBarcode("010801234567890617271231716012745093"))
        assertNull(Aic.fromBarcode("hello"))
        fun item(s: String, packs: String) = MedicineList.Item("Tachipirina", s, "Compressa", "Paracetamolo", "tachipirina", "paracetamolo", packs)
        val all = listOf(item("500 mg", "012745:093,105"), item("1000 mg", "012745:220"))
        assertEquals("1000 mg", Aic.find(all, "012745220")!!.strength)
        assertEquals("500 mg", Aic.find(all, "012745999")!!.strength)
        assertNull(Aic.find(all, "999999999"))
        assertEquals(listOf("500 mg"), MedicineList.search(all, "A012745105").map { it.strength })
    }

    @Test fun reportCounts() {
        val s = AppStore(null)
        s.put(Medicine(id = "a", name = "A", schedule = Schedule(times = listOf("08:00", "20:00"), start = "2026-11-01")), "2026-11-01")
        s.put(Medicine(id = "n", name = "N", schedule = Schedule("needed", emptyList(), start = "2026-11-01")), "2026-11-01")
        s.mark("a", "2026-11-02", "08:00", "taken", "08:05")
        s.mark("a", "2026-11-02", "20:00", "skipped")
        s.mark("a", "2026-11-03", "08:00", "taken", "08:01")
        s.takeNow("n", "2026-11-03", "15:00")
        // the 3rd at 12:00: 20:00 isn't due yet, so it isn't counted
        val d = Report.data(s, "2026-11-02", "2026-11-03", LocalDateTime.of(2026, 11, 3, 12, 0))
        val c = d.meds.single()
        assertEquals(listOf(3, 2, 1, 0), listOf(c.due, c.taken, c.skipped, c.missed))
        assertEquals(1, d.needed.single().second)
        assertEquals(listOf("2026-11-02", "2026-11-03"), d.days.map { it.first })
        // a day without marks is "not taken" for each dose
        val e = Report.data(s, "2026-11-04", "2026-11-04", LocalDateTime.of(2026, 11, 5, 9, 0))
        assertEquals(2, e.meds.single().missed)
        // stopped later: still counted on the days before
        s.setArchived("a", true)
        assertEquals(3, Report.data(s, "2026-11-02", "2026-11-03", LocalDateTime.of(2026, 11, 3, 12, 0)).meds.single().due)
    }

    @Test fun backupRestores() {
        val s = AppStore(null)
        s.put(Medicine(id = "a", name = "A", schedule = Schedule(times = listOf("08:00"), start = "2026-11-01")))
        s.mark("a", "2026-11-02", "08:00", "taken")
        val out = java.io.ByteArrayOutputStream()
        Backup.write(s, out, "1.2.0")
        val fresh = AppStore(null)
        assertEquals(2, Backup.restore(fresh, out.toString("UTF-8")))
        assertEquals(s.toJson().toString(), fresh.toJson().toString())
        assertNull(Backup.restore(fresh, "{\"hello\": 1}"))
        assertNull(Backup.restore(fresh, "not json"))
    }

    @Test fun amounts() {
        assertEquals("½ pill", Units.amount(0.5, "pill"))
        assertEquals("1½ pills", Units.amount(1.5, "pill"))
        assertEquals("2 drops", Units.amount(2.0, "drop"))
    }

    @Test fun searchesTheList() {
        fun item(n: String, i: String) = MedicineList.Item(n, "", "", i, MedicineList.fold(n), MedicineList.fold(i))
        val all = listOf(item("SERTRALINA DOC", "sertralina"), item("ZOLOFT", "sertralina cloridrato"), item("TACHIPIRINA", "paracetamolo"))
        assertEquals(listOf("SERTRALINA DOC", "ZOLOFT"), MedicineList.search(all, "sertr").map { it.name })
        assertEquals("drop", MedicineList.unitFor("gocce orali, soluzione"))
    }
}

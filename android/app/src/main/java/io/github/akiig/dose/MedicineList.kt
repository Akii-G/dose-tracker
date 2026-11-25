package io.github.akiig.dose

import android.content.Context
import java.text.Normalizer

/**
 * Medicines authorised in Italy (AIFA open data, CC BY 4.0): name, strength, form and active
 * ingredient, built into the app from data/medicines-it.tsv. Searched on the phone: nothing is
 * looked up online, so no one learns what you take.
 */
object MedicineList {
    data class Item(val name: String, val strength: String, val form: String, val ingredient: String,
                    val foldedName: String, val foldedIngredient: String, val packs: String = "")

    @Volatile private var items: List<Item>? = null

    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun load(context: Context): List<Item> = items ?: synchronized(this) {
        items ?: try {
            context.assets.open("medicines-it.tsv").bufferedReader().useLines { lines ->
                lines.filter { !it.startsWith("#") }.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size >= 4) Item(p[0], p[1], p[2], p[3], fold(p[0]), fold(p[3]), p.getOrElse(4) { "" }) else null
                }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }.also { items = it }
    }

    /** Names starting with the words first, then ingredients, then names containing them. */
    fun search(all: List<Item>, query: String, limit: Int = 30): List<Item> {
        Aic.fromText(query)?.let { code -> return listOfNotNull(Aic.find(all, code)) }   // the number on the box
        val q = fold(query.trim())
        if (q.length < 2) return emptyList()
        val first = ArrayList<Item>(); val second = ArrayList<Item>(); val third = ArrayList<Item>()
        for (item in all) {
            when {
                item.foldedName.startsWith(q) -> first.add(item)
                item.foldedIngredient.startsWith(q) -> second.add(item)
                item.foldedName.contains(q) || item.foldedIngredient.contains(q) -> third.add(item)
            }
            if (first.size >= limit) break
        }
        return (first + second + third).take(limit)
    }

    /** The unit that fits a form from the list: drops for "gocce", capsules for "capsula"… */
    fun unitFor(form: String): String {
        val f = form.lowercase()
        return when {
            "gocce" in f -> "drop"
            "capsul" in f -> "capsule"
            "bustin" in f -> "sachet"
            "cerott" in f -> "patch"
            "sciroppo" in f || "soluzione orale" in f || "sospensione orale" in f -> "ml"
            "inala" in f -> "puff"
            "iniett" in f -> "injection"
            else -> "pill"
        }
    }
}

/**
 * The AIC number (9 digits, "A012345678" on the box) names one pack of one medicine. Boxes carry it
 * as Code 39 ("A012345678"), as Code 32 (the same number in base 32, 6 characters, read as Code 39)
 * and, since 2025, in the GS1 DataMatrix under AI (716). Same rules as the PC (src/dose).
 */
object Aic {
    private const val CODE32 = "0123456789BCDFGHJKLMNPQRSTUVWXYZ"
    private val fixed = mapOf("00" to 18, "01" to 14, "02" to 14, "11" to 6, "12" to 6, "13" to 6, "15" to 6, "16" to 6, "17" to 6)

    /** Typed by hand: "A012345678", "012345678" -> "012345678". */
    fun fromText(text: String): String? =
        Regex("A?(\\d{9})").matchEntire(text.replace(Regex("[\\s.\\-]"), "").uppercase())?.groupValues?.get(1)

    fun fromBarcode(payload: String): String? {
        var p = payload.trim()
        if (p.startsWith("]") && p.length > 3) p = p.substring(3)
        fromText(p)?.let { return it }
        if (p.length == 6 && p.uppercase().all { it in CODE32 }) {
            var n = 0L
            for (c in p.uppercase()) n = n * 32 + CODE32.indexOf(c)
            return if (n < 1_000_000_000L) n.toString().padStart(9, '0') else null
        }
        var i = 0
        while (i < p.length) {
            if (p[i] == '\u001d') { i++; continue }
            val ai = p.substring(i, minOf(i + 2, p.length))
            val len = fixed[ai]
            if (len != null) { i += 2 + len; continue }
            var end = p.indexOf('\u001d', i)
            if (end < 0) end = p.length
            val field = p.substring(i, end)
            if (field.startsWith("716")) {
                val digits = field.drop(3).filter { it.isDigit() }
                return if (digits.isEmpty()) null else digits.takeLast(9).padStart(9, '0')
            }
            i = end
        }
        return Regex("716(\\d{9})(?!\\d)").find(p)?.groupValues?.get(1)
    }

    /** The medicine of that pack, or of another pack of the same medicine. */
    fun find(all: List<MedicineList.Item>, code: String): MedicineList.Item? {
        val farmaco = code.take(6); val pack = code.drop(6)
        var near: MedicineList.Item? = null
        for (item in all) {
            if (item.packs.isEmpty() || !item.packs.contains(farmaco)) continue
            for (group in item.packs.split(';')) {
                val head = group.substringBefore(':')
                if (head == farmaco) {
                    if (pack in group.substringAfter(':').split(',')) return item
                    if (near == null) near = item
                }
            }
        }
        return near
    }
}

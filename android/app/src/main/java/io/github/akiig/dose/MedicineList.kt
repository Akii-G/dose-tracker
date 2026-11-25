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
                    val foldedName: String, val foldedIngredient: String)

    @Volatile private var items: List<Item>? = null

    fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun load(context: Context): List<Item> = items ?: synchronized(this) {
        items ?: try {
            context.assets.open("medicines-it.tsv").bufferedReader().useLines { lines ->
                lines.filter { !it.startsWith("#") }.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size == 4) Item(p[0], p[1], p[2], p[3], fold(p[0]), fold(p[3])) else null
                }.toList()
            }
        } catch (e: Exception) {
            emptyList()
        }.also { items = it }
    }

    /** Names starting with the words first, then ingredients, then names containing them. */
    fun search(all: List<Item>, query: String, limit: Int = 30): List<Item> {
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

package io.github.akiig.dose

import android.app.Application
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders each screen with sample data to build/screenshots/: to check the layout, and for the README. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
abstract class Screens(private val lang: String) {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        fun seed() {
            val state = AppState.get(ApplicationProvider.getApplicationContext<Application>())
            state.prefs.server = "pop-os.local:8766"
            state.prefs.token = "sample-token-0123456789"
            state.chooseAutoSync(false)
            val s = state.store
            if (s.medicines.isNotEmpty()) return
            val start = Day.adding(-40, Day.today)
            s.put(Medicine(id = "ris", name = "Risperidone", strength = "2 mg", form = "compresse",
                schedule = Schedule(times = listOf("21:00"), start = start), stock = Stock(60.0, "2000-01-01T00:00:00.000Z")), start)
            s.put(Medicine(id = "ser", name = "Sertraline", strength = "50 mg", note = "with breakfast",
                schedule = Schedule(times = listOf("08:00"), start = start)), start)
            s.put(Medicine(id = "vit", name = "Vitamin D", amount = 4.0, unit = "drop",
                schedule = Schedule("weekdays", listOf("13:00"), weekdays = listOf(1, 4), start = start)), start)
            s.put(Medicine(id = "ibu", name = "Ibuprofen", strength = "400 mg", schedule = Schedule("needed", emptyList(), start = start)), start)
            s.put(Medicine(id = "amo", name = "Amoxicillin", strength = "1 g", archived = true,
                schedule = Schedule(times = listOf("08:00", "20:00"), start = Day.adding(-30, Day.today), end = Day.adding(-24, Day.today))), start)
            for (i in 1..34) {
                val d = Day.adding(-i, Day.today)
                if (i % 9 != 4) s.mark("ser", d, "08:00", "taken", "08:1${i % 10}", notify = false)
                if (i % 11 == 6) s.mark("ris", d, "21:00", "skipped", notify = false)
                else if (i % 13 != 2) s.mark("ris", d, "21:00", "taken", "21:0${i % 10}", notify = false)
                if (s.medicines["vit"]!!.dueOn(d)) s.mark("vit", d, "13:00", "taken", "13:20", notify = false)
            }
            s.mark("ser", Day.today, "08:00", "taken", "08:12")
            s.takeNow("ibu", Day.today, "11:40")
        }
    }

    protected fun shot(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screenshots/${lang}_$name.png")
    }

    private fun tab(text: String) = rule.onAllNodes(hasText(tr(text)))[0].performClick()

    @Test fun screens() {
        shot("1-today")
        tab("Medicines"); shot("2-medicines")
        tab("History"); shot("3-history")
        tab("Settings"); shot("4-settings")
        rule.onAllNodes(hasText(tr("Chime")))[0].performScrollTo(); shot("4b-sound")
        tab("Medicines")
        rule.onAllNodes(hasText("Risperidone 2 mg"))[0].performClick()
        shot("5-editor")
        rule.onAllNodes(hasText(tr("Cancel")))[0].performClick()
        tab("Settings")
        rule.onAllNodes(hasText(tr("Report for your doctor")))[0].performScrollTo().performClick()
        shot("6-report")
        rule.onAllNodes(hasText(tr("Close")))[0].performClick()
        tab("Today")
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(tr("Add a medicine")))[0].performClick()
        shot("7-add")
    }
}

class ScreensEnglish : Screens("en") {
    init { seed() }

    /** Another colour tone recolours the app at once. */
    @Test fun teal() {
        rule.runOnUiThread { io.github.akiig.dose.ui.Tones.choose("teal") }
        shot("tone-teal")
        rule.onAllNodes(hasText(tr("Medicines")))[0].performClick(); shot("tone-teal-medicines")
        rule.runOnUiThread { io.github.akiig.dose.ui.Tones.choose("violet") }
    }

    /** Tapping only Friday in a new medicine means Friday only (all seven used to start ticked). */
    @Test fun onlyFriday() {
        val state = AppState.get(ApplicationProvider.getApplicationContext())
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription(tr("Add a medicine")))[0].performClick()
        rule.onAllNodes(hasText(tr("Name")))[0].performTextInput("Friday pill")
        rule.onAllNodes(hasText(tr("Every day")))[0].performScrollTo().performClick()
        rule.onAllNodes(hasText(tr("Some days of the week")))[0].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText(tr("Save")))[0]
            .assertIsNotEnabled()     // no day picked yet
        rule.onAllNodes(hasText(tr("Fri").take(2)))[0].performClick()
        shot("friday")
        rule.onAllNodes(hasText(tr("Save")))[0].performClick()
        rule.waitForIdle()
        val med = state.store.medicines.values.single { it.name == "Friday pill" }
        org.junit.Assert.assertEquals(listOf(5), med.schedule.weekdays)
        org.junit.Assert.assertEquals(Day.isoWeekday(Day.today) == 5, med.dueOn(Day.today))
        rule.runOnUiThread { state.store.delete(med.id) }
    }
}

@Config(qualifiers = "it-w411dp-h891dp-xxhdpi")
class ScreensItalian : Screens("it") { init { seed() } }

@Config(qualifiers = "it-w411dp-h891dp-night-xxhdpi")
class ScreensDark : Screens("it-dark") { init { seed() } }

/** The largest text Android allows (200%): everything must still fit and be usable. */
@Config(qualifiers = "it-w411dp-h891dp-xxhdpi", fontScale = 2.0f)
class ScreensLargeText : Screens("it-large") { init { seed() } }

@Config(qualifiers = "it-w411dp-h891dp-xxhdpi", fontScale = 1.25f)
class ScreensText125 : Screens("it-125") { init { seed() } }

@Config(qualifiers = "it-w411dp-h891dp-xxhdpi", fontScale = 1.5f)
class ScreensText150 : Screens("it-150") { init { seed() } }

@Config(qualifiers = "it-w411dp-h891dp-xxhdpi", fontScale = 1.75f)
class ScreensText175 : Screens("it-175") { init { seed() } }

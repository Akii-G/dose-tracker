package io.github.akiig.dose

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.akiig.dose.ui.DoseApp
import io.github.akiig.dose.ui.DoseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import java.io.File

/** Everything the screens share. One per process, so a screen rotation keeps it. */
class AppState private constructor(app: Application) {
    val prefs = Prefs(app)
    val store = AppStore(File(app.filesDir, "dose.json"))
    val scope: CoroutineScope = MainScope()
    val client = SyncClient(prefs, SyncClient.deviceName(app))
    val sync = SyncCoordinator(prefs, client, scope)
    private val context = app.applicationContext

    /** Set when a reminder was tapped: show Today. */
    var openToday by mutableStateOf(false)

    // switches the screens follow at once
    var remind by mutableStateOf(prefs.remind)
        private set
    var followUp by mutableStateOf(prefs.followUp)
        private set
    var discreet by mutableStateOf(prefs.discreet)
        private set
    var autoSync by mutableStateOf(prefs.autoSync)
        private set
    fun chooseRemind(on: Boolean) { remind = on; prefs.remind = on; replan() }
    fun chooseFollowUp(on: Boolean) { followUp = on; prefs.followUp = on; replan() }
    fun chooseDiscreet(on: Boolean) { discreet = on; prefs.discreet = on }
    var priority by mutableStateOf(prefs.priority)
        private set
    var sound by mutableStateOf(prefs.sound)
        private set
    var soundUri by mutableStateOf(prefs.soundUri)
        private set
    fun choosePriority(p: String) { priority = p; prefs.priority = p; Reminders.createChannel(context) }
    fun chooseSound(s: String, uri: String = soundUri) {
        sound = s; soundUri = uri; prefs.sound = s; prefs.soundUri = uri
        Reminders.createChannel(context)
        Reminders.preview(context, s, uri)
    }
    fun chooseAutoSync(on: Boolean) { autoSync = on; prefs.autoSync = on; if (on) sync.syncSoon(store, 0) }

    fun replan() = Reminders.plan(context, store)
    fun chooseTone(key: String) { prefs.tone = key; io.github.akiig.dose.ui.Tones.choose(key) }

    /** Asks Android 13+ for notifications; set by the screen, which owns the request. */
    var askNotifications: () -> Unit = {}

    init {
        io.github.akiig.dose.ui.Tones.choose(prefs.tone)
        store.onChange = {
            sync.syncSoon(store, 2_000)
            replan()
        }
        sync.onChanges = { replan() }
        client.remember = { host, ip -> prefs.p.edit().putString("ip:$host", ip).apply() }
        client.recall = { host -> prefs.p.getString("ip:$host", null) }
        client.multicastLock = {
            val wifi = context.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock = wifi.createMulticastLock("dose-find-pc").apply { setReferenceCounted(false); acquire() }
            ({ lock.release() })
        }
    }

    companion object {
        @Volatile private var instance: AppState? = null
        fun get(app: Application): AppState =
            instance ?: synchronized(this) { instance ?: AppState(app).also { instance = it } }
    }
}

val LocalApp = staticCompositionLocalOf<AppState> { error("no AppState") }

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadTranslations(this)
        enableEdgeToEdge()
        state = AppState.get(application)
        if (intent?.getBooleanExtra(Reminders.OPEN_TODAY, false) == true) state.openToday = true
        Reminders.createChannel(this)
        state.replan()
        setContent {
            DoseTheme {
                CompositionLocalProvider(LocalApp provides state) { DoseApp() }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Reminders.OPEN_TODAY, false)) state.openToday = true
    }

    override fun onResume() {
        super.onResume()
        state.store.load()          // a "Taken" from a notification may have changed the file
        state.replan()
        state.sync.syncSoon(state.store, 0)
    }
}

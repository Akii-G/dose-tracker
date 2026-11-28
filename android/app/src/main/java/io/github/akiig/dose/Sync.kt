package io.github.akiig.dose

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

class SyncException(message: String) : Exception(message)

/** One PC the phone syncs with. The phone holds all medicines and doses, so it passes them
 *  between PCs: a dose marked on one PC reaches the others through the phone. */
data class PCLink(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val server: String = "",
    val token: String = "",
) {
    val isConfigured get() = server.isNotBlank() && token.isNotBlank()

    /** The name, or the computer's name from the address ("pop-os" for pop-os.local:8766). */
    val title: String
        get() {
            if (name.isNotBlank()) return name.trim()
            var raw = server.trim()
            if (!raw.contains("://")) raw = "http://$raw"
            val host = try { URI(raw).host } catch (e: Exception) { null }
            if (host.isNullOrEmpty()) return tr("New PC")
            if (host.first().isDigit() || host.contains(':')) return host    // an IP address stays whole
            return host.substringBefore('.')
        }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("server", server)
        .put("token", token)

    companion object {
        fun from(o: JSONObject) = PCLink(o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
            o.optString("name"), o.optString("server"), o.optString("token"))
    }
}

/** Address, token and switches, kept in the app's private settings. */
class Prefs(context: Context) {
    val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var server: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()
    var autoSync: Boolean
        get() = p.getBoolean("autoSync", true)
        set(v) = p.edit().putBoolean("autoSync", v).apply()
    var followUp: Boolean
        get() = p.getBoolean("followUp", true)
        set(v) = p.edit().putBoolean("followUp", v).apply()
    var discreet: Boolean
        get() = p.getBoolean("discreet", false)
        set(v) = p.edit().putBoolean("discreet", v).apply()
    var remind: Boolean
        get() = p.getBoolean("remind", true)
        set(v) = p.edit().putBoolean("remind", v).apply()
    /** urgent (pops up, the sound repeats until you answer), high (pops up), normal (no pop-up), quiet (no sound). */
    var tone: String
        get() = p.getString("tone", "violet") ?: "violet"
        set(v) = p.edit().putString("tone", v).apply()
    var priority: String
        get() = p.getString("priority", "high") ?: "high"
        set(v) = p.edit().putString("priority", v).apply()
    /** default (the phone's), chime, bell, soft, none, or custom (soundUri, picked from the phone's sounds). */
    var sound: String
        get() = p.getString("sound", "default") ?: "default"
        set(v) = p.edit().putString("sound", v).apply()
    var soundUri: String
        get() = p.getString("soundUri", "") ?: ""
        set(v) = p.edit().putString("soundUri", v).apply()

    /** The PCs in Settings. The single PC of older versions ("server", "token") becomes the first. */
    var pcs: List<PCLink>
        get() {
            val raw = p.getString("pcs", null)
            if (raw != null) return try {
                val a = JSONArray(raw)
                (0 until a.length()).map { PCLink.from(a.getJSONObject(it)) }
            } catch (e: Exception) { emptyList() }
            val old = if (server.isNotBlank() || token.isNotBlank()) listOf(PCLink(server = server, token = token)) else emptyList()
            pcs = old
            return old
        }
        set(v) = p.edit().putString("pcs", JSONArray(v.map { it.toJson() }).toString()).apply()

    fun updatePC(id: String, change: (PCLink) -> PCLink) {
        pcs = pcs.map { if (it.id == id) change(it) else it }
    }

    val configured get() = pcs.filter { it.isConfigured }
    val isConfigured get() = configured.isNotEmpty()
}

class SyncClient(
    private val server: () -> String,
    private val tokenSource: () -> String,
    private val deviceName: String,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) {
    constructor(prefs: Prefs, deviceName: String) : this({ prefs.server }, { prefs.token }, deviceName)

    private val token get() = tokenSource().trim()

    /** A client for one PC, sharing where .local names were last found. */
    fun forPC(pc: PCLink): SyncClient = SyncClient({ pc.server }, { pc.token }, deviceName, main).also {
        it.remember = remember; it.recall = recall; it.multicastLock = multicastLock
    }

    /** "pop-os.local:8766", "192.168.1.5:8766" or a full URL; 8766 when no port is given. */
    fun baseUrl(): URI {
        var raw = server().trim()
        if (raw.isEmpty() || token.isEmpty()) throw SyncException(tr("Add your PC's address and token first."))
        if (!raw.lowercase().startsWith("http://") && !raw.lowercase().startsWith("https://")) raw = "http://$raw"
        raw = raw.trimEnd('/')
        var uri = try { URI(raw) } catch (e: Exception) { null }
        if (uri?.host.isNullOrEmpty()) throw SyncException(tr("The address doesn't look right. It should be like pop-os.local:8766"))
        if (uri!!.port <= 0) uri = URI("$raw:8766")
        return uri
    }

    /** Where pop-os.local was last found, so the next sync is quick. */
    var remember: ((host: String, ip: String) -> Unit)? = null
    var recall: ((host: String) -> String?)? = null
    /** Lets the phone receive network-wide (multicast) answers while looking a name up. */
    var multicastLock: (() -> (() -> Unit))? = null

    /** The addresses to try. Many Android phones can't look up .local names, so Dose also asks the network itself. */
    private fun candidates(host: String): Sequence<String> = sequence {
        if (!host.lowercase().endsWith(".local")) {
            yield(host); return@sequence
        }
        val tried = mutableSetOf<String>()
        recall?.invoke(host)?.let { if (tried.add(it)) yield(it) }
        val system = try { InetAddress.getByName(host).hostAddress } catch (e: Exception) { null }
        if (system != null && tried.add(system)) yield(system)
        val short = host.dropLast(".local".length)
        val router = try { InetAddress.getByName(short).takeIf { !it.isLoopbackAddress }?.hostAddress } catch (e: Exception) { null }
        if (router != null && tried.add(router)) yield(router)
        val release = try { multicastLock?.invoke() } catch (e: Exception) { null }
        val asked = try { Mdns.resolve(host)?.hostAddress } finally { release?.invoke() }
        if (asked != null && tried.add(asked)) yield(asked)
        if (tried.isEmpty()) throw SyncException(tr("Can't find %@ on your Wi-Fi. Is the PC on and on the same Wi-Fi? You can also type its IP address.", host))
    }

    private fun send(url: URL, sealed: ByteArray, timeout: Int): Pair<Int, String> {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeout
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setFixedLengthStreamingMode(sealed.size)
            conn.outputStream.use { it.write(sealed) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** Sends `payload` encrypted to `path` and returns the decrypted reply. The token is only the key. */
    fun request(path: String, payload: ByteArray): ByteArray {
        val base = baseUrl()
        val sealed = SyncCrypto.seal(payload, token, path).toByteArray()
        var result: Pair<Int, String>? = null
        var failure: IOException? = null
        for (host in candidates(base.host)) {
            val shown = if (host.contains(':')) "[$host]" else host
            try {
                result = send(URL("${base.scheme}://$shown:${base.port}${base.rawPath ?: ""}$path"), sealed, 5_000)
                if (host != base.host) remember?.invoke(base.host, host)
                break
            } catch (e: IOException) {
                failure = e
            }
        }
        val (code, body) = result ?: throw SyncException(tr("Can't reach your PC. Is it on, with Phone sync switched on, and on the same Wi-Fi? (%@)",
            failure?.localizedMessage ?: failure?.javaClass?.simpleName ?: ""))
        if (code == 401) throw SyncException(tr("Wrong token. Copy it again from Dose on your PC (☰ → Phone sync)."))
        if (code == 403) throw SyncException(tr("Phone sync is switched off in Dose on your PC (☰ → Phone sync)."))
        if (code !in 200..299) {
            val message = try { JSONObject(body).optString("error") } catch (e: Exception) { "" }
            throw SyncException(message.ifEmpty { tr("Your PC answered with error %@.", code) })
        }
        return try {
            SyncCrypto.open(body, token, path)
        } catch (e: SyncCrypto.Unreadable) {
            throw SyncException(tr("Wrong token. Copy it again from Dose on your PC (☰ → Phone sync)."))
        }
    }

    fun ping(): String {
        val data = request("/api/e/ping", "{}".toByteArray())
        val version = try { JSONObject(String(data)).optString("version", "?") } catch (e: Exception) { "?" }
        return tr("Connected to Dose %@ on your PC (encrypted)", version)
    }

    /** Sends everything on the phone, gets the merged state back and merges it here. */
    suspend fun sync(store: AppStore): Int {
        val body = withContext(main) { store.toJson() }.put("device", deviceName).toString().toByteArray()
        val data = withContext(Dispatchers.IO) { request("/api/e/sync", body) }
        val remote = try { JSONObject(String(data)) } catch (e: Exception) {
            throw SyncException(tr("Your PC sent something unexpected. Update Dose on your PC."))
        }
        return withContext(main) { store.absorb(remote) }
    }

    companion object {
        fun deviceName(context: Context): String =
            Settings.Global.getString(context.contentResolver, "device_name") ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}

/** Runs syncs: when the app opens, a couple of seconds after a change, and on request. */
class SyncCoordinator(private val prefs: Prefs, private val client: SyncClient, private val scope: CoroutineScope) {
    private var lastStatus by mutableStateOf("")
    var status: String
        get() = lastStatus.ifEmpty { tr("Not synced yet") }
        set(v) { lastStatus = v }
    var busy by mutableStateOf(false)
    /** Called when a sync brought changes: plan the reminders again. */
    var onChanges: (() -> Unit)? = null

    private var pending: Job? = null
    private var again = false

    fun syncSoon(store: AppStore, delayMs: Long) {
        if (!prefs.autoSync || !prefs.isConfigured) return
        pending?.cancel()
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            scope.launch { run(store) }
        }
    }

    /** The last result for each PC (by id), shown under its name in Settings. */
    val pcStatus = mutableStateMapOf<String, String>()

    private fun message(e: Exception) = if (e is SyncException) e.message ?: "" else e.localizedMessage ?: e.javaClass.simpleName

    /** Syncs with every PC in turn. When a later PC brings something new, the earlier
     *  ones get a second round, so every PC ends up with everything. */
    suspend fun run(store: AppStore) {
        if (busy) { again = true; return }
        val pcs = prefs.configured
        if (pcs.isEmpty()) {
            status = tr("Add your PC's address and token first.")
            return
        }
        busy = true
        try {
            var changes = 0
            val failed = mutableMapOf<String, String>()
            for (round in 0 until 2) {
                var newFromLater = false
                for ((i, pc) in pcs.withIndex()) {
                    if (round == 1 && pc.id in failed) continue
                    try {
                        val n = client.forPC(pc).sync(store)
                        changes += n
                        if (n > 0 && i > 0) newFromLater = true
                        failed.remove(pc.id)
                        pcStatus[pc.id] = tr("Synced at %@", Day.now())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed[pc.id] = message(e)
                        pcStatus[pc.id] = message(e)
                    }
                }
                if (!newFromLater || pcs.size < 2) break
            }
            val time = Day.now()
            status = if (failed.size == pcs.size) {
                if (pcs.size == 1) failed.getValue(pcs[0].id) else tr("Couldn't sync with any PC. Tap one in Settings to see why.")
            } else {
                var text = if (changes > 0) trn("Synced at %2\$@ · %1\$@ change", "Synced at %2\$@ · %1\$@ changes", changes, time)
                           else tr("Synced at %@", time)
                for (pc in pcs) if (pc.id in failed) text += "\n" + tr("%@: not reached", pc.title)
                text
            }
            if (changes > 0) onChanges?.invoke()
        } finally {
            busy = false
        }
        if (again) { again = false; run(store) }
    }
}

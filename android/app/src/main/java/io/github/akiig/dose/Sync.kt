package io.github.akiig.dose

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
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
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

class SyncException(message: String) : Exception(message)

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

    val isConfigured get() = server.isNotBlank() && token.isNotBlank()
}

class SyncClient(
    private val server: () -> String,
    private val tokenSource: () -> String,
    private val deviceName: String,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) {
    constructor(prefs: Prefs, deviceName: String) : this({ prefs.server }, { prefs.token }, deviceName)

    private val token get() = tokenSource().trim()

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

    suspend fun run(store: AppStore) {
        if (busy) { again = true; return }
        busy = true
        try {
            val changes = client.sync(store)
            val time = Day.now()
            status = if (changes > 0) trn("Synced at %2\$@ · %1\$@ change", "Synced at %2\$@ · %1\$@ changes", changes, time)
                     else tr("Synced at %@", time)
            if (changes > 0) onChanges?.invoke()
        } catch (e: SyncException) {
            status = e.message ?: ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = e.localizedMessage ?: e.javaClass.simpleName
        } finally {
            busy = false
        }
        if (again) { again = false; run(store) }
    }
}

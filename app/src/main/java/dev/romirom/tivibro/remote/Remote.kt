package dev.romirom.tivibro.remote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.ProgrammeGuide
import dev.romirom.tivibro.data.Programme
import dev.romirom.tivibro.data.SetupServer
import dev.romirom.tivibro.search.SearchIndexer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone remote and the MCP endpoint: process-wide, started with the app ([init]) and alive as long as the process,
 * so a bookmarked page or an AI assistant can reach the TV whether or not a screen of the app is open.
 *
 * Everything is reached through one persistent random [token] (Settings → «Телефон і асистент» shows it as a QR code
 * and can replace it): as the first path segment (`/<token>/…`, what the QR code and the MCP URL carry), as
 * `Authorization: Bearer <token>`, or as `?t=<token>`. Without it the server answers 404 (401 on /api and /mcp).
 *
 * The view model publishes what may be searched ([publish], [guideChanged]) and registers itself as the [target] that
 * plays; the HTTP threads read those snapshots and run playback on the main thread ([onMain]).
 */
object Remote {
    /**
     * What the phone and the assistant drive: the live view model, called on the main thread only. Every change goes
     * through the function the TV's own keys use, so it is saved, shown and undone exactly as if made with the remote.
     */
    interface Target {
        /** Plays [ch] live ([p] null) or programme [p] (from its start when [fromStart], else resumes a bookmark, of a
         *  programme still on air too: the remote asks for one on air only to resume it). */
        fun play(ch: Channel, p: Programme?, fromStart: Boolean)
        /** One of [KEYS]; null when done, else why it could not be (no programme before this one, say). */
        fun key(k: String): String?
        fun playing(): Playing?
        /** Seeks to [to] (epoch ms) or by [byMs] from the position on screen; an error message when it can't. */
        fun seek(byMs: Long?, to: Long?): String?
        /** The audio tracks, after switching to track [select] (0-based) when given. */
        fun audio(select: Int?): List<Track>
        fun setFav(ch: Channel, on: Boolean)
        /** Moves favourite [id] one step up ([dir] -1) or down past the next one the user can see. */
        fun moveFav(id: String, dir: Int)
        fun setHidden(ch: Channel, hide: Boolean)
        /** Switches a provider group on or off, as Settings → «Групи каналів» does. */
        fun setGroupShown(name: String, on: Boolean)
        /** «Продовжити перегляд» as Home shows it: unfinished bookmarks still in the archive, newest first. */
        fun bookmarks(): List<dev.romirom.tivibro.data.Mark>
        /** Removes the bookmark of [chId]'s programme starting at [start]; false when there is none. */
        fun forgetBookmark(chId: String, start: Long): Boolean
        /** The guide's programme of [ch] at [t] with its description (null while the guide still reads it) and the
         *  bookmark's position in it, if any. */
        fun programme(ch: Channel, t: Long): ProgInfo?
    }

    /** What plays. [pos] is the frame on screen (live is ~20 s behind the clock); [can] lists the actions that make sense
     *  now (restart, previous_programme, next_programme, live); [desc] is null while the guide still reads it. */
    class Playing(
        val ch: Channel, val live: Boolean, val paused: Boolean, val pos: Long, val p: Programme?,
        val canSeek: Boolean = false, val can: Set<String> = emptySet(), val audio: List<Track> = emptyList(), val desc: String? = null,
    )

    class Track(val index: Int, val name: String, val selected: Boolean)

    /** [desc]: null while the guide still reads it, empty when the programme has none. */
    class ProgInfo(val p: Programme, val desc: String?, val markPos: Long?)

    /**
     * The active playlist as the remote may see it: [shown] is what the user did not hide (ids), [favs] and [recent] in
     * the user's order, [hidden] the channels hidden one by one, [groupsOff] the provider groups switched off.
     */
    class State(
        val playlist: String?, val channels: List<Channel>, val shown: Set<String>, val favs: List<String>,
        val recent: List<String> = emptyList(), val hidden: Set<String> = emptySet(), val groupsOff: Set<String> = emptySet(),
    ) {
        val byId: Map<String, Channel> = channels.associateBy { it.id }
        val visible: List<Channel> = channels.filter { it.id in shown }
        val favSet: Set<String> = favs.toHashSet()
        /** [visible] by group, in playlist order: what the phone browses ([Browse]). Built on first use, once. */
        val groups: Map<String, List<Channel>> by lazy { visible.groupByTo(LinkedHashMap()) { it.group } }
        /** Every provider group, hidden ones included, with its size: what the phone's «Приховані» lists. */
        val allGroups: Map<String, Int> by lazy { channels.groupingBy { it.group }.eachCountTo(LinkedHashMap()) }
    }

    private lateinit var app: Context
    val http = Http(::route, mask = { t -> token.let { if (it.isEmpty()) t else t.replace(it, it.take(4) + "…") } })
    val indexer by lazy { SearchIndexer(java.io.File(app.noBackupFilesDir, "search")) }
    /** The QR setup page, while a screen shows it. */
    @Volatile var setup: SetupServer? = null
    @Volatile var target: Target? = null
    /** The activity is started (visible): play requests go straight to [target] rather than through an intent. */
    @Volatile var foreground = false
    @Volatile var state = State(null, emptyList(), emptySet(), emptyList()); private set
    private val main = Handler(Looper.getMainLooper())
    private val api by lazy { RemoteApi(app) }
    private val mcp by lazy { Mcp(api) }

    @Volatile var token: String = ""; private set

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val sp = app.getSharedPreferences("tivibro", Context.MODE_PRIVATE)
        token = sp.getString(KEY, null)?.takeIf { it.length >= 16 } ?: newToken().also { sp.edit().putString(KEY, it).apply() }
        http.start()
        advertise()
    }

    /** Settings → «Нове посилання»: the old page bookmark and MCP URL stop working at once. */
    fun resetToken() {
        token = newToken()
        app.getSharedPreferences("tivibro", Context.MODE_PRIVATE).edit().putString(KEY, token).apply()
    }

    fun publish(
        playlist: String?, channels: List<Channel>, shown: List<Channel>, favs: List<String>,
        recent: List<String>, hidden: Set<String>, groupsOff: Set<String>,
    ) {
        state = State(playlist, channels, shown.mapTo(HashSet(shown.size * 2)) { it.id }, favs, recent, hidden, groupsOff)
    }

    fun guideChanged(channels: List<Channel>, guide: ProgrammeGuide, key: String) = indexer.request(channels, guide, key)

    /** `http://<tv ip>:<port>`; null without a network. */
    fun base(): String? = Http.localIp()?.let { "http://$it:${http.port}" }
    fun pageUrl(): String? = base()?.let { "$it/$token" }
    fun mcpUrl(): String? = base()?.let { "$it/$token/mcp" }

    /** Runs [f] on the main thread and waits for it (up to [timeoutMs]); null on timeout or failure. */
    fun <T> onMain(timeoutMs: Long = 3000, f: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(f).getOrNull()
        var out: T? = null
        val done = CountDownLatch(1)
        main.post { try { out = f() } catch (e: Throwable) { Log.w("tivibro", "remote: main-thread call failed", e) } finally { done.countDown() } }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return out
    }

    // ------------------------------------------------------------------ routing

    private fun route(r: Http.Request): Http.Response {
        setup?.handle(r)?.let { return it }
        val tok = token
        val path = r.path
        val rest: String? = when {
            tok.isEmpty() -> null
            path == "/$tok" || path.startsWith("/$tok/") -> path.substring(tok.length + 1).ifEmpty { "/" }
            same(r.header("authorization")?.removePrefix("Bearer ")?.trim(), tok) || same(r.query["t"], tok) -> path
            else -> null
        }
        if (rest == null) {
            return if (path.startsWith("/api") || path.startsWith("/mcp")) Http.Response.json("""{"error":"unauthorized"}""", 401)
            else Http.Response.empty(404)
        }
        // DNS rebinding: a browser page of another site must not reach this (it would also need the token, but still)
        val origin = r.header("origin")
        if (origin != null && origin != "null" && origin.substringAfter("://").substringBefore('/') != r.header("host")) {
            return Http.Response.json("""{"error":"forbidden origin"}""", 403)
        }
        return when {
            rest == "/" || rest == "/index.html" -> Http.Response.html(RemotePage.html(tok))
            rest == "/manifest.webmanifest" -> Http.Response(200, "application/manifest+json", RemotePage.manifest(tok).toByteArray())
            rest == "/icon.png" -> icon()?.let { Http.Response(200, "image/png", it, mapOf("Cache-Control" to "max-age=604800")) } ?: Http.Response.empty(404)
            rest == "/mcp" -> mcp.handle(r.method, r::header, r.text)
            rest.startsWith("/api/") -> api.handle(r.method, rest.removePrefix("/api/"), r.query, r.text)
            else -> Http.Response.empty(404)
        }
    }

    /** The home screen icon of «Add to Home screen», read once from the APK's assets. */
    private val iconBytes by lazy { runCatching { app.assets.open("remote/icon.png").use { it.readBytes() } }.getOrNull() }
    private fun icon(): ByteArray? = iconBytes

    private fun same(a: String?, b: String) = a != null && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    // ------------------------------------------------------------------ discovery

    /** `_tivibro._tcp` on the LAN (mDNS/DNS-SD), so a client can find the TV without typing its address. */
    private fun advertise() = runCatching {
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply { serviceName = "TiViBro"; serviceType = "_tivibro._tcp"; port = http.port }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i("tivibro", "remote: advertised as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w("tivibro", "remote: nsd failed $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        })
    }.onFailure { Log.w("tivibro", "remote: nsd", it) }

    private const val KEY = "remoteToken"

    /** What [Target.key] takes: the remote's own keys, and the player panel's buttons. */
    val KEYS = listOf("pause", "resume", "playpause", "next", "prev", "live", "restart", "previous_programme", "next_programme")

    private fun newToken(): String {
        val b = ByteArray(12)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }
}

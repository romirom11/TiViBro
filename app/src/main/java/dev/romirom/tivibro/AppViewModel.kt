package dev.romirom.tivibro

import android.app.ActivityManager
import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import dev.romirom.tivibro.data.Catchup
import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.DAY
import dev.romirom.tivibro.data.Db
import dev.romirom.tivibro.data.Guide
import dev.romirom.tivibro.data.GuideMode
import dev.romirom.tivibro.data.GuideSync
import dev.romirom.tivibro.data.Group
import dev.romirom.tivibro.data.HOUR
import dev.romirom.tivibro.data.MIN
import dev.romirom.tivibro.data.Mark
import dev.romirom.tivibro.data.Pacer
import dev.romirom.tivibro.data.PlaylistInfo
import dev.romirom.tivibro.data.PlaylistRepo
import dev.romirom.tivibro.data.Prefs
import dev.romirom.tivibro.data.Programme
import dev.romirom.tivibro.data.ProgrammeGuide
import dev.romirom.tivibro.data.SetupServer
import dev.romirom.tivibro.data.XmltvGuide
import dev.romirom.tivibro.data.inArchive
import dev.romirom.tivibro.data.now
import dev.romirom.tivibro.home.HomeCard
import dev.romirom.tivibro.home.HomeIndex
import dev.romirom.tivibro.home.HomeKind
import dev.romirom.tivibro.home.HomePicks
import dev.romirom.tivibro.home.HomeRow
import dev.romirom.tivibro.home.HomeRows
import dev.romirom.tivibro.home.Filler
import dev.romirom.tivibro.home.Slot
import dev.romirom.tivibro.tmdb.Tmdb
import dev.romirom.tivibro.tmdb.TmdbInfo
import dev.romirom.tivibro.tvhome.DeepLink
import dev.romirom.tivibro.tvhome.TvHome
import dev.romirom.tivibro.ui.channelsWord
import dev.romirom.tivibro.ui.dayWord
import dev.romirom.tivibro.ui.hm
import dev.romirom.tivibro.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel as Wake
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Mode { LIVE, ARCHIVE }
/** What covers the video: nothing, the channel list (with the programme view), or Home. */
enum class Layer { NONE, LIST, HOME }
enum class Focus { GROUPS, CHANNELS, DAYS, PROGS }
/** Layers above the list / video, stacked: the menu stays visible under a settings sheet opened from it. */
enum class Sheet { MENU, SETTINGS, GROUPS, HIDDEN, PLAYLISTS, PLAYLIST, GUIDE, LINK, LANGUAGE, HOME, HOME_SOURCES, HOME_GROUP }
/** Rows of «Головна сторінка» (Sheet.HOME). */
enum class HomeSetting { SOURCES, TMDB, TMDB_KEY }
enum class MenuItem { HOME, GROUPS, GUIDE, SETTINGS, UPDATE, EXIT }
enum class SettingsRow { PLAYLISTS, GROUPS, HIDDEN, GUIDE, HOME, STARTUP, LANGUAGE, SCALE, UPDATE, ABOUT }
/** Actions on one playlist (Sheet.PLAYLIST), top to bottom. */
enum class PlaylistAction { USE, REFRESH, RENAME, DELETE }
/** Rows of «Програма передач» (Sheet.GUIDE): the three sources, then the refresh. */
enum class GuideRow { AUTO, PLAYLIST, CUSTOM, REFRESH }
/** What the QR / LAN page (Sheet.LINK) is collecting. */
enum class LinkPurpose { ADD_PLAYLIST, GUIDE_URL, RENAME, TMDB_KEY }
/** Where the active playlist's guide is read from right now. */
sealed class GuideSource {
    data object Segmented : GuideSource()
    data class Xmltv(val url: String) : GuideSource()
}
enum class CtxItem { FAV, HIDE }
/** The «Продовжити перегляд» context menu (long OK on a bookmark or a watched programme), top to bottom. */
enum class MarkAction { FROM_START, FORGET, DONE }
/** The player control panel's button row, left to right. */
enum class OsdButton { RESTART, PREV, NEXT, LIVE, AUDIO }
enum class Key { UP, DOWN, LEFT, RIGHT, OK, BACK, CHUP, CHDOWN, PLAYPAUSE, RED, GREEN, YELLOW, BLUE, INFO, MENU, D0, D1, D2, D3, D4, D5, D6, D7, D8, D9 }

/**
 * All app state and remote-control logic. Mirrors prototype/index.html: one focus owner, every key routed
 * by the current layer, so the remote behaves the same everywhere.
 */
@OptIn(UnstableApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val prefs = Prefs(app)
    private val db = Db(app)
    /** Every database call, in order, off the main thread (the first open may migrate a 380 MB guide away). */
    private val dbIo = Dispatchers.IO.limitedParallelism(1)
    private val repo = PlaylistRepo(app, http, prefs)
    private val segGuide = Guide(File(app.noBackupFilesDir, "epg"), ::onGuideChanged)
    private val xmlGuide = XmltvGuide(File(app.noBackupFilesDir, "xmltv"), ::onGuideChanged)
    /** The guide of the active playlist's source ([guideSource]). */
    private var guide: ProgrammeGuide = segGuide
    // HTTP/1.1 for the guide: over HTTP/2 epg.team resets streams (PROTOCOL_ERROR) when the reader is slower than
    // the server sends, which a throttled, pausing download always is
    private val guideSync = GuideSync(segGuide, http.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build(), ::guideHold) { s ->
        viewModelScope.launch(Dispatchers.Main) { status = s }
    }
    private val guideThread = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST); r.run() }, "guide-sync")
    }.asCoroutineDispatcher()
    private val guideWake = Wake<Unit>(Wake.CONFLATED)
    @Volatile private var guideSyncing = false
    @Volatile private var guideForced = false
    /** [guideSource] and [channels] as the download thread sees them. */
    @Volatile private var guideSourceNow: GuideSource = GuideSource.Segmented
    @Volatile private var guideChannels: List<Channel> = emptyList()
    /** Why the last guide pass of the current source failed; null after one that worked. */
    var guideError by mutableStateOf<String?>(null); private set
    @Volatile private var lastKeyAt = 0L
    @Volatile private var bufferingSince = 0L // 0: not buffering

    /** The player's client: an archive the server doesn't have comes back as a redirect to a stub, or as a playlist of
     *  stub segments; both are turned into a 410 marked [NO_ARCHIVE] so that the player stops instead of playing it. */
    private val playerHttp = http.newBuilder().addNetworkInterceptor(Interceptor { chain ->
        val r = chain.proceed(chain.request())
        val stub = when {
            r.isRedirect -> STUB.containsMatchIn(r.header("Location").orEmpty())
            r.isSuccessful && chain.request().url.encodedPath.endsWith(".m3u8") -> r.peekBody(16 * 1024).string().contains("stub_noarch")
            else -> false
        }
        if (!stub) return@Interceptor r
        r.close()
        r.newBuilder().code(410).message("No archive").header(NO_ARCHIVE, "1").removeHeader("Location").body("".toResponseBody(null)).build()
    }).build()

    val player: ExoPlayer = ExoPlayer.Builder(
        app,
        DefaultRenderersFactory(app).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    )
        .setLoadControl(loadControl(app))
        .setMediaSourceFactory(
            // The provider's H.264 has no IDR frames (I-slices + recovery-point SEI only): without this flag the TS
            // extractor never sees a keyframe, the video renderer never starts and playback hangs in BUFFERING.
            HlsMediaSource.Factory(OkHttpDataSource.Factory(playerHttp).setUserAgent(USER_AGENT))
                .setExtractorFactory(DefaultHlsExtractorFactory(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES, true)),
        )
        .build()

    // ---------- data ----------
    /** The active playlist; null until the first one is set up. */
    var playlist by mutableStateOf(repo.get(prefs.activePlaylist) ?: repo.all().firstOrNull()); private set
    val playlistUrl get() = playlist?.url
    /** Every saved playlist, for «Плейлисти». */
    var playlists by mutableStateOf(repo.all()); private set
    /** Playlist being downloaded by a user action (add, refresh, switch): its id. */
    var loadingPlaylist by mutableStateOf<String?>(null); private set
    var guideSource by mutableStateOf<GuideSource>(GuideSource.Segmented); private set
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var groups by mutableStateOf<List<Group>>(emptyList()); private set
    var status by mutableStateOf<String?>(null); private set
    var guideVersion by mutableIntStateOf(0); private set
    private var byId: Map<String, Channel> = emptyMap()
    /** Every provider group in playlist order, hidden ones included (the «Групи каналів» sheet lists them all). */
    var allGroups by mutableStateOf<List<Group>>(emptyList()); private set
    private var groupByName: Map<String, Group> = emptyMap()
    private var providerGroups: List<Group> = emptyList()
    /** Channels the user can see: not hidden, in a shown group. «Усі канали», zapping and numbers go by this. */
    private var shown: List<Channel> = emptyList()
    private var hidden = emptySet<String>()
    /** Bumped on every show/hide change: the settings live in Prefs, so the sheets re-read them on this. */
    var cfgVersion by mutableIntStateOf(0); private set
    /** Settings → «Масштаб інтерфейсу», percent of the design size: one of [UI_SCALES]. */
    var uiScale by mutableIntStateOf(prefs.uiScale.takeIf { it in UI_SCALES } ?: 100); private set
    private var favs = mutableListOf<String>()
    /** Channels whose archive the server turned out not to have (it answered with its stub), this session. */
    private val noArchive = HashSet<String>()
    private val marks = LinkedHashMap<String, Mark>()
    /** What the database holds (and what was saved since), by [markKey]: [marks] is this with the playlist's channels. */
    private val markRows = LinkedHashMap<String, Db.MarkRow>()
    /** Bumped when a bookmark is added, removed or marked watched: what shows them reads it to recompose. */
    var markVersion by mutableIntStateOf(0); private set

    /** «Нещодавні»: the last channels tuned, most recent first (ids, per playlist). */
    private var recent = mutableListOf<String>()

    // ---------- playback ----------
    var cur by mutableStateOf<Channel?>(null); private set
    var mode by mutableStateOf(Mode.LIVE); private set
    var paused by mutableStateOf(false); private set
    private var archiveFrom = 0L
    private var archiveTo = 0L // OPEN = a growing `-now` window that reaches the broadcast
    private var pausedLiveAt: Long? = null
    private var pendingAbs: Long? = null
    var buffering by mutableStateOf(false); private set

    // ---------- overlays ----------
    var tick by mutableLongStateOf(now()); private set
    var layer by mutableStateOf(Layer.NONE); private set
    var banner by mutableStateOf(false); private set
    /** The player control panel. Focus is on the timeline, or on [osdBtn] in the button row. */
    var osd by mutableStateOf(false); private set
    var osdOnButtons by mutableStateOf(false); private set
    var osdBtn by mutableStateOf(OsdButton.RESTART); private set
    var audioTracks by mutableIntStateOf(0); private set
    private var seekRun = 0
    private var lastSeekAt = 0L
    var seekAcc by mutableLongStateOf(0L); private set
    var toast by mutableStateOf<String?>(null); private set
    var numBuf by mutableStateOf(""); private set

    // ---------- list ----------
    var focus by mutableStateOf(Focus.CHANNELS); private set
    var groupIdx by mutableIntStateOf(0); private set
    var chIdx by mutableIntStateOf(0); private set
    var dayIdx by mutableIntStateOf(0); private set
    var progIdx by mutableIntStateOf(0); private set
    private var groupId = "all"

    // ---------- Home ----------
    /** The rows on Home, built when it opens and merged with what arrives while it is open (see [HomeRows.merge]). */
    var homeRows by mutableStateOf<List<HomeRow>>(emptyList()); private set
    var homeRowIdx by mutableIntStateOf(0); private set
    var homeColIdx by mutableIntStateOf(0); private set
    /** The card each row was left on, by row kind: coming back to a row lands where it was. */
    private val homeCols = HashMap<HomeKind, Int>()
    /** Settings → «При запуску»; Home also opens on a playlist's first start, when it has no last channel yet. */
    var startHome by mutableStateOf(prefs.startHome); private set
    private var startupPending = true
    /** A home screen link waiting for its playlist's channels (see [openDeepLink]). */
    private var pendingLink: DeepLink? = null
    /** Home sources of the active playlist: whole groups by name, and picked channels of other groups. */
    private var homeGroups = emptySet<String>()
    private val homeChannels = HashMap<String, Set<String>>()
    /** The group Sheet.HOME_GROUP lists. */
    var homeGroup by mutableStateOf<Group?>(null); private set
    /** Now/next of the Home sources, read from the guide in the background (see [HomeIndex]). */
    private val homeIndex: HomeIndex = HomeIndex(viewModelScope) {
        homeLoading = homeIndex.building && homeIndex.slots.isEmpty()
        refreshHome()
        if (!homeIndex.building) syncTvHome()
    }
    // ---------- TMDB
    /** «Свій ключ TMDB», else the one built in (BuildConfig.TMDB_TOKEN); null: none, TMDB stays out of sight. */
    var tmdbOwnKey by mutableStateOf(prefs.tmdbKey?.takeIf { it.isNotBlank() }); private set
    var tmdbOn by mutableStateOf(prefs.tmdbEnabled); private set
    val tmdbToken: String? get() = tmdbOwnKey ?: BuildConfig.TMDB_TOKEN.takeIf { it.isNotBlank() }
    /** TMDB is asked: there is a key and the switch is on. */
    val tmdbActive: Boolean get() = tmdbOn && tmdbToken != null
    /** What the lookup thread sends: the key while [tmdbActive], else nothing (and nothing is asked). */
    @Volatile private var tmdbKeyNow: String? = null
    var tmdbVersion by mutableIntStateOf(0); private set
    private val tmdb = Tmdb(http, File(app.cacheDir, "tmdb"), { tmdbKeyNow }) { tmdbVersion++; refreshHome() }

    /** Home's guide index is being read for the first time: the rows it feeds are still to come. */
    var homeLoading by mutableStateOf(false); private set
    /** Sources are picked, nothing is loading, and still nothing to show from them. */
    val homeEmpty: Boolean get() { cfgVersion; return !homeLoading && homeRows.none { it.kind != HomeKind.CONTINUE && it.kind != HomeKind.WATCHING } && (homeGroups.isNotEmpty() || homeChannels.isNotEmpty()) }

    // ---------- menu, settings sheets, channel context menu ----------
    var sheets by mutableStateOf<List<Sheet>>(emptyList()); private set
    var menuIdx by mutableIntStateOf(0); private set
    var rowIdx by mutableIntStateOf(0); private set // row of the settings sheet on top
    var ctx by mutableStateOf<Channel?>(null); private set
    var ctxIdx by mutableIntStateOf(0); private set
    /** The bookmark the «Продовжити перегляд» context menu acts on. */
    var markCtx by mutableStateOf<Mark?>(null); private set
    /** A bookmark the user just removed or marked watched while it plays: the running playback must not write it back
     *  until the user touches the playback again ([showOsd]) or starts something else ([playArchive]). */
    private var markLock: String? = null

    /** Self-update from GitHub Releases. */
    val updater = Updater(app, http, viewModelScope).also { it.say = { msg -> showToast(msg) } }

    val setupServer = SetupServer { link -> viewModelScope.launch { usePlaylist(link) } }
    /** What Sheet.LINK is collecting, and for which playlist. */
    var linkPurpose by mutableStateOf(LinkPurpose.ADD_PLAYLIST); private set
    private var linkTarget: String? = null
    /** The playlist Sheet.PLAYLIST acts on. */
    var sheetPlaylist by mutableStateOf<PlaylistInfo?>(null); private set
    private var deleteArmed = 0L
    var onExit: () -> Unit = {}
    /** Recreates the activity: a new language takes effect at once. */
    var onRecreate: () -> Unit = {}

    private var bannerJob: Job? = null
    private var osdJob: Job? = null
    private var toastJob: Job? = null
    private var numJob: Job? = null
    private var seekJob: Job? = null
    private var guideJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                bufferingSince = if (!buffering) 0L else bufferingSince.takeIf { it != 0L } ?: SystemClock.elapsedRealtime()
                if (state == Player.STATE_READY) pendingAbs = null
            }

            /** The archive playlist starts on the first segment boundary after `from` (window start comes from
             *  PROGRAM-DATE-TIME), so the offset given to setMediaItem() can land up to a segment late: re-aim. */
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val t = pendingAbs ?: return
                if (mode != Mode.ARCHIVE || timeline.isEmpty) return
                val target = max(0L, t - windowBase())
                if (abs(target - player.currentPosition) > 1000) player.seekTo(target)
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioTracks = audioGroups().size
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (isNoArchive(error)) { onNoArchive(); return }
                if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return // fell behind the live window: rejoin live
                }
                showToast(str(R.string.err_play, error.errorCodeName.removePrefix("ERROR_CODE_").lowercase()))
            }
        })
        if (BuildConfig.DEBUG) player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
        viewModelScope.launch(dbIo) {
            runCatching { db.dropOldGuide() }.onFailure { Log.w(TAG, "db: quick migration failed", it) }
            Log.i(TAG, "db ${getApplication<Application>().getDatabasePath("tivibro.db").length() / 1024} KB")
        }
        tmdb.lang = Tmdb.tmdbLang(L10n.locale.language)
        tmdbKeyNow = if (tmdbActive) tmdbToken else null
        playlist?.let { openPlaylist(it, download = true) }
        Updater.onInstallError = { showToast(str(R.string.update_failed, it)) }
        updater.checkIfDue()
        viewModelScope.launch {
            while (true) {
                delay(1000)
                tick = now()
                onTick()
            }
        }
    }

    // ============================================================ loading

    /** First run, the intent extra: this link becomes the active playlist (added if new). */
    fun usePlaylist(url: String) {
        setupServer.stop()
        val info = repo.add(url)
        playlists = repo.all()
        openPlaylist(info, download = true)
    }

    private var openJob: Job? = null

    /** Makes [info] the active playlist: its own favourites, hidden channels, groups and bookmarks, its guide. */
    private fun openPlaylist(info: PlaylistInfo, download: Boolean) {
        openJob?.cancel()
        if (playlist?.id != info.id) saveMark()
        prefs.activePlaylist = info.id
        prefs.scope = info.id
        playlist = info
        favs = prefs.favorites.toMutableList()
        recent = prefs.recent.toMutableList()
        hidden = prefs.hiddenChannels
        homeGroups = prefs.homeGroups
        homeChannels.clear()
        groupId = prefs.lastGroup ?: "all"
        markRows.clear(); marks.clear(); noArchive.clear()
        homeIndex.clear()
        channels = emptyList(); guideChannels = emptyList(); byId = emptyMap()
        allGroups = emptyList(); groupByName = emptyMap(); providerGroups = emptyList(); shown = emptyList(); groups = emptyList()
        cur = null
        mode = Mode.LIVE; paused = false; pausedLiveAt = null; pendingAbs = null
        layer = Layer.NONE; osd = false; banner = false
        player.stop(); player.clearMediaItems()
        applyGuideSource()
        cfgVersion++
        val id = info.id
        openJob = viewModelScope.launch {
            val rows = withContext(dbIo) { db.marks(id) }
            for (r in rows) markRows.getOrPut(r.ch + ":" + r.p.start) { r }
            load(download)
        }
    }

    private suspend fun load(download: Boolean) {
        val id = playlist?.id ?: return
        val cached = withContext(Dispatchers.IO) { runCatching { repo.cached(id) }.getOrNull() }
        if (cached != null && cached.channels.isNotEmpty()) applyChannels(cached.channels)
        if (!download && channels.isNotEmpty()) { applyGuideSource(); refreshGuide(force = false); return }
        status = if (channels.isEmpty()) str(R.string.loading_channels) else null
        val fresh = withContext(Dispatchers.IO) { runCatching { repo.download(id) } }
        if (playlist?.id != id) return
        playlists = repo.all()
        repo.get(id)?.let { playlist = it }
        // usually identical to the cached copy: re-applying it would rebuild every list on the main thread for nothing
        fresh.onSuccess { if (it.channels != channels) applyChannels(it.channels) }.onFailure {
            if (channels.isEmpty()) status = str(R.string.playlist_load_failed, it.message.orEmpty())
        }
        if (channels.isNotEmpty()) {
            status = null
            applyGuideSource()
            refreshGuide(force = false)
        }
    }

    // ============================================================ playlists («Плейлисти»)

    fun openPlaylistSheet(p: PlaylistInfo) {
        sheetPlaylist = p
        deleteArmed = 0
        openSheet(Sheet.PLAYLIST)
    }

    /** Downloads [url] first: only a playlist that loads is added (and switched to). */
    private fun addPlaylist(url: String) {
        if (loadingPlaylist != null) return
        val existing = repo.all().firstOrNull { it.url == url }
        val info = existing ?: repo.add(url)
        loadingPlaylist = info.id
        playlists = repo.all()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { repo.download(info.id) } }
            loadingPlaylist = null
            r.onSuccess { parsed ->
                playlists = repo.all()
                closeSheets()
                openPlaylist(repo.get(info.id) ?: return@launch, download = false)
                showToast(str(if (existing != null) R.string.playlist_exists else R.string.playlist_added, channelsWord(parsed.channels.size)))
            }.onFailure {
                if (existing == null) repo.remove(info.id)
                playlists = repo.all()
                showToast(str(R.string.playlist_load_failed, it.message.orEmpty()))
            }
        }
    }

    private fun playlistAction(a: PlaylistAction) {
        val p = sheetPlaylist?.let { repo.get(it.id) } ?: return
        if (a != PlaylistAction.DELETE) deleteArmed = 0
        when (a) {
            PlaylistAction.USE -> {
                if (p.id == playlist?.id) { showToast(str(R.string.playlist_already_open)); return }
                closeSheets()
                openPlaylist(p, download = true)
                showToast(str(R.string.playlist_switched, p.title))
            }
            PlaylistAction.REFRESH -> {
                if (loadingPlaylist != null) return
                loadingPlaylist = p.id
                viewModelScope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { repo.download(p.id) } }
                    loadingPlaylist = null
                    playlists = repo.all()
                    sheetPlaylist = repo.get(p.id)
                    r.onSuccess {
                        if (p.id == playlist?.id) {
                            playlist = repo.get(p.id)
                            if (it.channels != channels) applyChannels(it.channels)
                            applyGuideSource()
                        }
                        showToast(str(R.string.playlist_refreshed, channelsWord(it.channels.size)))
                    }.onFailure { showToast(str(R.string.refresh_failed, it.message.orEmpty())) }
                }
            }
            PlaylistAction.RENAME -> openLink(LinkPurpose.RENAME, p)
            PlaylistAction.DELETE -> {
                if (now() - deleteArmed > 3000) { deleteArmed = now(); showToast(str(R.string.delete_confirm, p.title)); return }
                deleteArmed = 0
                deletePlaylist(p)
            }
        }
    }

    private fun deletePlaylist(p: PlaylistInfo) {
        repo.remove(p.id)
        prefs.forget(p.id)
        viewModelScope.launch(dbIo) { db.deleteMarks(p.id) }
        playlists = repo.all()
        showToast(str(R.string.playlist_deleted, p.title))
        if (p.id != playlist?.id) { pop(); return }
        closeSheets()
        val next = playlists.firstOrNull()
        if (next != null) { openPlaylist(next, download = true); return }
        // the last one: back to the first-run screen
        openJob?.cancel()
        player.stop(); player.clearMediaItems()
        prefs.activePlaylist = null
        playlist = null
        channels = emptyList(); groups = emptyList(); allGroups = emptyList(); cur = null
        applyGuideSource()
    }

    /** Sheet.LINK: the QR code of the LAN page, set up to collect [purpose]. */
    private fun openLink(purpose: LinkPurpose, target: PlaylistInfo?) {
        linkPurpose = purpose
        linkTarget = target?.id
        setupServer.page = when (purpose) {
            LinkPurpose.ADD_PLAYLIST -> SetupServer.playlistPage()
            LinkPurpose.GUIDE_URL -> SetupServer.Page(
                str(R.string.page_guide_title), str(R.string.page_guide_hint), str(R.string.page_send),
                value = target?.guideUrl.orEmpty(),
            )
            LinkPurpose.RENAME -> SetupServer.Page(
                str(R.string.page_name_title), str(R.string.page_name_hint), str(R.string.page_save), link = false, value = target?.title.orEmpty(),
            )
            LinkPurpose.TMDB_KEY -> SetupServer.Page(str(R.string.page_tmdb_title), str(R.string.page_tmdb_hint), str(R.string.page_save), link = false)
        }
        setupServer.onSubmit = { v -> viewModelScope.launch { onLinkAnswer(purpose, v) } }
        setupServer.start()
        openSheet(Sheet.LINK)
    }

    private fun onLinkAnswer(purpose: LinkPurpose, v: String) {
        if (sheets.lastOrNull() != Sheet.LINK || linkPurpose != purpose) return
        val id = linkTarget
        when (purpose) {
            LinkPurpose.ADD_PLAYLIST -> addPlaylist(v)
            LinkPurpose.RENAME -> {
                val p = repo.update(id ?: return) { it.copy(name = v.trim().take(60)) } ?: return
                afterPlaylistEdit(p)
                pop()
                showToast(str(R.string.renamed, p.title))
            }
            LinkPurpose.TMDB_KEY -> setTmdbKey(v)
            LinkPurpose.GUIDE_URL -> {
                val p = repo.update(id ?: return) { it.copy(guideMode = GuideMode.CUSTOM, guideUrl = v.trim()) } ?: return
                afterPlaylistEdit(p)
                pop()
                showToast(str(R.string.guide_custom_set))
                refreshGuide(force = true)
            }
        }
    }

    private fun afterPlaylistEdit(p: PlaylistInfo) {
        playlists = repo.all()
        if (sheetPlaylist?.id == p.id) sheetPlaylist = p
        if (playlist?.id == p.id) { playlist = p; applyGuideSource() }
    }

    // ============================================================ guide source («Програма передач»)

    private fun sourceFor(p: PlaylistInfo?): GuideSource {
        p ?: return GuideSource.Segmented
        val tvg = p.tvgUrl
        return when (p.guideMode) {
            GuideMode.AUTO -> if (tvg == null || isEpgTeam(tvg)) GuideSource.Segmented else GuideSource.Xmltv(tvg)
            GuideMode.PLAYLIST -> tvg?.let { GuideSource.Xmltv(it) } ?: GuideSource.Segmented
            GuideMode.CUSTOM -> p.guideUrl?.let { GuideSource.Xmltv(it) } ?: GuideSource.Segmented
        }
    }

    /** Points [guide] at the active playlist's source; a change wakes the download loop for it. */
    private fun applyGuideSource() {
        val s = sourceFor(playlist)
        guideSourceNow = s
        if (s == guideSource) return
        guideSource = s
        guide = if (s is GuideSource.Xmltv) xmlGuide else segGuide
        homeIndex.clear() // read again from the new guide when Home next needs it
        guideError = null
        status = null
        guideVersion++
        if (s is GuideSource.Segmented && repo.all().none { sourceFor(it) is GuideSource.Xmltv }) xmlGuide.clear()
        guideWake.trySend(Unit)
    }

    private fun setGuideMode(m: GuideMode) {
        val p = playlist ?: return
        if (m == GuideMode.CUSTOM) { openLink(LinkPurpose.GUIDE_URL, p); return }
        if (m == GuideMode.PLAYLIST && p.tvgUrl == null) { showToast(str(R.string.guide_no_tvg)); return }
        if (p.guideMode == m) return
        afterPlaylistEdit(repo.update(p.id) { it.copy(guideMode = m) } ?: return)
        showToast(str(if (m == GuideMode.AUTO) R.string.guide_auto_set else R.string.guide_playlist_set))
    }

    private fun applyChannels(list: List<Channel>) {
        val first = channels.isEmpty()
        channels = list
        guideChannels = list
        // the XMLTV import keeps only these channels: a pass that ran before they were known did nothing
        if (guideSourceNow is GuideSource.Xmltv) guideWake.trySend(Unit)
        byId = list.associateBy { it.id }
        // a fresh download replaces the cached copy the playing channel came from: the lists find it by equality
        cur = cur?.let { byId[it.id] ?: it }
        allGroups = list.groupBy { it.group }.map { (name, chs) -> Group("g:$name", name, chs) }
        groupByName = allGroups.associateBy { it.name }
        homeChannels.clear()
        for (g in allGroups) prefs.homeChannels(g.name).takeIf { it.isNotEmpty() }?.let { homeChannels[g.name] = it }
        marksFromDb()
        applyVisibility()
        if (first) {
            val last = prefs.lastChannel?.let(byId::get)?.takeIf(::isShown)
            val link = pendingLink
            // a link to a channel of this playlist tunes it itself; anything else plays the last channel under it
            if (!(link is DeepLink.Live && link.channel in byId) && !(link is DeepLink.Archive && link.channel in byId)) tune(last ?: shown.firstOrNull() ?: list.first())
            when {
                link != null -> applyDeepLink()
                // the app's start (or a playlist's very first one): Home when asked for, or when there is no last channel
                (startupPending && startHome) || last == null -> openHome()
            }
            startupPending = false
            // the sources' now/next for the home screen's channel, once the first channel has had its start
            viewModelScope.launch { delay(20_000); if (channels === list) ensureHomeIndex() }
        }
    }

    /** Re-derives what is visible after the playlist or a show/hide setting changed. */
    private fun applyVisibility() {
        val groupOn = allGroups.filter(::groupShown).mapTo(HashSet()) { it.name }
        shown = channels.filter { it.id !in hidden && it.group in groupOn }
        providerGroups = allGroups.filter { it.name in groupOn }
            .map { g -> g.copy(channels = g.channels.filter { it.id !in hidden }) }
            .filter { it.channels.isNotEmpty() }
        rebuildGroups()
        if (layer == Layer.LIST) {
            syncGroup()
            chIdx = chIdx.coerceIn(0, max(0, (currentGroup()?.channels?.size ?: 1) - 1))
        }
        cfgVersion++
    }

    private fun rebuildGroups() {
        val g = ArrayList<Group>()
        val cont = resumeList()
        if (cont.isNotEmpty()) g += Group("cont", str(R.string.group_cont), cont.map { it.ch }, cont)
        g += Group("fav", str(R.string.group_fav), favs.mapNotNull(byId::get).filter(::isShown))
        g += Group("recent", str(R.string.group_recent), recent.mapNotNull(byId::get).filter(::isShown))
        g += Group("all", str(R.string.group_all), shown)
        g += providerGroups
        groups = g
    }

    // ============================================================ what is shown

    /** Every group is shown until the user says otherwise, except the adult one. */
    private fun defaultShown(g: Group) = !ADULT.containsMatchIn(g.name)

    fun groupShown(g: Group) = prefs.groupShown(g.name, defaultShown(g))
    private fun groupOf(ch: Channel) = groupByName[ch.group]

    fun isShown(ch: Channel) = ch.id !in hidden && groupOf(ch)?.let(::groupShown) != false
    fun isHidden(ch: Channel) = ch.id in hidden

    fun hiddenChannels(): List<Channel> = channels.filter { it.id in hidden }

    private fun toggleGroupShown(g: Group) {
        prefs.setGroupShown(g.name, !groupShown(g))
        applyVisibility()
    }

    private fun setHidden(ch: Channel, hide: Boolean) {
        hidden = if (hide) hidden + ch.id else hidden - ch.id
        prefs.hiddenChannels = hidden
        applyVisibility()
    }

    // ============================================================ guide download

    /** When the current source last finished a pass. */
    fun guideAt() = if (guideSource is GuideSource.Xmltv) xmlGuide.meta?.at ?: 0L else prefs.guideAt

    /** How many channels the XMLTV import kept, for the sheet; null for the segmented guide. */
    fun xmltvChannels(): Int? = if (guideSource is GuideSource.Xmltv) xmlGuide.meta?.channels else null

    /**
     * The guide download loop, one pass at a time on a lowest-priority thread of its own. epg.team's segmented guide
     * ([GuideSync]) is checked on start, every 3 h and just after UTC midnight (when the days shift); an XMLTV source
     * is imported again when the last import is 12 h old, is of another source or playlist, or when [force]d. A source
     * change ([applyGuideSource]) wakes it at once.
     */
    fun refreshGuide(force: Boolean) {
        if (force) {
            if (guideSyncing) { showToast(str(R.string.guide_busy)); return }
            showToast(str(R.string.guide_updating))
            guideForced = true
            guideWake.trySend(Unit)
        }
        if (guideJob != null) return
        guideJob = viewModelScope.launch(guideThread) {
            while (true) {
                guideWake.tryReceive() // a wake that came in before this pass started is this pass
                val forced = guideForced
                guideForced = false
                val src = guideSourceNow
                guideSyncing = true
                val r = runCatching { guidePass(src, forced) }
                guideSyncing = false
                withContext(Dispatchers.Main) {
                    if (src != guideSource) return@withContext
                    r.onSuccess { msg -> guideError = null; if (forced && msg != null) showToast(msg) }.onFailure { e ->
                        Log.e(TAG, "guide sync failed", e)
                        guideError = e.message ?: e.javaClass.simpleName
                        status = null
                        if (forced) showToast(str(R.string.guide_failed, guideError.orEmpty()))
                    }
                }
                val toMidnight = DAY - Math.floorMod(now(), DAY) + 15 * MIN
                val retry = if (src is GuideSource.Xmltv) HOUR else 10 * MIN
                withTimeoutOrNull(if (r.isSuccess) min(3 * HOUR, toMidnight) else retry) { guideWake.receive() }
            }
        }
    }

    /** One pass for [src] on the guide thread; the toast for a forced pass that had nothing to do. */
    private fun guidePass(src: GuideSource, forced: Boolean): String? {
        when (src) {
            GuideSource.Segmented -> {
                val it = guideSync.run()
                prefs.guideAt = now()
                if (it.bytes > 0) {
                    val disk = segGuide.blobs.listFiles()?.sumOf { f -> f.length() } ?: 0L
                    Log.i(TAG, "guide: rev ${it.revision}: fetched ${it.bytes / 1024} KB in ${it.millis / 1000} s, today bound after ${it.todayMillis} ms, blobs on disk ${disk / 1024} KB")
                    return null
                }
                return str(R.string.guide_up_to_date)
            }
            is GuideSource.Xmltv -> {
                val pl = playlist?.id ?: return null
                val chs = guideChannels.takeIf { it.isNotEmpty() } ?: return null
                val m = xmlGuide.meta
                if (!forced && m != null && m.url == src.url && m.playlist == pl && now() - m.at < 12 * HOUR) return null
                val r = xmlGuide.import(xmltvHttp, src.url, pl, chs, Pacer(::guideHold)) { s ->
                    viewModelScope.launch(Dispatchers.Main) { if (guideSourceNow == src) status = s }
                }
                Log.i(TAG, "xmltv: ${r.channels} channels, ${r.programmes} programmes from ${r.bytes / 1024} KB in ${r.millis / 1000} s")
                return str(R.string.guide_updated, channelsWord(r.channels))
            }
        }
    }

    private val xmltvHttp by lazy { http.newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).readTimeout(2, TimeUnit.MINUTES).build() }

    /** Why the guide download should hold still right now: the user is at the remote, or the stream is starving
     *  (for at most a minute: a channel that never starts must not stop the guide for good). Any thread. */
    private fun guideHold(): String? {
        val b = bufferingSince
        return when {
            SystemClock.uptimeMillis() - lastKeyAt < 3000 -> "keys"
            b != 0L && SystemClock.elapsedRealtime() - b < MIN -> "buffering"
            else -> null
        }
    }

    // ============================================================ guide

    /** Decoded programmes or a description landed. Every getter below reads [guideVersion], so whatever showed their
     *  results recomposes. */
    private fun onGuideChanged(id: String?, old: List<Programme>?, new: List<Programme>?) {
        if (id != null && old != null && new != null) keepProgFocus(id, old, new)
        // a screenful of rows lands as a burst of decodes: one recomposition for the lot
        if (guideBumpAt != 0L) return
        guideBumpAt = SystemClock.uptimeMillis()
        viewModelScope.launch { delay(120); guideBumpAt = 0L; guideVersion++ }
    }

    private var guideBumpAt = 0L

    /** More days decoded (or an update) under the open programme view shift its indexes: stay on the same programme. */
    private fun keepProgFocus(id: String, old: List<Programme>, new: List<Programme>) {
        if (layer != Layer.LIST || (focus != Focus.PROGS && focus != Focus.DAYS)) return
        val ch = listChannel()?.takeIf { it.epgId == id } ?: return
        val from = listFrom(ch)
        val o = old.filter { it.end >= from }
        val n = new.filter { it.end >= from }
        o.getOrNull(progIdx)?.let { p -> n.indexOfFirst { it.start == p.start }.takeIf { it >= 0 }?.let { progIdx = it } }
        daysOf(o).getOrNull(dayIdx)?.let { d -> daysOf(n).indexOf(d).takeIf { it >= 0 }?.let { dayIdx = it } }
    }

    /** These getters never touch the disk: they return what is decoded (empty at first) and queue the rest. */
    fun progs(ch: Channel): List<Programme> {
        guideVersion
        return guide.list(ch, null)
    }

    fun progAt(ch: Channel, t: Long): Programme? {
        guideVersion
        val a = guide.list(ch, t)
        var lo = 0
        var hi = a.size - 1
        while (lo <= hi) {
            val m = (lo + hi) ushr 1
            when {
                a[m].end <= t -> lo = m + 1
                a[m].start > t -> hi = m - 1
                else -> return a[m]
            }
        }
        return null
    }

    /** The description of [p]; null while it is being read. */
    fun desc(p: Programme): String? {
        guideVersion
        return guide.desc(p)
    }

    /** A group's rows around [center]: what's on for ±10 rows, every day for the focused channel (decoded first). */
    fun prefetch(chs: List<Channel>, center: Int) {
        val t = now()
        for (k in 10 downTo 0) {
            chs.getOrNull(center + k)?.let { guide.list(it, t) }
            if (k > 0) chs.getOrNull(center - k)?.let { guide.list(it, t) }
        }
        chs.getOrNull(center)?.let { guide.list(it, null) }
    }

    private fun listFrom(ch: Channel) = if (ch.archiveDays > 0) now() - ch.archiveDays * DAY else dayStart(now())

    /** Programmes the user can browse: the archive window plus everything ahead. */
    fun progList(ch: Channel): List<Programme> {
        val from = listFrom(ch)
        return progs(ch).filter { it.end >= from }
    }

    fun progDays(ch: Channel): List<Long> = daysOf(progList(ch))

    private fun daysOf(list: List<Programme>): List<Long> {
        val out = ArrayList<Long>()
        for (p in list) { val d = dayStart(p.start); if (out.lastOrNull() != d) out += d }
        return out
    }

    /** «That day at this time»: the latest programme starting on [day] no later than the current clock time, else the
     *  day's first. Only programmes that start on [day]: one running across midnight (Tue 23:30–Wed 01:00) belongs to
     *  the day before, and the view shows the day of the programme it lands on. */
    private fun sameTimeOn(list: List<Programme>, day: Long): Int {
        val t = day + (now() - dayStart(now()))
        var i = list.indexOfLast { dayStart(it.start) == day && it.start <= t }
        if (i < 0) i = list.indexOfFirst { dayStart(it.start) == day }
        return max(0, i)
    }

    // ============================================================ bookmarks («Продовжити перегляд»)

    private fun markKey(ch: Channel, p: Programme) = ch.id + ":" + p.start

    fun watched(ch: Channel, p: Programme): Float {
        markVersion
        return marks[markKey(ch, p)]?.watched ?: 0f
    }

    private fun resumeList(): List<Mark> =
        marks.values.filter { it.watched < DONE && it.ch.inArchive(it.p) && isShown(it.ch) }.sortedByDescending { it.at }.take(20)

    private fun marksFromDb() {
        marks.clear()
        for (r in markRows.values) {
            val ch = byId[r.ch] ?: continue
            marks[markKey(ch, r.p)] = Mark(ch, r.p, r.pos, r.at)
        }
    }

    private var lastPersist = 0L
    private fun saveMark(persist: Boolean = true) {
        if (mode != Mode.ARCHIVE) return
        val ch = cur ?: return
        val t = posAbs()
        val p = progAt(ch, t) ?: return
        if (t - p.start < MIN) return
        if (markKey(ch, p) == markLock) return
        val m = Mark(ch, p, t, now())
        val isNew = markKey(ch, p) !in marks
        marks[markKey(ch, p)] = m
        markRows[markKey(ch, p)] = Db.MarkRow(ch.id, p, t, m.at)
        if (persist || now() - lastPersist > 10_000) {
            lastPersist = now()
            val pl = playlist?.id ?: return
            viewModelScope.launch(dbIo) { db.saveMark(pl, m) }
        }
        if (isNew) { rebuildGroups(); markVersion++; refreshHome(); syncTvHome() }
    }

    fun forgetMark(ch: Channel, p: Programme) {
        lockMarkIfPlaying(ch, p)
        marks.remove(markKey(ch, p))
        markRows.remove(markKey(ch, p))
        markVersion++
        rebuildGroups()
        syncGroup()
        refreshHome(drop = HomeRows.continueKey(ch, p))
        syncTvHome()
        val pl = playlist?.id ?: return
        viewModelScope.launch(dbIo) { db.deleteMark(pl, ch.id, p.start) }
    }

    /** «Позначити переглянутим»: the position goes to the end, so it leaves «Продовжити перегляд» and shows ✓. */
    private fun markWatched(ch: Channel, p: Programme) {
        lockMarkIfPlaying(ch, p)
        val m = Mark(ch, p, p.end, now())
        marks[markKey(ch, p)] = m
        markRows[markKey(ch, p)] = Db.MarkRow(ch.id, p, p.end, m.at)
        markVersion++
        rebuildGroups()
        syncGroup()
        refreshHome(drop = HomeRows.continueKey(ch, p))
        syncTvHome()
        val pl = playlist?.id ?: return
        viewModelScope.launch(dbIo) { db.saveMark(pl, m) }
    }

    private fun lockMarkIfPlaying(ch: Channel, p: Programme) {
        if (mode == Mode.ARCHIVE && cur == ch && progAt(ch, posAbs())?.start == p.start) markLock = markKey(ch, p)
    }

    /** Remembers [ch] at the top of «Нещодавні». */
    private fun addRecent(ch: Channel) {
        if (recent.firstOrNull() == ch.id) return
        recent.remove(ch.id)
        recent.add(0, ch.id)
        while (recent.size > RECENT_MAX) recent.removeAt(recent.size - 1)
        prefs.recent = recent
        rebuildGroups()
        if (layer == Layer.LIST) syncGroup()
    }

    // ============================================================ playback

    fun posAbs(): Long {
        pausedLiveAt?.let { return it }
        if (mode == Mode.LIVE) return now()
        pendingAbs?.let { return it }
        return windowBase() + player.currentPosition
    }

    /** What live is showing right now: the player sits ~20–30 s behind the broadcast, so pausing or seeking from live
     *  starts from the frame on screen rather than from the wall clock. Measured from the end of the live window, not
     *  from PROGRAM-DATE-TIME: some channels' live PDT runs minutes off the clock the archive is indexed by. */
    private fun livePos(): Long {
        val d = player.duration
        return if (d == C.TIME_UNSET || !player.isCurrentMediaItemLive) now() else now() - (d - player.currentPosition).coerceIn(0, MIN)
    }

    private fun windowBase(): Long {
        val tl = player.currentTimeline
        if (!tl.isEmpty) {
            val w = tl.getWindow(player.currentMediaItemIndex, Timeline.Window())
            if (w.windowStartTimeMs != C.TIME_UNSET) return w.windowStartTimeMs
        }
        return archiveFrom
    }

    private fun play(url: String, offsetMs: Long = C.TIME_UNSET) {
        val item = MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        if (offsetMs == C.TIME_UNSET) player.setMediaItem(item) else player.setMediaItem(item, offsetMs)
        player.prepare()
        player.playWhenReady = true
    }

    fun tune(ch: Channel, at: Long? = null) {
        if (BuildConfig.DEBUG) android.util.Log.d("tivibro", "tune ${ch.id} at=$at")
        saveMark()
        cur = ch
        prefs.lastChannel = ch.id
        addRecent(ch)
        pausedLiveAt = null
        paused = false
        if (at == null) {
            mode = Mode.LIVE
            play(ch.url)
            showBanner()
        } else {
            playArchive(ch, at)
            showOsd()
        }
    }

    /** Up to an hour behind the broadcast (resumed pause, seek back from live, current programme from the start) the
     *  window is open-ended (`-now`): it grows with the broadcast, so playback stays behind live for as long as the
     *  user likes without re-windowing. Further back it is a fixed window of up to 6 h, re-cut by [onTick] before it ends. */
    private fun playArchive(ch: Channel, t: Long) {
        val earliest = now() - ch.archiveDays * DAY
        val from = (max(earliest, t - 30 * MIN) / 1000) * 1000
        val open = now() - t <= HOUR
        val durSec = min((now() - from) / 1000, Catchup.maxWindowSec(ch))
        val url = Catchup.url(ch, from / 1000, if (open) null else durSec)
        if (url == null) { onNoArchive(); return }
        if (BuildConfig.DEBUG) android.util.Log.d("tivibro", "archive ${ch.id} behind=${(now() - t) / 1000}s open=$open")
        archiveFrom = from
        archiveTo = if (open) OPEN else from + durSec * 1000
        markLock = null
        mode = Mode.ARCHIVE
        paused = false
        pausedLiveAt = null
        pendingAbs = t
        play(url, t - from)
    }

    /** The channel can be paused, rewound and played from the guide: it has a depth, a scheme, and the server didn't
     *  answer with its «no archive» stub. */
    fun canArchive(ch: Channel) = ch.archiveDays > 0 && ch.id !in noArchive && Catchup.kind(ch) != Catchup.Kind.NONE

    private fun isNoArchive(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) {
            if (c is HttpDataSource.InvalidResponseCodeException && c.responseCode == 410 && c.headerFields.keys.any { it.equals(NO_ARCHIVE, true) }) return true
            c = c.cause
        }
        return false
    }

    /** No archive after all: say so and go back to the broadcast rather than show the server's stub. */
    private fun onNoArchive() {
        val ch = cur ?: return
        noArchive += ch.id
        showToast(str(R.string.no_archive))
        mode = Mode.LIVE; paused = false; pausedLiveAt = null; pendingAbs = null
        play(ch.url)
        hideOsd()
    }

    private fun seekAbs(target: Long) {
        val ch = cur ?: return
        val t = max(target, now() - ch.archiveDays * DAY)
        if (t >= now() - 5_000) { goLive(); return }
        if (pausedLiveAt == null && mode == Mode.ARCHIVE && t >= archiveFrom && t <= archiveTo - 30_000) {
            // past the end of a `-now` window is the live edge itself: rejoin the broadcast rather than wait for it
            if (archiveTo == OPEN && player.duration != C.TIME_UNSET && t >= windowBase() + player.duration - 5_000) { goLive(); return }
            pendingAbs = t
            player.seekTo(t - windowBase())
        } else {
            playArchive(ch, t)
        }
    }

    fun goLive() {
        if (BuildConfig.DEBUG) android.util.Log.d("tivibro", "goLive")
        saveMark()
        val ch = cur ?: return
        mode = Mode.LIVE
        paused = false
        pausedLiveAt = null
        play(ch.url)
        hideOsd()
        showBanner()
        showToast(str(R.string.back_live))
    }

    private fun togglePause() {
        val ch = cur ?: return
        when {
            mode == Mode.LIVE -> {
                if (!canArchive(ch)) { showToast(str(R.string.no_archive)); return }
                pausedLiveAt = livePos(); mode = Mode.ARCHIVE; paused = true; player.pause()
            }
            pausedLiveAt != null -> playArchive(ch, pausedLiveAt!!)
            else -> { paused = !paused; player.playWhenReady = !paused }
        }
        showOsd()
    }

    /** 10 s → 30 s → 1 min → 5 min: a step up every second tap in a quick run, or every few repeats of a held key. */
    private val seekSteps = longArrayOf(10, 30, 60, 300)
    private fun seek(dir: Int, repeat: Int) {
        val ch = cur ?: return
        if (!canArchive(ch)) { showToast(str(R.string.no_archive)); return }
        if (dir > 0 && mode == Mode.LIVE) return // already at the live edge
        if (repeat == 0) { seekRun = if (now() - lastSeekAt < 1200) seekRun + 1 else 0 }
        lastSeekAt = now()
        if (mode == Mode.LIVE) { // ◀ in live: timeshift from the frame on screen
            pendingAbs = livePos()
            pausedLiveAt = null; mode = Mode.ARCHIVE; archiveFrom = 0; archiveTo = 0
        }
        val step = seekSteps[min(seekRun / 2 + repeat / 6, seekSteps.size - 1)] * 1000 * dir
        seekAcc += step
        seekAbs(posAbs() + step)
        showOsd()
        seekJob?.cancel()
        seekJob = viewModelScope.launch { delay(900); seekAcc = 0 }
    }

    // ============================================================ player control panel

    /** The programme at the playing position. */
    fun playingProgramme(): Programme? = cur?.let { progAt(it, posAbs()) }

    /** Buttons that make no sense right now are dimmed and skipped by focus. */
    fun osdEnabled(b: OsdButton): Boolean {
        val ch = cur ?: return false
        val p = playingProgramme()
        return when (b) {
            OsdButton.RESTART -> p != null && ch.inArchive(p) && posAbs() - p.start > 5_000
            OsdButton.PREV -> p != null && progAt(ch, p.start - 1)?.let(ch::inArchive) == true
            OsdButton.NEXT, OsdButton.LIVE -> mode == Mode.ARCHIVE
            OsdButton.AUDIO -> audioTracks > 1
        }
    }

    private fun osdButtons() = OsdButton.entries.filter(::osdEnabled)

    private fun runOsd(b: OsdButton) {
        val ch = cur ?: return
        val p = playingProgramme()
        when (b) {
            OsdButton.RESTART -> if (p != null) { saveMark(); seekAbs(p.start); showToast(str(R.string.from_start_toast, p.title)) }
            OsdButton.PREV -> p?.let { progAt(ch, it.start - 1) }?.let { q -> saveMark(); seekAbs(q.start); showToast(q.title) }
            OsdButton.NEXT -> {
                saveMark()
                val q = progAt(ch, (p?.end ?: posAbs()) + 1)
                if (q == null || q.start >= now() - 5000) goLive() else { seekAbs(q.start); showToast(q.title) }
            }
            OsdButton.LIVE -> goLive()
            OsdButton.AUDIO -> cycleAudio()
        }
        if (!osd) return
        showOsd()
        if (!osdEnabled(osdBtn)) osdButtons().firstOrNull()?.let { osdBtn = it } ?: run { osdOnButtons = false }
    }

    private fun audioGroups() = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }

    private fun cycleAudio() {
        val gs = audioGroups()
        if (gs.size < 2) { showToast(str(R.string.no_other_audio)); return }
        val i = (gs.indexOfFirst { it.isSelected } + 1) % gs.size
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(gs[i].mediaTrackGroup, 0)).build()
        val f = gs[i].getTrackFormat(0)
        val name = f.label ?: f.language?.let { java.util.Locale(it).getDisplayLanguage(L10n.locale).replaceFirstChar(Char::uppercase) }
        showToast(str(R.string.audio_track, name ?: str(R.string.audio_track_n, i + 1), i + 1, gs.size))
    }

    /** Timeline: ◀▶ seek, OK pause, ▼ buttons. Buttons: ◀▶ choose, OK run, ▲ timeline. BACK closes; any key keeps it up. */
    private fun panelKey(k: Key, repeat: Int) {
        showOsd()
        when (k) {
            Key.BACK -> { hideOsd(); return }
            Key.CHUP -> { zap(1); return }
            Key.CHDOWN -> { zap(-1); return }
            else -> Unit
        }
        if (!osdOnButtons) when (k) {
            Key.LEFT -> seek(-1, repeat)
            Key.RIGHT -> seek(1, repeat)
            Key.OK -> togglePause()
            Key.DOWN -> osdButtons().firstOrNull()?.let { osdBtn = it; osdOnButtons = true }
            else -> Unit
        } else when (k) {
            Key.LEFT, Key.RIGHT -> {
                val bs = osdButtons()
                if (bs.isNotEmpty()) osdBtn = bs[(bs.indexOf(osdBtn).coerceAtLeast(0) + if (k == Key.RIGHT) 1 else -1).coerceIn(0, bs.size - 1)]
            }
            Key.OK -> if (osdEnabled(osdBtn)) runOsd(osdBtn)
            Key.UP -> osdOnButtons = false
            else -> Unit
        }
    }

    /** «Продовжити перегляд» lists programmes and «Нещодавні» reorders itself on every tune: zapping goes by «Усі канали». */
    private fun zapsBy(id: String) = id != "cont" && id != "recent"

    private fun zap(dir: Int) {
        val list = groupFor(groupId)?.channels?.takeIf { it.isNotEmpty() && zapsBy(groupId) } ?: shown
        if (list.isEmpty()) return
        val i = list.indexOf(cur)
        val n = cur?.num ?: 0
        tune(
            if (i >= 0) list[(i + dir + list.size) % list.size]
            // the current channel was just hidden: go on from where it stood
            else if (dir > 0) list.firstOrNull { it.num > n } ?: list.first() else list.lastOrNull { it.num < n } ?: list.last(),
        )
    }

    fun playProgramme(ch: Channel, p: Programme, fromStart: Boolean = false) {
        if (BuildConfig.DEBUG) android.util.Log.d("tivibro", "playProgramme ${ch.id} ${p.start} past=${p.isPast()} now=${p.isNow()}")
        closeLayers()
        when {
            p.isNow() && fromStart && !canArchive(ch) -> { showToast(str(R.string.no_archive)); tune(ch) }
            p.isNow() && fromStart -> tune(ch, p.start)
            p.isNow() -> tune(ch)
            p.isPast() -> {
                if (!canArchive(ch)) { showToast(str(R.string.no_archive)); return }
                if (!ch.inArchive(p)) { showToast(str(R.string.out_of_archive)); return }
                val m = marks[markKey(ch, p)]
                val resume = !fromStart && m != null && m.watched < DONE
                tune(ch, if (resume) m!!.pos else p.start)
                showToast(if (resume) str(R.string.resuming, ago(m!!.pos - p.start), p.title) else p.title)
            }
            else -> showToast(str(R.string.not_started))
        }
    }

    private fun onTick() {
        // Home follows the clock: what started, what is on, what is soon; the index itself is re-read every half hour
        if (layer == Layer.HOME && (now() / 1000 % 30 == 0L || watchingStale())) refreshHome()
        else if (now() / 1000 % 1800 == 0L && cur != null) ensureHomeIndex()
        if (mode == Mode.ARCHIVE && pausedLiveAt == null) {
            saveMark(persist = false)
            val t = posAbs()
            // A fixed window is re-cut before its ENDLIST stops playback; a `-now` one never runs out and is only re-cut
            // once it has grown past 3 h, to keep the playlist the player keeps re-polling small. Never jumps to live.
            val ch = cur ?: return
            val openMax = min(3 * HOUR, Catchup.maxWindowSec(ch) * 1000 - 30 * MIN)
            val renew = if (archiveTo == OPEN) now() - archiveFrom > openMax else archiveTo > 0 && t > archiveTo - 20_000
            if (!paused && pendingAbs == null && renew) playArchive(ch, t)
        }
    }

    fun onStop() { saveMark(); player.pause(); syncTvHome(now = true) }

    /** «Вихід»: the bookmark is saved, playback stops, the activity finishes. */
    private fun exitApp() {
        saveMark()
        closeSheets(); closeCtx(); markCtx = null
        player.stop()
        onExit()
    }
    fun onStart() {
        if (!paused && cur != null) player.playWhenReady = true
        updater.checkIfDue()
    }

    override fun onCleared() {
        Updater.onInstallError = null
        setupServer.stop()
        player.release()
        guideThread.close()
    }

    // ============================================================ overlays

    private fun showBanner() {
        osd = false
        banner = true
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch { delay(5000); banner = false }
    }

    /** Opens the panel or keeps it up: it hides after 6 s without keys, unless paused. Reopened, it starts on the timeline. */
    private fun showOsd() {
        markLock = null
        if (!osd) osdOnButtons = false
        banner = false
        osd = true
        osdJob?.cancel()
        osdJob = viewModelScope.launch { delay(6000); if (!paused) osd = false }
    }

    private fun hideOsd() { osd = false }

    fun showToast(text: String) {
        toast = text
        toastJob?.cancel()
        toastJob = viewModelScope.launch { delay(2600); toast = null }
    }

    private fun closeLayers() { layer = Layer.NONE }

    // ============================================================ list

    private fun groupFor(id: String) = groups.firstOrNull { it.id == id }
    fun currentGroup(): Group? = groups.getOrNull(groupIdx)
    fun listChannel(): Channel? = currentGroup()?.channels?.getOrNull(chIdx)
    fun listMark(): Mark? = currentGroup()?.marks?.getOrNull(chIdx)

    private fun syncGroup() {
        val i = groups.indexOfFirst { it.id == groupId }
        groupIdx = if (i >= 0) i else groups.indexOfFirst { it.id == "all" }.coerceAtLeast(0)
    }

    private fun openList(programmes: Boolean = false) {
        listKeyAt = keyAt
        banner = false; osd = false
        if (!zapsBy(groupId)) groupId = prefs.lastGroup ?: "all" // «Нещодавні» / «Продовжити» were a detour
        if (groupFor(groupId)?.channels?.contains(cur) != true) groupId = "all"
        syncGroup()
        chIdx = max(0, currentGroup()?.channels?.indexOf(cur) ?: 0)
        focus = Focus.CHANNELS
        layer = Layer.LIST
        if (programmes) openSchedule(cur?.let { progAt(it, posAbs()) }) // the programme being played, live or archive
    }

    private fun openSchedule(target: Programme?) {
        val ch = listChannel() ?: return
        val list = progList(ch)
        if (list.isEmpty()) {
            showToast(
                when {
                    !guide.hasData && !guideSyncing -> str(R.string.guide_not_loaded)
                    !guide.hasData || !guide.complete(ch) -> str(R.string.guide_loading)
                    else -> str(R.string.no_guide_for_channel)
                },
            )
            return
        }
        progIdx = (if (target != null) list.indexOfFirst { it.start == target.start } else list.indexOfFirst { it.isNow() }).coerceAtLeast(0)
        focus = Focus.PROGS
    }

    private fun toggleFav(ch: Channel) {
        if (ch.id in favs) { favs.remove(ch.id); showToast(str(R.string.fav_removed, ch.name)) }
        else { favs.add(ch.id); showToast(str(R.string.fav_added, ch.name)) }
        prefs.favorites = favs
        rebuildGroups()
        syncGroup()
    }

    fun isFav(ch: Channel) = ch.id in favs

    /** ◀ walks on deeper, the same way everywhere: programmes › days, channels › groups › menu. BACK, whatever has the
     *  focus, closes the whole list back to playback. */
    private fun listKey(k: Key) {
        val g = currentGroup() ?: return
        when (focus) {
            Focus.GROUPS -> when (k) {
                Key.UP -> { groupIdx = (groupIdx - 1 + groups.size) % groups.size; chIdx = 0 }
                Key.DOWN -> { groupIdx = (groupIdx + 1) % groups.size; chIdx = 0 }
                Key.RIGHT, Key.OK -> focus = Focus.CHANNELS
                Key.LEFT -> openMenu()
                Key.BACK -> closeLayers()
                else -> Unit
            }.also { groupIdx = groupIdx.coerceIn(0, groups.size - 1); groupId = groups[groupIdx].id; if (zapsBy(groupId)) prefs.lastGroup = groupId }

            Focus.DAYS -> {
                val ch = listChannel() ?: return
                val days = progDays(ch)
                when (k) {
                    Key.UP, Key.CHUP, Key.YELLOW -> dayIdx = max(0, dayIdx - 1)
                    Key.DOWN, Key.CHDOWN, Key.BLUE -> dayIdx = min(days.size - 1, dayIdx + 1)
                    Key.RIGHT, Key.OK -> days.getOrNull(dayIdx)?.let { progIdx = sameTimeOn(progList(ch), it); focus = Focus.PROGS }
                    Key.LEFT -> focus = Focus.CHANNELS
                    Key.BACK -> closeLayers()
                    else -> Unit
                }
            }

            Focus.PROGS -> {
                val ch = listChannel() ?: return
                val list = progList(ch)
                if (list.isEmpty()) { focus = Focus.CHANNELS; return }
                when (k) {
                    Key.UP -> progIdx = max(0, progIdx - 1)
                    Key.DOWN -> progIdx = min(list.size - 1, progIdx + 1)
                    Key.CHUP, Key.CHDOWN, Key.YELLOW, Key.BLUE -> {
                        val days = progDays(ch)
                        val d = days.indexOf(dayStart(list[progIdx].start)) + if (k == Key.CHUP || k == Key.YELLOW) -1 else 1
                        if (d in days.indices) progIdx = sameTimeOn(list, days[d])
                    }
                    Key.LEFT -> { dayIdx = progDays(ch).indexOf(dayStart(list[progIdx].start)).coerceAtLeast(0); focus = Focus.DAYS }
                    Key.BACK -> closeLayers()
                    Key.OK -> playProgramme(ch, list[progIdx])
                    else -> Unit
                }
            }

            Focus.CHANNELS -> {
                val n = g.channels.size
                val m = listMark()
                when (k) {
                    Key.UP -> if (n > 0) chIdx = (chIdx - 1 + n) % n
                    Key.DOWN -> if (n > 0) chIdx = (chIdx + 1) % n
                    Key.CHUP -> chIdx = max(0, chIdx - 9)
                    Key.CHDOWN -> chIdx = min(n - 1, chIdx + 9)
                    Key.LEFT -> focus = Focus.GROUPS
                    Key.BACK -> closeLayers()
                    Key.RIGHT -> openSchedule(m?.p)
                    Key.OK -> {
                        if (m != null) { playProgramme(m.ch, m.p); return }
                        val ch = listChannel() ?: return
                        if (zapsBy(g.id)) groupId = g.id
                        closeLayers()
                        if (ch == cur && mode == Mode.LIVE) showBanner() else tune(ch)
                    }
                    Key.RED -> listChannel()?.let(::toggleFav)
                    else -> Unit
                }
            }
        }
    }

    private fun listLongOk() {
        when (focus) {
            Focus.PROGS -> {
                val ch = listChannel() ?: return
                val p = progList(ch).getOrNull(progIdx) ?: return
                longOkProgramme(ch, p)
            }
            Focus.CHANNELS -> {
                val m = listMark()
                if (m != null) { openMarkCtx(m); return }
                listChannel()?.let(::openCtx)
            }
            else -> Unit
        }
    }

    // ============================================================ menu, settings, context menu

    /** The row each sheet under the top one was on. */
    private val rowStack = ArrayList<Int>()

    private fun push(s: Sheet) {
        if (sheets.isNotEmpty()) rowStack += rowIdx
        sheets = sheets + s
    }

    private fun pop() {
        if (sheets.lastOrNull() == Sheet.LINK) stopLink()
        sheets = sheets.dropLast(1)
        rowIdx = rowStack.removeLastOrNull() ?: 0
        if (sheets.isEmpty()) refreshHome() // back on Home from its sources: what they changed shows at once
    }

    private fun closeSheets() {
        if (Sheet.LINK in sheets) stopLink()
        sheets = emptyList()
        rowStack.clear()
    }

    /** The LAN page goes back to what the first-run screen expects, and stops. */
    private fun stopLink() {
        setupServer.stop()
        setupServer.page = null
        setupServer.onSubmit = { link -> viewModelScope.launch { usePlaylist(link) } }
    }

    private fun openMenu() {
        banner = false; osd = false
        menuIdx = 0
        closeSheets()
        sheets = listOf(Sheet.MENU)
    }

    private fun openSheet(s: Sheet) {
        push(s)
        rowIdx = when (s) {
            Sheet.PLAYLISTS -> max(0, playlists.indexOfFirst { it.id == playlist?.id })
            Sheet.GUIDE -> playlist?.guideMode?.ordinal ?: 0
            Sheet.LANGUAGE -> L10n.lang(getApplication()).ordinal
            else -> 0
        }
    }

    /** The side menu's rows, top to bottom. */
    fun menuItems(): List<MenuItem> = MenuItem.entries.filter { it != MenuItem.UPDATE || updater.available != null }

    /** ▲▼ choose, OK opens, ▶ back to what is under it; BACK back to where the menu was opened from (Home if from
     *  Home, else playback; Home also when nothing plays yet), MENU closes back to playback. */
    private fun menuKey(k: Key) {
        val items = menuItems()
        val n = items.size
        menuIdx = menuIdx.coerceIn(0, n - 1)
        when (k) {
            Key.UP -> menuIdx = (menuIdx - 1 + n) % n
            Key.DOWN -> menuIdx = (menuIdx + 1) % n
            Key.OK -> when (items[menuIdx]) {
                MenuItem.HOME -> openHome()
                MenuItem.GROUPS -> openSheet(Sheet.GROUPS)
                MenuItem.SETTINGS -> openSheet(Sheet.SETTINGS)
                MenuItem.GUIDE -> { closeSheets(); refreshGuide(force = true) }
                MenuItem.UPDATE -> updater.install()
                MenuItem.EXIT -> exitApp()
            }
            Key.RIGHT -> pop()
            Key.BACK -> if (layer == Layer.HOME || cur == null) openHome() else { closeSheets(); closeLayers() } // openHome keeps Home's focus
            Key.MENU -> { closeSheets(); closeLayers() }
            else -> Unit
        }
    }

    /** Settings sheets: ▲▼ rows, OK acts (flips a switch, opens a sheet), BACK or ◀ goes back. */
    private fun sheetKey(s: Sheet, k: Key) {
        val n = when (s) {
            Sheet.SETTINGS -> SettingsRow.entries.size
            Sheet.GROUPS -> allGroups.size
            Sheet.HIDDEN -> hiddenChannels().size
            Sheet.PLAYLISTS -> playlists.size + 1 // + «Додати плейлист»
            Sheet.PLAYLIST -> PlaylistAction.entries.size
            Sheet.GUIDE -> GuideRow.entries.size
            Sheet.LINK -> 0
            Sheet.LANGUAGE -> L10n.Lang.entries.size
            Sheet.HOME -> HomeSetting.entries.size
            Sheet.HOME_SOURCES -> allGroups.size
            Sheet.HOME_GROUP -> (homeGroup?.channels?.size ?: 0) + 1 // + «Усі канали групи»
            Sheet.MENU -> return menuKey(k)
        }
        when (k) {
            Key.UP -> if (n > 0) rowIdx = (rowIdx - 1 + n) % n
            Key.DOWN -> if (n > 0) rowIdx = (rowIdx + 1) % n
            Key.CHUP -> rowIdx = max(0, rowIdx - 8)
            Key.CHDOWN -> rowIdx = max(0, min(n - 1, rowIdx + 8))
            Key.BACK, Key.MENU -> pop()
            Key.LEFT, Key.RIGHT -> if (s == Sheet.SETTINGS && SettingsRow.entries[rowIdx] == SettingsRow.SCALE) {
                stepScale(if (k == Key.RIGHT) 1 else -1, wrap = false)
            } else if (s == Sheet.SETTINGS && SettingsRow.entries[rowIdx] == SettingsRow.STARTUP) {
                changeStartHome(k == Key.RIGHT)
            } else if (s == Sheet.HOME_SOURCES && k == Key.RIGHT) {
                allGroups.getOrNull(rowIdx)?.let(::openHomeGroup)
            } else if (k == Key.LEFT) pop()
            Key.OK -> when (s) {
                Sheet.SETTINGS -> when (SettingsRow.entries[rowIdx]) {
                    SettingsRow.PLAYLISTS -> { playlists = repo.all(); openSheet(Sheet.PLAYLISTS) }
                    SettingsRow.GROUPS -> openSheet(Sheet.GROUPS)
                    SettingsRow.HIDDEN -> openSheet(Sheet.HIDDEN)
                    SettingsRow.GUIDE -> openSheet(Sheet.GUIDE)
                    SettingsRow.HOME -> openSheet(Sheet.HOME)
                    SettingsRow.STARTUP -> changeStartHome(!startHome)
                    SettingsRow.LANGUAGE -> openSheet(Sheet.LANGUAGE)
                    SettingsRow.SCALE -> stepScale(1, wrap = true)
                    SettingsRow.UPDATE -> if (updater.available != null) updater.install() else updater.check(manual = true)
                    SettingsRow.ABOUT -> Unit
                }
                Sheet.LANGUAGE -> setLanguage(L10n.Lang.entries[rowIdx])
                Sheet.PLAYLISTS -> playlists.getOrNull(rowIdx)?.let(::openPlaylistSheet) ?: openLink(LinkPurpose.ADD_PLAYLIST, null)
                Sheet.PLAYLIST -> playlistAction(PlaylistAction.entries[rowIdx])
                Sheet.GUIDE -> when (GuideRow.entries[rowIdx]) {
                    GuideRow.AUTO -> setGuideMode(GuideMode.AUTO)
                    GuideRow.PLAYLIST -> setGuideMode(GuideMode.PLAYLIST)
                    GuideRow.CUSTOM -> {
                        val p = playlist
                        if (p?.guideUrl != null && p.guideMode != GuideMode.CUSTOM) {
                            afterPlaylistEdit(repo.update(p.id) { it.copy(guideMode = GuideMode.CUSTOM) } ?: return)
                            showToast(str(R.string.guide_custom_again))
                        } else setGuideMode(GuideMode.CUSTOM)
                    }
                    GuideRow.REFRESH -> refreshGuide(force = true)
                }
                Sheet.LINK -> Unit
                Sheet.GROUPS -> allGroups.getOrNull(rowIdx)?.let(::toggleGroupShown)
                Sheet.HIDDEN -> hiddenChannels().getOrNull(rowIdx)?.let { ch ->
                    setHidden(ch, false)
                    showToast(str(R.string.channel_back, ch.name))
                    rowIdx = rowIdx.coerceAtMost(max(0, n - 2))
                }
                Sheet.HOME -> when (HomeSetting.entries[rowIdx]) {
                    HomeSetting.SOURCES -> openSheet(Sheet.HOME_SOURCES)
                    HomeSetting.TMDB -> switchTmdb(!tmdbOn || tmdbToken == null)
                    HomeSetting.TMDB_KEY -> openLink(LinkPurpose.TMDB_KEY, null)
                }
                Sheet.HOME_SOURCES -> allGroups.getOrNull(rowIdx)?.let(::openHomeGroup)
                Sheet.HOME_GROUP -> homeGroup?.let { g -> if (rowIdx == 0) toggleHomeGroup(g) else g.channels.getOrNull(rowIdx - 1)?.let { toggleHomeChannel(g, it) } }
                Sheet.MENU -> Unit
            }
            else -> Unit
        }
    }

    /** ◀▶ move one step and stop at the ends; OK goes round (150 % → 90 %). Applies at once, saved for every playlist. */
    private fun stepScale(d: Int, wrap: Boolean) {
        val i = UI_SCALES.indexOf(uiScale).coerceAtLeast(0) + d
        val next = if (wrap) UI_SCALES[(i + UI_SCALES.size) % UI_SCALES.size] else UI_SCALES[i.coerceIn(0, UI_SCALES.size - 1)]
        if (next == uiScale) return
        uiScale = next
        prefs.uiScale = next
    }

    /** Saves the choice and recreates the activity; the sheets stay where they are (they live here). */
    private fun setLanguage(l: L10n.Lang) {
        val app = getApplication<Application>()
        if (L10n.lang(app) == l) return
        L10n.setLang(app, l)
        tmdb.lang = Tmdb.tmdbLang(L10n.locale.language)
        tmdb.reset() // titles, overviews and genres come in the new language
        status = null // already formatted in the old language; the next progress update writes it in the new one
        rebuildGroups()
        cfgVersion++
        onRecreate()
    }

    private fun openCtx(ch: Channel) {
        banner = false
        ctx = ch
        ctxIdx = 0
    }

    private fun closeCtx() { ctx = null }

    private fun ctxKey(k: Key) {
        val ch = ctx ?: return
        val n = CtxItem.entries.size
        when (k) {
            Key.UP -> ctxIdx = (ctxIdx - 1 + n) % n
            Key.DOWN -> ctxIdx = (ctxIdx + 1) % n
            Key.BACK, Key.LEFT, Key.MENU -> closeCtx()
            Key.OK -> when (CtxItem.entries[ctxIdx]) {
                CtxItem.FAV -> { closeCtx(); toggleFav(ch) }
                CtxItem.HIDE -> {
                    closeCtx()
                    val hide = !isHidden(ch) // the playing channel can already be hidden: then this brings it back
                    setHidden(ch, hide)
                    showToast(str(if (hide) R.string.channel_hidden else R.string.channel_back, ch.name))
                }
            }
            else -> Unit
        }
    }

    /** Long OK on a programme: a watched one in the archive gets the bookmark menu; the one on air starts over. */
    private fun longOkProgramme(ch: Channel, p: Programme) {
        val m = marks[markKey(ch, p)]
        when {
            p.isPast() && ch.inArchive(p) && m != null -> openMarkCtx(m)
            p.isNow() && canArchive(ch) -> playProgramme(ch, p, fromStart = true)
            else -> playProgramme(ch, p)
        }
    }

    /** Whether long OK on [p] opens the bookmark menu (for the hints). */
    fun hasMarkMenu(ch: Channel, p: Programme) = p.isPast() && ch.inArchive(p) && markKey(ch, p) in marks

    private fun openMarkCtx(m: Mark) {
        banner = false
        markCtx = m
        ctxIdx = 0
    }

    /** The bookmark menu's rows: a watched programme offers to forget the ✓ instead of marking it again. */
    fun markActions(m: Mark): List<MarkAction> =
        if (m.watched >= DONE) listOf(MarkAction.FROM_START, MarkAction.FORGET) else MarkAction.entries

    private fun markCtxKey(k: Key) {
        val m = markCtx ?: return
        val items = markActions(m)
        val n = items.size
        when (k) {
            Key.UP -> ctxIdx = (ctxIdx - 1 + n) % n
            Key.DOWN -> ctxIdx = (ctxIdx + 1) % n
            Key.BACK, Key.LEFT, Key.MENU -> markCtx = null
            Key.OK -> {
                markCtx = null
                when (items[ctxIdx.coerceIn(0, n - 1)]) {
                    MarkAction.FROM_START -> playProgramme(m.ch, m.p, fromStart = true)
                    MarkAction.FORGET -> {
                        val done = m.watched >= DONE
                        forgetMark(m.ch, m.p)
                        showToast(str(if (done) R.string.mark_unwatched_toast else R.string.mark_forgotten_toast))
                        afterMarkChange(m.ch)
                    }
                    MarkAction.DONE -> {
                        markWatched(m.ch, m.p)
                        showToast(str(R.string.mark_done_toast, m.p.title))
                        afterMarkChange(m.ch)
                    }
                }
            }
            else -> Unit
        }
    }

    /** The bookmark left «Продовжити перегляд»: keep the list on something that still exists. A programme view opened
     *  from that group moves to the same channel in «Усі канали», since its row may be gone. */
    private fun afterMarkChange(ch: Channel) {
        if (layer != Layer.LIST) return
        if (groupId == "cont" || groupFor(groupId) == null) {
            val inProgs = focus == Focus.PROGS || focus == Focus.DAYS
            if (inProgs || groupFor("cont") == null) {
                groupId = "all"; syncGroup(); chIdx = max(0, shown.indexOf(if (inProgs) ch else cur))
            } else syncGroup()
        }
        chIdx = chIdx.coerceIn(0, max(0, (currentGroup()?.channels?.size ?: 1) - 1))
    }

    // ============================================================ Home

    /** Opens Home over the video (playback goes on), or comes back to it from the menu keeping its focus. */
    fun openHome() {
        closeSheets(); closeCtx(); markCtx = null
        banner = false; osd = false
        if (layer == Layer.HOME) return
        listKeyAt = if (keyAt > 0) keyAt else SystemClock.uptimeMillis() // opened at start: timed from now
        layer = Layer.HOME
        homeCols.clear()
        ensureHomeIndex()
        homeRows = buildHomeRows()
        homeRowIdx = 0
        homeColIdx = 0
        homeFocused()
    }

    private fun closeHome() { if (layer == Layer.HOME) layer = Layer.NONE }

    /** «Продовжити перегляд» on Home: every unfinished bookmark of the playlist still in its archive window, newest first. */
    private fun homeContinue(): List<Mark> =
        marks.values.filter { it.watched < DONE && it.ch.inArchive(it.p) }.sortedByDescending { it.at }.take(HOME_ROW_MAX)

    /** «Зараз дивитесь»: what plays, with the programme at the playing position (live or archive); none when nothing plays. */
    private fun watchingCard(): HomeCard? {
        val ch = cur ?: return null
        return HomeCard(HomeRows.WATCHING_KEY, HomeKind.WATCHING, ch, playingProgramme() ?: HomeRows.NO_PROGRAMME)
    }

    /** The playing position moved on to another programme (or the guide for it came in) since Home last built the card. */
    private fun watchingStale(): Boolean {
        val shown = homeRows.firstOrNull()?.takeIf { it.kind == HomeKind.WATCHING }?.cards?.firstOrNull()
        val fresh = watchingCard()
        return shown?.ch != fresh?.ch || shown?.p != fresh?.p
    }

    private fun buildHomeRows(): List<HomeRow> {
        val rows = ArrayList<HomeRow>()
        watchingCard()?.let { rows += HomeRow(HomeKind.WATCHING, listOf(it)) }
        val cont = homeContinue()
        if (cont.isNotEmpty()) rows += HomeRow(HomeKind.CONTINUE, cont.map { HomeCard(HomeRows.continueKey(it.ch, it.p), HomeKind.CONTINUE, it.ch, it.p, it) })
        val src = homeSources()
        if (src.isEmpty()) { rows += HomeRow(HomeKind.SETUP, emptyList()); return rows }
        val ids = src.mapTo(HashSet()) { it.id }
        val slots = homeIndex.slots.filter { it.ch.id in ids }
        val t = now()
        fun add(kind: HomeKind, cards: List<HomeCard>) { if (cards.isNotEmpty()) rows += HomeRow(kind, cards) }
        add(HomeKind.NOW, HomePicks.now(slots, t, HOME_NOW_MAX))
        add(HomeKind.STARTED, HomePicks.started(slots, t, ::canArchive, HOME_ROW_MAX))
        add(HomeKind.SOON, HomePicks.soon(slots, t, HOME_ROW_MAX))
        if (tmdbActive) {
            val (films, series) = tmdbRows(slots, t)
            add(HomeKind.FILMS, films)
            add(HomeKind.SERIES, series)
        }
        return rows
    }

    /** Folds what changed into the open Home without moving the focused card; [drop]: a card to remove even if focused. */
    private fun refreshHome(drop: String? = null) {
        if (layer != Layer.HOME) return
        ensureHomeIndex()
        val focused = homeCard()?.key?.takeIf { it != drop }
        val kind = homeRows.getOrNull(homeRowIdx)?.kind
        homeRows = HomeRows.merge(homeRows, buildHomeRows(), focused)
        val r = homeRows.indexOfFirst { it.kind == kind }.takeIf { it >= 0 } ?: homeRowIdx.coerceIn(0, max(0, homeRows.size - 1))
        homeRowIdx = r
        val cards = homeRows.getOrNull(r)?.cards.orEmpty()
        homeColIdx = cards.indexOfFirst { it.key == focused }.takeIf { it >= 0 } ?: homeColIdx.coerceIn(0, max(0, cards.size - 1))
    }

    /**
     * «Фільми» and «Серіали»: programmes of the sources on now or later today that TMDB matched as a film or a show,
     * what is on now or within the hour first, then by rating; one card per film. Asks TMDB about what it doesn't know
     * yet: what is on now first, then later programmes that look like films or episodes (a prefix, a year, an episode
     * marker, or feature length). Only these, never the whole playlist.
     */
    private fun tmdbRows(slots: List<Slot>, t: Long): Pair<List<HomeCard>, List<HomeCard>> {
        val end = dayStart(t) + DAY
        val asksNow = ArrayList<Tmdb.Ask>()
        val asksLater = ArrayList<Tmdb.Ask>()
        val films = ArrayList<Pair<HomeCard, TmdbInfo>>()
        val series = ArrayList<Pair<HomeCard, TmdbInfo>>()
        for (s in slots) {
            var n = 0
            for (p in s.progs) {
                if (p.end <= t || p.start >= end) continue
                if (++n > 10) break
                if (Filler.isFiller(p.title)) continue
                val info = tmdb.get(p.title)
                if (info == null) {
                    if (tmdb.known(p.title)) continue
                    val ask = Tmdb.Ask(p.title, (p.duration / MIN).toInt())
                    if (p.isNow(t)) asksNow += ask else if (p.duration >= 75 * MIN || TMDB_HINT.containsMatchIn(p.title)) asksLater += ask
                    continue
                }
                val kind = if (info.type == "movie") HomeKind.FILMS else HomeKind.SERIES
                (if (kind == HomeKind.FILMS) films else series) += HomeCard(HomeRows.progKey(kind, s.ch, p), kind, s.ch, p) to info
            }
        }
        tmdb.want(asksNow + asksLater)
        fun order(l: List<Pair<HomeCard, TmdbInfo>>) = l
            .sortedWith(compareBy<Pair<HomeCard, TmdbInfo>> { if (it.first.p.start <= t + HOUR) 0 else 1 }.thenByDescending { it.second.rating }.thenBy { it.first.p.start })
            .distinctBy { it.second.type + it.second.id }.take(HOME_ROW_MAX).map { it.first }
        return order(films) to order(series)
    }

    /** What TMDB matched for [p], if it was looked up; never asks (see [tmdbRows], [homeFocused]). */
    fun tmdbInfo(p: Programme): TmdbInfo? {
        tmdbVersion
        return if (tmdbActive && p.title.isNotBlank()) tmdb.get(p.title) else null
    }

    /** The focused card's programme is looked up first, whatever row it is in. */
    private fun homeFocused() {
        if (!tmdbActive) return
        val c = homeCard() ?: return
        if (c.p.title.isBlank() || (c.mark == null && Filler.isFiller(c.p.title))) return
        tmdb.want(listOf(Tmdb.Ask(c.p.title, (c.p.duration / MIN).toInt())), first = true)
    }

    private fun switchTmdb(v: Boolean) {
        if (v && tmdbToken == null) { showToast(str(R.string.tmdb_need_key)); return }
        tmdbOn = v
        prefs.tmdbEnabled = v
        tmdbKeyNow = if (tmdbActive) tmdbToken else null
        cfgVersion++
    }

    /** «Свій ключ TMDB» from the LAN page: checked with TMDB before it is kept; «-» goes back to the built-in one. */
    private fun setTmdbKey(v: String) {
        val key = v.trim()
        if (key == "-" || key.isEmpty()) {
            tmdbOwnKey = null
            prefs.tmdbKey = null
            tmdbKeyNow = if (tmdbActive) tmdbToken else null
            tmdb.reset()
            cfgVersion++
            pop()
            showToast(str(R.string.tmdb_key_removed))
            return
        }
        showToast(str(R.string.tmdb_key_checking))
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Tmdb.check(http, key) }
            if (!ok) { showToast(str(R.string.tmdb_key_bad)); return@launch }
            tmdbOwnKey = key
            prefs.tmdbKey = key
            if (!tmdbOn) { tmdbOn = true; prefs.tmdbEnabled = true }
            tmdbKeyNow = tmdbToken
            tmdb.reset()
            cfgVersion++
            if (sheets.lastOrNull() == Sheet.LINK && linkPurpose == LinkPurpose.TMDB_KEY) pop()
            showToast(str(R.string.tmdb_key_saved))
        }
    }

    /** Reads the sources' now/next again when they changed or it is old; otherwise nothing. */
    private fun ensureHomeIndex() {
        homeIndex.ensure(homeSources(), guide)
        homeLoading = homeIndex.building && homeIndex.slots.isEmpty()
    }

    /** The focused card; null on the «Налаштувати джерела» button. */
    fun homeCard(): HomeCard? = homeRows.getOrNull(homeRowIdx)?.cards?.getOrNull(homeColIdx)

    /** ◀▶ cards, ▲▼ rows (each row remembers its card), OK acts, BACK back to playback (with nothing playing yet, it
     *  just leaves Home); ◀ on a row's first card opens the menu, the way ◀ on the groups does in the list. Focus
     *  starts on «Зараз дивитесь» when something plays. */
    private fun homeKey(k: Key) {
        val row = homeRows.getOrNull(homeRowIdx)
        val n = if (row?.kind == HomeKind.SETUP) 1 else row?.cards?.size ?: 0
        fun goRow(r: Int) {
            if (r !in homeRows.indices || r == homeRowIdx) return
            homeRows.getOrNull(homeRowIdx)?.let { homeCols[it.kind] = homeColIdx }
            homeRowIdx = r
            val m = homeRows[r].cards.size
            homeColIdx = (homeCols[homeRows[r].kind] ?: 0).coerceIn(0, max(0, m - 1))
        }
        when (k) {
            Key.UP -> goRow(homeRowIdx - 1)
            Key.DOWN -> goRow(homeRowIdx + 1)
            Key.CHUP -> goRow(0)
            Key.CHDOWN -> goRow(homeRows.size - 1)
            Key.LEFT -> if (homeColIdx > 0) homeColIdx-- else openMenu()
            Key.RIGHT -> if (homeColIdx < n - 1) homeColIdx++
            Key.OK -> if (row?.kind == HomeKind.SETUP) openHomeSources() else homeCard()?.let(::homeOk)
            Key.BACK -> closeHome()
            Key.RED -> homeCard()?.ch?.let(::toggleFav)
            else -> Unit
        }
        if (k == Key.UP || k == Key.DOWN || k == Key.LEFT || k == Key.RIGHT || k == Key.CHUP || k == Key.CHDOWN) homeFocused()
    }

    /** OK on a card: «Зараз дивитесь» goes back to the playback as it is, the bookmark resumes, «Щойно почалось»
     *  starts from the beginning, a live programme plays live, a past one from the archive; one still to come only says
     *  when it starts. */
    private fun homeOk(c: HomeCard) {
        val p = c.p
        when {
            c.kind == HomeKind.WATCHING -> closeHome()
            c.kind == HomeKind.STARTED && p.isNow() && canArchive(c.ch) -> playProgramme(c.ch, p, fromStart = true)
            p.isNow() || p.isPast() -> playProgramme(c.ch, p)
            else -> showToast(str(R.string.home_starts_at, dayWord(p.start).lowercase(), hm(p.start), c.ch.name))
        }
    }

    /** Long OK: the bookmark menu where there is a bookmark; on a «Щойно почалось» card, the broadcast instead; on a
     *  live programme, from the start; on «Зараз дивитесь» and on one to come, the channel menu. */
    private fun homeLongOk() {
        val c = homeCard() ?: return
        when {
            c.kind == HomeKind.WATCHING -> openCtx(c.ch)
            c.mark != null -> openMarkCtx(c.mark)
            c.kind == HomeKind.STARTED && c.p.isNow() -> playProgramme(c.ch, c.p)
            c.p.isNow() || c.p.isPast() -> longOkProgramme(c.ch, c.p)
            else -> openCtx(c.ch)
        }
    }

    /** «Налаштувати джерела» on Home: the sources sheet right over it; BACK comes back to Home. */
    private fun openHomeSources() {
        closeSheets()
        openSheet(Sheet.HOME_SOURCES)
    }

    // ---------- sources («Головна сторінка» → «Джерела»)

    /** How many of [g]'s channels feed Home. */
    fun homePicked(g: Group): Int = when {
        g.name in homeGroups -> g.channels.size
        else -> homeChannels[g.name]?.let { ids -> g.channels.count { it.id in ids } } ?: 0
    }

    fun isHomeChannel(g: Group, ch: Channel) = g.name in homeGroups || homeChannels[g.name]?.contains(ch.id) == true

    /** The channels Home is built from, in playlist order: hidden channels left out, at most [HOME_SOURCE_MAX]. */
    fun homeSources(): List<Channel> {
        if (homeGroups.isEmpty() && homeChannels.isEmpty()) return emptyList()
        val out = ArrayList<Channel>()
        for (g in allGroups) {
            val all = g.name in homeGroups
            val ids = homeChannels[g.name]
            if (!all && ids == null) continue
            for (c in g.channels) if ((all || c.id in ids!!) && c.id !in hidden) { out += c; if (out.size >= HOME_SOURCE_MAX) return out }
        }
        return out
    }

    private fun openHomeGroup(g: Group) {
        homeGroup = g
        openSheet(Sheet.HOME_GROUP)
    }

    /** «Усі канали групи»: everything when not all of it was picked, else nothing. */
    private fun toggleHomeGroup(g: Group) {
        val all = homePicked(g) < g.channels.size
        homeGroups = if (all) homeGroups + g.name else homeGroups - g.name
        homeChannels.remove(g.name)
        saveHomeSources(g)
    }

    /** A whole group stays whole (channels the provider adds later come in too) until one of its channels is unticked;
     *  ticking the last missing one makes it whole again. */
    private fun toggleHomeChannel(g: Group, ch: Channel) {
        val ids = if (g.name in homeGroups) g.channels.mapTo(HashSet()) { it.id } else HashSet(homeChannels[g.name].orEmpty())
        if (!ids.add(ch.id)) ids.remove(ch.id)
        homeGroups = homeGroups - g.name
        homeChannels.remove(g.name)
        when {
            g.channels.all { it.id in ids } -> homeGroups = homeGroups + g.name
            ids.isNotEmpty() -> homeChannels[g.name] = ids
        }
        saveHomeSources(g)
    }

    private fun saveHomeSources(g: Group) {
        prefs.homeGroups = homeGroups
        prefs.setHomeChannels(g.name, homeChannels[g.name].orEmpty())
        cfgVersion++
    }

    private fun changeStartHome(v: Boolean) {
        if (v == startHome) return
        startHome = v
        prefs.startHome = v
        cfgVersion++
    }

    // ============================================================ the Android TV home screen

    private var tvHomeJob: Job? = null

    /** Publishes what to continue and what is on now to the home screen ([TvHome]), a few seconds after the last change
     *  (marks change in bursts), or at once when the app goes to the background. */
    private fun syncTvHome(now: Boolean = false) {
        if (!TvHome.supported || channels.isEmpty()) return
        tvHomeJob?.cancel()
        val app = getApplication<Application>()
        tvHomeJob = viewModelScope.launch {
            if (!now) delay(5000)
            val s = tvHomeSnapshot() ?: return@launch
            val pl = playlist?.id
            val hasSources = homeSources().isNotEmpty()
            withContext(tvHomeIo) {
                // the index not read yet this session: keep what was on the channel rather than empty it
                val keep = if (s.live.isEmpty() && hasSources) TvHome.load(app)?.live?.filter { it.playlist == pl }.orEmpty() else s.live
                val out = TvHome.Snapshot(s.cont, keep)
                TvHome.save(app, out)
                TvHome.publish(app, out)
            }
        }
    }

    private val tvHomeIo = Dispatchers.IO.limitedParallelism(1)

    /** Bookmarks of this playlist, and the Home sources with their next few programmes and the pictures TMDB has
     *  already found for them (nothing is looked up for this). */
    private fun tvHomeSnapshot(): TvHome.Snapshot? {
        val pl = playlist?.id ?: return null
        val t = now()
        fun prog(p: Programme): TvHome.Prog {
            val i = if (tmdbActive) tmdb.get(p.title) else null
            return TvHome.Prog(p.start, p.end, p.title, i?.backdropUrl, i?.let { it.type == "movie" })
        }
        val cont = homeContinue().take(8).map { m ->
            TvHome.Cont(pl, m.ch.id, m.ch.name, m.ch.logo, prog(m.p), m.pos, m.at, m.p.start + m.ch.archiveDays * DAY)
        }
        val ids = homeSources().mapTo(HashSet()) { it.id }
        val live = homeIndex.slots.filter { it.ch.id in ids }.mapNotNull { s ->
            val ps = s.progs.filter { it.end > t && !Filler.isFiller(it.title) }.take(6)
            if (ps.isEmpty()) null else TvHome.Live(pl, s.ch.id, s.ch.name, s.ch.logo, ps.map(::prog))
        }.take(24)
        return TvHome.Snapshot(cont, live)
    }

    /** A link from the home screen. Overrides «При запуску»; waits for the channels when they are still loading, and
     *  switches playlists when it is for another one. */
    fun openDeepLink(l: DeepLink) {
        startupPending = false
        pendingLink = l
        if (l is DeepLink.Live || l is DeepLink.Archive) {
            val pl = if (l is DeepLink.Live) l.playlist else (l as DeepLink.Archive).playlist
            if (pl != playlist?.id) {
                val info = repo.get(pl)
                if (info == null) pendingLink = DeepLink.Home // a playlist that is gone: Home
                else { closeSheets(); openPlaylist(info, download = true); return } // applied once its channels are in
            }
        }
        if (channels.isNotEmpty()) applyDeepLink()
    }

    private fun applyDeepLink() {
        val l = pendingLink ?: return
        pendingLink = null
        Log.i(TAG, "link: ${l.uri()}")
        closeSheets(); closeCtx(); markCtx = null
        val chId = when (l) { is DeepLink.Live -> l.channel; is DeepLink.Archive -> l.channel; DeepLink.Home -> null }
        val ch = chId?.let(byId::get)
        when {
            l == DeepLink.Home || ch == null -> { if (cur == null) shown.firstOrNull()?.let { tune(it) }; openHome() }
            l is DeepLink.Archive -> {
                closeLayers()
                // the archive moved on since the link was made: the broadcast instead
                if (!canArchive(ch) || l.start < now() - ch.archiveDays * DAY) { tune(ch); showToast(str(R.string.link_archive_gone)) }
                else tune(ch, l.start + l.position)
            }
            else -> { closeLayers(); if (cur != ch || mode != Mode.LIVE) tune(ch) else showBanner() }
        }
    }

    // ============================================================ keys

    private var keyAt = 0L
    /** When the key that opened the list was pressed (uptime ms): the list logs how long it took to appear. */
    var listKeyAt = 0L; private set
    private var holdKey: Key? = null
    private var holdFired = false
    private var holdStart = 0L

    /** Returns true if the key was consumed. */
    fun onKey(e: KeyEvent): Boolean {
        val k = map(e.keyCode) ?: return false
        lastKeyAt = SystemClock.uptimeMillis()
        keyAt = e.eventTime
        if (channels.isEmpty()) return false
        // no long presses in dialogs and the player panel: OK acts on press, and holding it doesn't repeat it
        val dialog = ctx != null || markCtx != null || sheets.isNotEmpty() || (layer == Layer.NONE && osd)
        if (dialog && k == Key.OK && e.repeatCount > 0) return true
        val holdable = !dialog && k == Key.OK
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (holdable) {
                    if (e.repeatCount == 0) { holdKey = k; holdFired = false; holdStart = e.eventTime }
                    else if (holdKey == k && !holdFired && (e.isLongPress || e.eventTime - holdStart >= 450)) { holdFired = true; longPress(k) }
                    return true
                }
                if (k == Key.BACK && e.repeatCount > 0) return true
                press(k, e.repeatCount)
            }
            KeyEvent.ACTION_UP -> if (holdKey == k) {
                holdKey = null
                if (!holdFired) press(k, 0)
            }
        }
        return true
    }

    private fun longPress(k: Key) {
        when (k) {
            Key.OK -> when (layer) {
                Layer.LIST -> listLongOk()
                Layer.HOME -> homeLongOk()
                Layer.NONE -> cur?.let(::openCtx)
            }
            else -> Unit
        }
    }

    private fun press(k: Key, repeat: Int) {
        if (k == Key.PLAYPAUSE) { togglePause(); return } // media keys pause anywhere
        if (ctx != null) { ctxKey(k); return }
        if (markCtx != null) { markCtxKey(k); return }
        sheets.lastOrNull()?.let { sheetKey(it, k); return }
        if (k.ordinal >= Key.D0.ordinal) { digit(k.ordinal - Key.D0.ordinal); return }
        if (k == Key.MENU) { openMenu(); return }
        if (layer == Layer.LIST) { listKey(k); return }
        if (layer == Layer.HOME) { homeKey(k); return }
        if (osd) { panelKey(k, repeat); return }
        // plain playback: the same keys in live and in the archive
        when (k) {
            Key.UP, Key.CHUP -> zap(1)
            Key.DOWN, Key.CHDOWN -> zap(-1)
            Key.LEFT -> openList()
            Key.RIGHT -> openList(programmes = true)
            Key.OK -> showOsd()
            Key.BACK -> openHome() // and BACK there comes back here: BACK always leads back to playback
            Key.RED -> cur?.let(::toggleFav)
            Key.GREEN -> openList(programmes = true)
            Key.YELLOW -> openRecent()
            Key.INFO -> if (banner) banner = false else showBanner()
            else -> Unit
        }
    }

    /** YELLOW / LAST_CHANNEL: «Нещодавні», on the channel watched before this one. */
    private fun openRecent() {
        listKeyAt = keyAt
        banner = false; osd = false
        groupId = "recent"
        syncGroup()
        val chs = currentGroup()?.channels.orEmpty()
        chIdx = if (chs.size > 1 && chs.first() == cur) 1 else 0
        focus = Focus.CHANNELS
        layer = Layer.LIST
    }

    private fun digit(d: Int) {
        numBuf = (numBuf + d).takeLast(4)
        numJob?.cancel()
        numJob = viewModelScope.launch {
            delay(1300)
            val ch = channelByNum(numBuf.toIntOrNull() ?: -1)
            numBuf = ""
            if (ch == null) { showToast(str(R.string.no_such_number)); return@launch }
            if (layer == Layer.LIST) {
                groupId = "all"; syncGroup(); chIdx = shown.indexOf(ch); focus = Focus.CHANNELS
            } else { closeLayers(); tune(ch) }
        }
    }

    fun channelByNum(n: Int) = shown.firstOrNull { it.num == n }

    companion object {
        const val DONE = .92f
        const val RECENT_MAX = 20
        /** Cards per Home row at most. */
        const val HOME_ROW_MAX = 20
        /** «Зараз в ефірі» shows more: it is one card per channel. */
        const val HOME_NOW_MAX = 60
        /** Home reads the guide of at most this many source channels, however large the groups picked. */
        const val HOME_SOURCE_MAX = 300
        /** Settings → «Масштаб інтерфейсу»: the steps, percent; 100 is the default. */
        val UI_SCALES = listOf(90, 100, 110, 125, 150)
        private const val TAG = "tivibro"
        private const val OPEN = Long.MAX_VALUE
        const val USER_AGENT = "TiviMate/5.1.6 (Android 12)"
        private val ADULT = Regex("adult|18\\+|взросл|доросл", RegexOption.IGNORE_CASE)
        /** Where the provider sends an archive it doesn't have (302 Location, segment names). */
        private val STUB = Regex("noarch|/stub", RegexOption.IGNORE_CASE)
        private const val NO_ARCHIVE = "X-TiViBro-No-Archive"
        /** A later programme worth a TMDB lookup even when it is short: it says it is a film or an episode, or has a year. */
        private val TMDB_HINT = Regex("""^\s*(х|x|м|д|т)\s*[/\\.]\s*(ф|с)|\b(19|20)\d{2}\b|сезон|серия|серія|season|staffel|\bs\d{1,2}\s*e\d""", RegexOption.IGNORE_CASE)

        fun isEpgTeam(url: String) = PlaylistInfo.hostOf(url).let { it == "epg.team" || it.endsWith(".epg.team") }

        /**
         * A buffer a 1–1.5 GB TV can afford: Media3's default keeps up to 50 s whatever the bitrate (≈30 MB of HD) on
         * top of a ~130 MB byte target. Here it stops at a byte cap first: 16 MB / 30 s on low-RAM devices, else
         * 24 MB / 50 s. Live HLS needs little more than a few segments; the archive refills as fast as it plays.
         */
        private fun loadControl(app: Application): DefaultLoadControl {
            val am = app.getSystemService(ActivityManager::class.java)
            val low = am.isLowRamDevice || am.memoryClass <= 128
            Log.i(TAG, "player buffer: ${if (low) "low-RAM" else "normal"} (memoryClass ${am.memoryClass} MB)")
            return DefaultLoadControl.Builder()
                .setBufferDurationsMs(if (low) 15_000 else 30_000, if (low) 30_000 else 50_000, 2_500, 5_000)
                .setTargetBufferBytes(if (low) 16 shl 20 else 24 shl 20)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        }

        fun map(code: Int): Key? = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> Key.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.OK
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> Key.BACK
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> Key.CHUP
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> Key.CHDOWN
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE -> Key.PLAYPAUSE
            KeyEvent.KEYCODE_PROG_RED -> Key.RED
            KeyEvent.KEYCODE_PROG_GREEN, KeyEvent.KEYCODE_GUIDE -> Key.GREEN
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_LAST_CHANNEL -> Key.YELLOW
            KeyEvent.KEYCODE_PROG_BLUE -> Key.BLUE
            KeyEvent.KEYCODE_INFO -> Key.INFO
            KeyEvent.KEYCODE_MENU -> Key.MENU
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> Key.entries[Key.D0.ordinal + code - KeyEvent.KEYCODE_0]
            else -> null
        }

        fun dayStart(t: Long): Long = Calendar.getInstance().apply {
            timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun ago(ms: Long): String {
            val m = (ms / MIN).toInt().coerceAtLeast(0)
            return if (m < 60) str(R.string.dur_min, m) else if (m % 60 == 0) str(R.string.dur_hours, m / 60) else str(R.string.dur_hours_min, m / 60, m % 60)
        }
    }
}

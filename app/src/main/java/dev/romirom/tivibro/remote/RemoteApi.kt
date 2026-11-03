package dev.romirom.tivibro.remote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import dev.romirom.tivibro.MainActivity
import dev.romirom.tivibro.R
import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.Programme
import dev.romirom.tivibro.data.inArchive
import dev.romirom.tivibro.data.now
import dev.romirom.tivibro.search.ProgrammeHit
import dev.romirom.tivibro.search.When
import dev.romirom.tivibro.search.searchChannels
import dev.romirom.tivibro.str
import dev.romirom.tivibro.tvhome.DeepLink
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the phone page and the MCP tools can ask of the TV. Every answer is JSON with channel ids and programme start
 * times (epoch ms) that can be passed straight back to [play]. Failures are `{"ok":false,"error":…}`, worded for a
 * person or a language model to act on.
 */
interface Tv {
    /** True while the programme index is (re)built: programme search may miss things for a few seconds. */
    val indexing: Boolean
    fun channels(q: String, limit: Int): JSONArray
    fun programmes(q: String, limit: Int): JSONArray
    /** [channel]: id, number or name; null: the favourites. */
    fun whatsOn(channel: String?): JSONObject
    fun nowPlaying(): JSONObject
    /** [channel]: id, number or name. [start]: a programme's start (ms) to play from the archive or from its start. */
    fun play(channel: String, start: Long?, fromStart: Boolean): JSONObject
    /** pause, resume, playpause, next, prev, live. */
    fun control(action: String): JSONObject
    /** The favourites, then the playlist's groups, with what the user hid left out: id, name, count. */
    fun groups(): JSONObject
    /** One page of a group's channels ([group]: an id from [groups], or a name), each with what is on now. */
    fun groupChannels(group: String, offset: Int, limit: Int): JSONObject
    /** [channel]'s programmes on day [day] (0 today, -1 yesterday, 1 tomorrow) and the days it has. */
    fun schedule(channel: String, day: Int): JSONObject
    /** The favourites in the user's order, with what is on now. */
    fun favourites(): JSONObject
    fun setFavourite(channel: String, on: Boolean): JSONObject
    /** «Продовжити перегляд»: unfinished archive programmes, newest first, with how far along each is. */
    fun bookmarks(): JSONObject
    /** Resumes a bookmark ([channel] id and programme [start]); both null: the most recent one. */
    fun resume(channel: String?, start: Long?): JSONObject
    /** Seeks by [seconds] (negative: back) or [to] (epoch ms, or a local time like «21:30»). */
    fun seek(seconds: Long?, to: String?): JSONObject
    /** The audio tracks, after switching to [index] (0-based) when given. */
    fun audio(index: Int?): JSONObject
    /** The TV's system volume, after [set] (0..100 %), [change] (± %) and [mute] when given. */
    fun volume(set: Int?, change: Int?, mute: Boolean?): JSONObject
}

/** [Tv] over [Remote]'s snapshots, plus the JSON routes the phone page calls (`/api/…`). */
class RemoteApi(private val app: Context) : Tv {
    private val st get() = Remote.state
    private val idx get() = Remote.indexer.index
    private val vol by lazy { Volume(app) }

    override val indexing get() = Remote.indexer.building

    fun handle(method: String, path: String, q: Map<String, String>, body: String): Http.Response {
        val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        val out: Any = when (path) {
            "search" -> JSONObject().put("channels", channels(q["q"].orEmpty(), 12)).put("programmes", programmes(q["q"].orEmpty(), 40))
                .put("indexing", indexing).put("ready", st.channels.isNotEmpty())
            "channels" -> channels(q["q"].orEmpty(), limit)
            "programmes" -> programmes(q["q"].orEmpty(), limit)
            "whatson" -> whatsOn(q["channel"])
            "groups" -> groups()
            "group" -> groupChannels(q["id"].orEmpty(), q["offset"]?.toIntOrNull() ?: 0, q["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 40)
            "schedule" -> schedule(q["channel"].orEmpty(), q["day"]?.toIntOrNull() ?: 0)
            "now" -> nowPlaying()
            "home" -> home()
            "hidden" -> hiddenList()
            "programme" -> programme(q["channel"].orEmpty(), q["start"]?.toLongOrNull() ?: 0)
            // GET: the state; POST: `level` (0..max) or `by` (steps) as the page's slider and ± send, `set` / `change` in %, `mute`
            "volume" -> if (method != "POST") volume(null, null, null) else {
                val o = runCatching { JSONObject(body) }.getOrElse { return Http.Response.json("""{"error":"bad json"}""", 400) }
                val mute = if (o.has("mute")) o.optBoolean("mute") else null
                fun int(k: String) = if (o.has(k)) o.optInt(k) else null
                if (o.has("level") || o.has("by")) onVolume { it.apply(int("level"), int("by"), mute) } else volume(int("set"), int("change"), mute)
            }
            "play", "key", "seek", "audio", "fav", "fav/move", "hide", "group/show", "bookmark/forget" -> {
                if (method != "POST") return Http.Response.json("""{"error":"POST only"}""", 405)
                val o = runCatching { JSONObject(body) }.getOrElse { return Http.Response.json("""{"error":"bad json"}""", 400) }
                val ch = o.optString("channelId", o.optString("channel"))
                when (path) {
                    "play" -> play(ch, o.optLong("start", 0).takeIf { it > 0 }, o.optBoolean("fromStart"), o.optBoolean("resume"))
                    // what the player does now comes back with the answer: the phone shows it without polling again
                    "key" -> withNow(control(o.optString("key")))
                    "seek" -> withNow(seekMs(if (o.has("by")) o.optLong("by") else null, o.opt("to")?.let { if (it is Number) it.toLong().toString() else it.toString() }?.ifBlank { null }))
                    "audio" -> audio(o.optInt("index", -1).takeIf { it >= 0 })
                    "fav" -> setFavourite(ch, o.optBoolean("on", true))
                    "fav/move" -> moveFavourite(ch, o.optInt("dir"))
                    "hide" -> setHidden(ch, o.optBoolean("hidden", true))
                    "group/show" -> setGroupShown(o.optString("group"), o.optBoolean("shown", true))
                    else -> forgetBookmark(ch, o.optLong("start"))
                }
            }
            else -> return Http.Response.json("""{"error":"not found"}""", 404)
        }
        return Http.Response.json(out.toString())
    }

    // ------------------------------------------------------------------ Tv

    override fun channels(q: String, limit: Int): JSONArray {
        val t = now()
        val a = JSONArray()
        for (h in searchChannels(st.visible, q, limit)) a.put(channelJson(h.ch, t))
        return a
    }

    override fun programmes(q: String, limit: Int): JSONArray {
        val shown = st.shown
        val a = JSONArray()
        for (h in idx.search(q, now(), limit) { it.id in shown }) a.put(progJson(h))
        return a
    }

    override fun whatsOn(channel: String?): JSONObject {
        val t = now()
        val chs = if (channel.isNullOrBlank()) st.favs.mapNotNull(st.byId::get).filter { it.id in st.shown }.take(30)
        else listOfNotNull(resolve(channel) ?: return fail(noChannel(channel)))
        if (chs.isEmpty()) return fail("No favourite channels. Name a channel instead.")
        val a = JSONArray()
        for (ch in chs) {
            val o = channelJson(ch, t, now = false)
            o.put("programmes", JSONArray().also { p -> idx.whatsOn(ch, t, if (chs.size == 1) 3 else 1).forEach { p.put(progJson(it, withChannel = false)) } })
            a.put(o)
        }
        return ok().put("channels", a)
    }

    override fun nowPlaying(): JSONObject {
        val t = now()
        val tg = Remote.target ?: return ok().put("playing", false).put("foreground", false).put("serverNow", t)
        val p = Remote.onMain { tg.playing() } ?: return ok().put("playing", false).put("foreground", Remote.foreground).put("serverNow", t)
        val prog = p.p?.takeIf { it.end > it.start } ?: idx.whatsOn(p.ch, p.pos, 0).firstOrNull()?.p
        return ok().put("playing", true).put("foreground", Remote.foreground).put("serverNow", t)
            .put("channel", channelJson(p.ch, t, now = false))
            .put("mode", if (p.live) "live" else "archive").put("paused", p.paused).put("position", p.pos).put("positionLocal", local(p.pos))
            .put("programme", prog?.let {
                JSONObject().put("title", it.title).put("start", it.start).put("end", it.end).put("local", span(it))
                    .put("desc", p.desc?.takeIf { d -> d.isNotBlank() && p.p === it } ?: JSONObject.NULL)
            })
            // what the phone's player panel may offer: seeking (a channel with an archive), the buttons, the audio tracks
            .put("canSeek", p.canSeek).put("earliest", if (p.canSeek) t - p.ch.archiveDays * DAY else t)
            .put("can", JSONArray(p.can.toList()))
            .put("audio", JSONArray().also { a -> p.audio.forEach { a.put(trackJson(it)) } })
    }

    override fun play(channel: String, start: Long?, fromStart: Boolean): JSONObject = play(channel, start, fromStart, resume = false)

    /** [resume]: a bookmark is continued even while its programme is still on air (else that plays live). */
    fun play(channel: String, start: Long?, fromStart: Boolean, resume: Boolean): JSONObject {
        if (st.channels.isEmpty()) return fail(NO_PLAYLIST)
        val ch = resolve(channel) ?: return fail(noChannel(channel))
        val t = now()
        var p: Programme? = null
        if (start != null) {
            // the guide's programme when the index has it; else the moment itself, as a one-millisecond «programme»
            val g = idx.find(ch, start) ?: Programme(start, start + 1, "", "")
            when {
                g.start > t -> return fail("«${g.title.ifEmpty { "That programme" }}» has not started yet (starts ${local(g.start)}).")
                (g.end <= t || fromStart) && ch.archiveDays <= 0 -> return fail("${ch.name} has no archive: only live is possible.")
                g.end <= t && !ch.inArchive(g, t) -> return fail("That programme is older than ${ch.name}'s archive (${ch.archiveDays} days).")
            }
            p = if (g.isNow(t) && !fromStart && !resume) null else g
        }
        val what = p?.let { "«${it.title.ifEmpty { local(it.start) }}» on ${ch.name}" + if (it.isNow(t) && fromStart) " from the start" else " from the archive" }
            ?: "${ch.name} live"
        val tg = Remote.target
        if (tg != null && Remote.foreground) {
            Remote.onMain { tg.play(ch, p, fromStart); true } ?: return fail(NO_ANSWER)
            return ok().put("message", "Playing $what.").put("channel", channelJson(ch, t, now = false))
        }
        // not on screen: the same link the home screen uses brings the activity up and plays (if Android lets it)
        val pl = st.playlist ?: return fail(NO_PLAYLIST)
        val link = if (p == null) DeepLink.Live(pl, ch.id) else DeepLink.Archive(pl, ch.id, p.start, 0)
        runCatching {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.uri())).setClass(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { return fail("Could not open TiViBro: ${it.message}") }
        val until = SystemClock.uptimeMillis() + 4000
        while (!Remote.foreground && SystemClock.uptimeMillis() < until) Thread.sleep(100)
        return if (Remote.foreground) ok().put("message", "Opened TiViBro on the TV, playing $what.")
        else fail("TiViBro is in the background and Android did not let it come to the front. Open TiViBro on the TV, then try again.")
    }

    override fun control(action: String): JSONObject {
        if (action !in Remote.KEYS) return fail("Unknown action «$action». Use ${Remote.KEYS.joinToString()}.")
        val tg = foregroundTarget() ?: return fail(NOT_ON_SCREEN)
        val r = Remote.onMain { Result.success(tg.key(action)) } ?: return fail(NO_ANSWER)
        return r.getOrNull()?.let(::fail) ?: ok().put("message", "Done: $action.")
    }

    override fun groups(): JSONObject {
        if (st.channels.isEmpty()) return fail(NO_PLAYLIST)
        val a = JSONArray()
        for (g in Browse.groups(st, str(R.string.group_fav))) a.put(JSONObject().put("id", g.id).put("name", g.name).put("count", g.channels.size))
        return ok().put("groups", a)
    }

    override fun groupChannels(group: String, offset: Int, limit: Int): JSONObject {
        if (st.channels.isEmpty()) return fail(NO_PLAYLIST)
        val chs = Browse.channels(st, group) ?: return fail("No group «$group». Use list_groups.")
        val t = now()
        val from = offset.coerceIn(0, chs.size)
        val a = JSONArray()
        // a page at a time: «now» costs a title read per channel, and a provider group can hold a thousand channels
        for (ch in chs.subList(from, minOf(chs.size, from + limit))) a.put(channelJson(ch, t))
        return ok().put("total", chs.size).put("offset", from).put("channels", a)
    }

    override fun schedule(channel: String, day: Int): JSONObject {
        if (st.channels.isEmpty()) return fail(NO_PLAYLIST)
        val ch = resolve(channel) ?: return fail(noChannel(channel))
        val t = now()
        val days = Browse.days(ch.archiveDays, t)
        val d = day.coerceIn(days.first().first, days.last().first)
        val a = JSONArray()
        for (h in idx.range(ch, Browse.midnight(t, d), Browse.midnight(t, d + 1), t)) a.put(progJson(h, withChannel = false))
        return ok().put("channel", channelJson(ch, t, now = false)).put("day", d)
            .put("days", JSONArray().also { j -> days.forEach { (k, start) -> j.put(JSONObject().put("day", k).put("start", start)) } })
            .put("programmes", a).put("indexing", indexing)
    }

    override fun favourites(): JSONObject {
        if (st.channels.isEmpty()) return fail(NO_PLAYLIST)
        val t = now()
        val a = JSONArray()
        for (ch in Browse.favourites(st)) a.put(channelJson(ch, t))
        return ok().put("channels", a)
    }

    override fun setFavourite(channel: String, on: Boolean): JSONObject {
        val ch = resolve(channel, hiddenToo = true) ?: return fail(noChannel(channel))
        val tg = Remote.target ?: return fail(NOT_RUNNING)
        Remote.onMain { tg.setFav(ch, on); true } ?: return fail(NO_ANSWER)
        return ok().put("message", if (on) "${ch.name} is in the favourites." else "${ch.name} is no longer a favourite.")
            .put("fav", on).put("favs", JSONArray(Remote.state.favs))
    }

    override fun bookmarks(): JSONObject {
        val tg = Remote.target ?: return fail(NOT_RUNNING)
        val ms = Remote.onMain { tg.bookmarks() } ?: return fail(NO_ANSWER)
        val a = JSONArray()
        for (m in ms) {
            a.put(JSONObject().put("channelId", m.ch.id).put("channel", m.ch.name).put("logo", m.ch.logo ?: JSONObject.NULL)
                .put("title", m.p.title).put("start", m.p.start).put("end", m.p.end).put("local", span(m.p))
                .put("position", m.pos).put("progress", (m.watched * 100).toInt()))
        }
        return ok().put("bookmarks", a)
    }

    override fun resume(channel: String?, start: Long?): JSONObject {
        if (channel.isNullOrBlank() || start == null) {
            val b = bookmarks().optJSONArray("bookmarks")?.optJSONObject(0) ?: return fail("Nothing to continue: no unfinished programmes.")
            return play(b.getString("channelId"), b.getLong("start"), false, resume = true)
        }
        return play(channel, start, false, resume = true)
    }

    override fun seek(seconds: Long?, to: String?): JSONObject = seekMs(seconds?.times(1000), to)

    /** [to]: epoch ms, or a local time of day (the most recent one). */
    private fun seekMs(byMs: Long?, to: String?): JSONObject {
        val t = now()
        val at = to?.trim()?.let { s -> s.toLongOrNull()?.takeIf { it > 1_000_000_000_000L } ?: Control.localTime(s, t) ?: return fail("«$s» is not a time: use HH:MM or epoch ms.") }
        if (at == null && byMs == null) return fail("Say how far: seconds (negative goes back) or to (a time).")
        val tg = foregroundTarget() ?: return fail(NOT_ON_SCREEN)
        val r = Remote.onMain { Result.success(tg.seek(byMs, at)) } ?: return fail(NO_ANSWER)
        return r.getOrNull()?.let(::fail) ?: ok()
    }

    override fun audio(index: Int?): JSONObject {
        val tg = (if (index != null) foregroundTarget() else Remote.target) ?: return fail(if (index != null) NOT_ON_SCREEN else NOT_RUNNING)
        val ts = Remote.onMain { tg.audio(index) } ?: return fail(NO_ANSWER)
        if (index != null && index !in ts.indices) return fail("There is no audio track ${index + 1}: there ${if (ts.size == 1) "is 1" else "are ${ts.size}"}.")
        return ok().put("tracks", JSONArray().also { a -> ts.forEach { a.put(trackJson(it)) } })
    }

    override fun volume(set: Int?, change: Int?, mute: Boolean?): JSONObject = onVolume { it.applyPercent(set, change, mute) }

    private fun onVolume(f: (Volume) -> JSONObject): JSONObject = Remote.onMain { f(vol) } ?: fail(NO_ANSWER)

    // ------------------------------------------------------------------ the phone page only

    fun moveFavourite(channel: String, dir: Int): JSONObject {
        val tg = Remote.target ?: return fail(NOT_RUNNING)
        Remote.onMain { tg.moveFav(channel, dir); true } ?: return fail(NO_ANSWER)
        return ok().put("favs", JSONArray(Remote.state.favs))
    }

    fun forgetBookmark(channel: String, start: Long): JSONObject {
        val tg = Remote.target ?: return fail(NOT_RUNNING)
        val r = Remote.onMain { Result.success(tg.forgetBookmark(channel, start)) } ?: return fail(NO_ANSWER)
        return if (r.getOrNull() == true) ok() else fail("No such bookmark.")
    }

    fun setHidden(channel: String, hide: Boolean): JSONObject {
        val ch = st.byId[channel] ?: return fail(noChannel(channel))
        val tg = Remote.target ?: return fail(NOT_RUNNING)
        Remote.onMain { tg.setHidden(ch, hide); true } ?: return fail(NO_ANSWER)
        return ok().put("hidden", hide)
    }

    fun setGroupShown(group: String, on: Boolean): JSONObject {
        if (group !in st.allGroups) return fail("No group «$group».")
        val tg = Remote.target ?: return fail(NOT_RUNNING)
        Remote.onMain { tg.setGroupShown(group, on); true } ?: return fail(NO_ANSWER)
        return ok().put("shown", on)
    }

    /** What Settings → «Групи каналів» and «Приховані канали» list: every group with its switch, the hidden channels. */
    fun hiddenList(): JSONObject {
        if (st.channels.isEmpty()) return fail(NO_PLAYLIST)
        val s = st
        val g = JSONArray()
        for ((name, n) in s.allGroups) g.put(JSONObject().put("name", name).put("count", n).put("shown", name !in s.groupsOff))
        val c = JSONArray()
        for (id in s.hidden) s.byId[id]?.let { c.put(channelJson(it, 0, now = false)) }
        return ok().put("groups", g).put("channels", c)
    }

    /** The phone's «Головна»: what to continue, the channels watched last, now and next on the favourites. */
    fun home(): JSONObject {
        val t = now()
        val o = ok().put("foreground", Remote.foreground).put("ready", st.channels.isNotEmpty())
        o.put("bookmarks", bookmarks().optJSONArray("bookmarks") ?: JSONArray())
        val r = JSONArray()
        for (id in st.recent) { if (r.length() >= RECENT) break; st.byId[id]?.takeIf { it.id in st.shown }?.let { r.put(channelJson(it, t)) } }
        o.put("recent", r)
        o.put("favourites", whatsOn(null).optJSONArray("channels") ?: JSONArray())
        return o
    }

    /** One programme with its description and the bookmark's position; `pending` while the guide still reads them. */
    fun programme(channel: String, start: Long): JSONObject {
        val ch = st.byId[channel] ?: return fail(noChannel(channel))
        val tg = Remote.target ?: return fail(NOT_RUNNING)
        val t = now()
        // the main thread answers from what is decoded; a guide read it queues lands a moment later
        val info = Remote.onMain { Result.success(tg.programme(ch, start)) }?.getOrNull()
        val p = info?.p ?: idx.find(ch, start) ?: return fail("No such programme.").put("pending", info == null)
        val kind = when { p.end <= t -> "archive"; p.start <= t -> "now"; else -> "upcoming" }
        return ok().put("channel", channelJson(ch, t, now = false)).put("title", p.title).put("start", p.start).put("end", p.end)
            .put("local", span(p)).put("when", kind).put("desc", info?.desc?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("resumeAt", info?.markPos ?: JSONObject.NULL).put("inArchive", ch.archiveDays > 0 && ch.inArchive(p, t))
            .put("pending", info == null || info.desc == null)
    }

    // ------------------------------------------------------------------ helpers

    /** The view model, when its activity is on screen: what playback control needs (a stopped player can't seek). */
    private fun foregroundTarget() = Remote.target?.takeIf { Remote.foreground }

    /** [o] with the player's state after it, for the phone to show at once. */
    private fun withNow(o: JSONObject): JSONObject = o.put("now", nowPlaying())

    private fun trackJson(t: Remote.Track) = JSONObject().put("index", t.index).put("name", t.name).put("selected", t.selected)

    /** An id, a channel number, or the best name match, among the channels the user did not hide (an id may name a
     *  hidden one when [hiddenToo]: unstarring a favourite whose channel was hidden since). */
    private fun resolve(s: String, hiddenToo: Boolean = false): Channel? {
        val q = s.trim()
        if (q.isEmpty()) return null
        st.byId[q]?.takeIf { hiddenToo || it.id in st.shown }?.let { return it }
        q.toIntOrNull()?.let { n -> st.visible.firstOrNull { it.num == n }?.let { return it } }
        return searchChannels(st.visible, q, 1).firstOrNull()?.ch
    }

    private fun noChannel(s: String) = if (st.channels.isEmpty()) NO_PLAYLIST else "No channel matches «$s». Try search_channels."

    private fun channelJson(ch: Channel, t: Long, now: Boolean = true): JSONObject {
        val o = JSONObject().put("id", ch.id).put("num", ch.num).put("name", ch.name).put("group", ch.group)
            .put("archiveDays", ch.archiveDays).put("logo", ch.logo ?: JSONObject.NULL).put("fav", ch.id in st.favSet)
        if (now) idx.whatsOn(ch, t, 0).firstOrNull()?.takeIf { it.kind == When.NOW }?.let {
            o.put("now", JSONObject().put("title", it.p.title).put("start", it.p.start).put("end", it.p.end))
        }
        return o
    }

    private fun progJson(h: ProgrammeHit, withChannel: Boolean = true): JSONObject {
        val o = JSONObject()
        if (withChannel) o.put("channelId", h.ch.id).put("channel", h.ch.name).put("num", h.ch.num).put("logo", h.ch.logo ?: JSONObject.NULL)
        return o.put("title", h.p.title).put("start", h.p.start).put("end", h.p.end).put("local", span(h.p))
            .put("when", h.kind.name.lowercase()).put("playable", h.kind != When.UPCOMING)
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg)

    private fun local(t: Long): String = SimpleDateFormat("EEE yyyy-MM-dd HH:mm", Locale.US).format(Date(t))
    private fun span(p: Programme): String = local(p.start) + "–" + SimpleDateFormat("HH:mm", Locale.US).format(Date(p.end))

    private companion object {
        const val NO_PLAYLIST = "TiViBro has no channels loaded yet: open TiViBro on the TV."
        const val NOT_RUNNING = "TiViBro is not running on the TV: open it."
        const val NOT_ON_SCREEN = "TiViBro is not on screen on the TV: open it, then try again."
        const val NO_ANSWER = "The TV app did not respond."
        const val DAY = 24 * 3600_000L
        /** «Нещодавні» on the phone's Home: the channels watched last. */
        const val RECENT = 10
    }
}

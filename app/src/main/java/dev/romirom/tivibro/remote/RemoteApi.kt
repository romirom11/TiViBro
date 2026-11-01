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
}

/** [Tv] over [Remote]'s snapshots, plus the JSON routes the phone page calls (`/api/…`). */
class RemoteApi(private val app: Context) : Tv {
    private val st get() = Remote.state
    private val idx get() = Remote.indexer.index

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
            "play", "key" -> {
                if (method != "POST") return Http.Response.json("""{"error":"POST only"}""", 405)
                val o = runCatching { JSONObject(body) }.getOrElse { return Http.Response.json("""{"error":"bad json"}""", 400) }
                if (path == "key") control(o.optString("key"))
                else play(o.optString("channelId", o.optString("channel")), o.optLong("start", 0).takeIf { it > 0 }, o.optBoolean("fromStart"))
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
        val tg = Remote.target ?: return ok().put("playing", false).put("foreground", false)
        val p = Remote.onMain { tg.playing() } ?: return ok().put("playing", false).put("foreground", Remote.foreground)
        val prog = p.p?.takeIf { it.end > it.start } ?: idx.whatsOn(p.ch, p.pos, 0).firstOrNull()?.p
        return ok().put("playing", true).put("foreground", Remote.foreground).put("channel", channelJson(p.ch, now(), now = false))
            .put("mode", if (p.live) "live" else "archive").put("paused", p.paused).put("position", p.pos).put("positionLocal", local(p.pos))
            .put("programme", prog?.let { JSONObject().put("title", it.title).put("start", it.start).put("end", it.end).put("local", span(it)) })
    }

    override fun play(channel: String, start: Long?, fromStart: Boolean): JSONObject {
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
            p = if (g.isNow(t) && !fromStart) null else g
        }
        val what = p?.let { "«${it.title.ifEmpty { local(it.start) }}» on ${ch.name}" + if (it.isNow(t)) " from the start" else " from the archive" }
            ?: "${ch.name} live"
        val tg = Remote.target
        if (tg != null && Remote.foreground) {
            Remote.onMain { tg.play(ch, p, fromStart); true } ?: return fail("The TV app did not respond.")
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
        val tg = Remote.target?.takeIf { Remote.foreground } ?: return fail("TiViBro is not on screen on the TV.")
        val done = Remote.onMain { tg.key(action) } ?: return fail("The TV app did not respond.")
        return if (done) ok().put("message", "Done: $action.") else fail("Unknown action «$action». Use pause, resume, playpause, next, prev or live.")
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

    // ------------------------------------------------------------------ helpers

    /** An id, a channel number, or the best name match, among the channels the user did not hide. */
    private fun resolve(s: String): Channel? {
        val q = s.trim()
        if (q.isEmpty()) return null
        st.byId[q]?.takeIf { it.id in st.shown }?.let { return it }
        q.toIntOrNull()?.let { n -> st.visible.firstOrNull { it.num == n }?.let { return it } }
        return searchChannels(st.visible, q, 1).firstOrNull()?.ch
    }

    private fun noChannel(s: String) = if (st.channels.isEmpty()) NO_PLAYLIST else "No channel matches «$s». Try search_channels."

    private fun channelJson(ch: Channel, t: Long, now: Boolean = true): JSONObject {
        val o = JSONObject().put("id", ch.id).put("num", ch.num).put("name", ch.name).put("group", ch.group)
            .put("archiveDays", ch.archiveDays).put("logo", ch.logo ?: JSONObject.NULL)
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
    }
}

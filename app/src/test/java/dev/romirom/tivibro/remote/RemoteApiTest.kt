package dev.romirom.tivibro.remote

import android.content.ContextWrapper
import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.Mark
import dev.romirom.tivibro.data.Programme
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The phone's JSON routes over a fake view model. Main-thread calls run inline here (no Looper in unit tests). */
class RemoteApiTest {
    private fun ch(id: String, num: Int) = Channel(id, num, "Ch $num", "Новини", null, 3, "http://x/$id", false)
    private val a = ch("a", 1)
    private val b = ch("b", 2)
    private val h = ch("h", 3)
    private val calls = ArrayList<String>()
    private var favs = listOf("b")
    private var seekErr: String? = null

    private fun publish() = Remote.publish("pl", listOf(a, b, h), listOf(a, b), favs, listOf("a"), setOf("h"), emptySet())

    private val target = object : Remote.Target {
        override fun play(ch: Channel, p: Programme?, fromStart: Boolean) { calls += "play ${ch.id}" }
        override fun key(k: String): String? { calls += "key $k"; return if (k == "next_programme") "Зараз це неможливо" else null }
        override fun playing() = Remote.Playing(a, live = false, paused = false, pos = 1000, p = Programme(500, 5000, "Новини", ""), canSeek = true, can = setOf("restart"))
        override fun seek(byMs: Long?, to: Long?): String? { calls += "seek $byMs $to"; return seekErr }
        override fun audio(select: Int?) = listOf(Remote.Track(0, "AAC", select == null || select == 0), Remote.Track(1, "MP2", select == 1))
            .also { calls += "audio $select" }
        // as the view model does: the change is published, and the next read sees it
        override fun setFav(ch: Channel, on: Boolean) { favs = if (on) favs + ch.id else favs - ch.id; publish() }
        override fun moveFav(id: String, dir: Int) { favs = Control.moveFav(favs, setOf("a", "b"), id, dir); publish() }
        override fun setHidden(ch: Channel, hide: Boolean) { calls += "hide ${ch.id} $hide" }
        override fun setGroupShown(name: String, on: Boolean) { calls += "group $name $on" }
        override fun bookmarks() = listOf(Mark(b, Programme(1_000_000, 4_600_000, "Фільм", ""), 2_800_000, 9))
        override fun forgetBookmark(chId: String, start: Long) = (chId == "b" && start == 1_000_000L).also { calls += "forget $chId $start" }
        override fun programme(ch: Channel, t: Long): Remote.ProgInfo? = null
    }

    private val api = RemoteApi(ContextWrapper(null))

    @Before fun setUp() { publish(); Remote.target = target; Remote.foreground = true }
    @After fun tearDown() { Remote.target = null; Remote.foreground = false }

    private fun post(path: String, body: String) = JSONObject(String(api.handle("POST", path, emptyMap(), body).body))

    @Test fun aStarTogglesTheFavouriteAndAnswersTheNewList() {
        val on = post("fav", """{"channelId":"a","on":true}""")
        assertTrue(on.getBoolean("ok"))
        assertTrue(on.getBoolean("fav"))
        assertEquals("""["b","a"]""", on.getJSONArray("favs").toString())
        val off = post("fav", """{"channelId":"b","on":false}""")
        assertFalse(off.getBoolean("fav"))
        assertEquals("""["a"]""", off.getJSONArray("favs").toString())
        // a hidden channel can still be unstarred by its id; an unknown one is an error
        assertTrue(post("fav", """{"channelId":"h","on":false}""").getBoolean("ok"))
        assertFalse(post("fav", """{"channelId":"nope","on":true}""").getBoolean("ok"))
    }

    @Test fun channelsSayWhetherTheyAreFavourites() {
        val r = post("fav/move", """{"channelId":"b","dir":1}""")
        assertEquals("""["b"]""", r.getJSONArray("favs").toString())
        val g = JSONObject(String(api.handle("GET", "hidden", emptyMap(), "").body))
        assertEquals("h", g.getJSONArray("channels").getJSONObject(0).getString("id"))
        assertFalse(g.getJSONArray("channels").getJSONObject(0).getBoolean("fav"))
        assertTrue(g.getJSONArray("groups").getJSONObject(0).getBoolean("shown"))
    }

    @Test fun seeksAreParsedAndAnsweredWithThePlayersState() {
        val r = post("seek", """{"by":-30000}""")
        assertEquals("seek -30000 null", calls.last())
        assertTrue(r.getBoolean("ok"))
        assertEquals("archive", r.getJSONObject("now").getString("mode"))
        assertEquals("restart", r.getJSONObject("now").getJSONArray("can").getString(0))
        // a dragged position arrives as a JSON double
        post("seek", """{"to":1.7934777199E12}""")
        assertEquals("seek null 1793477719900", calls.last())
        assertFalse(post("seek", """{"to":"ніколи"}""").getBoolean("ok"))
        assertFalse(post("seek", "{}").getBoolean("ok"))
        seekErr = "На цьому каналі немає архіву"
        assertEquals(seekErr, post("seek", """{"by":5000}""").getString("error"))
    }

    @Test fun controlNeedsTheAppOnScreenAndPassesItsReasonOn() {
        assertTrue(post("key", """{"key":"restart"}""").getBoolean("ok"))
        assertEquals("Зараз це неможливо", post("key", """{"key":"next_programme"}""").getString("error"))
        assertFalse(post("key", """{"key":"explode"}""").getBoolean("ok"))
        Remote.foreground = false
        val calls0 = calls.size
        assertFalse(post("key", """{"key":"pause"}""").getBoolean("ok"))
        assertFalse(post("seek", """{"by":-30000}""").getBoolean("ok"))
        assertEquals(calls0, calls.size) // nothing reached the player
        assertTrue(post("fav", """{"channelId":"a","on":true}""").getBoolean("ok")) // favourites work in the background
    }

    @Test fun audioTracksAreListedAndSwitched() {
        val r = post("audio", """{"index":1}""")
        assertEquals("audio 1", calls.last())
        assertTrue(r.getJSONArray("tracks").getJSONObject(1).getBoolean("selected"))
        assertFalse(post("audio", """{"index":5}""").getBoolean("ok"))
    }

    @Test fun bookmarksAreListedAndForgotten() {
        val bm = api.bookmarks().getJSONArray("bookmarks").getJSONObject(0)
        assertEquals("b", bm.getString("channelId"))
        assertEquals(50, bm.getInt("progress"))
        assertTrue(post("bookmark/forget", """{"channelId":"b","start":1000000}""").getBoolean("ok"))
        assertFalse(post("bookmark/forget", """{"channelId":"b","start":5}""").getBoolean("ok"))
        assertNull(Remote.state.byId["zz"])
    }
}

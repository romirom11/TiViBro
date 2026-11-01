package dev.romirom.tivibro.remote

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTest {
    private val played = ArrayList<String>()
    private val tv = object : Tv {
        override val indexing = false
        override fun channels(q: String, limit: Int) = JSONArray().put(JSONObject().put("id", "ch1").put("name", "1+1"))
        override fun programmes(q: String, limit: Int) = JSONArray().put(JSONObject().put("title", "Футбол").put("start", 123L))
        override fun whatsOn(channel: String?) = JSONObject().put("ok", true)
        override fun nowPlaying() = JSONObject().put("ok", true).put("playing", false)
        override fun play(channel: String, start: Long?, fromStart: Boolean): JSONObject {
            played += "$channel@$start/$fromStart"
            return if (channel == "bad") JSONObject().put("ok", false).put("error", "no") else JSONObject().put("ok", true)
        }
        override fun control(action: String) = JSONObject().put("ok", true)
        override fun groups() = JSONObject().put("ok", true).put("groups", JSONArray().put(JSONObject().put("id", "*fav")))
        override fun groupChannels(group: String, offset: Int, limit: Int): JSONObject {
            played += "group $group $offset $limit"
            return JSONObject().put("ok", true)
        }
        override fun schedule(channel: String, day: Int) = JSONObject().put("ok", true)
        override fun favourites() = JSONObject().put("ok", true)
        override fun setFavourite(channel: String, on: Boolean) = JSONObject().put("ok", true).also { played += "fav $channel $on" }
        override fun bookmarks() = JSONObject().put("ok", true)
        override fun resume(channel: String?, start: Long?) = JSONObject().put("ok", true).also { played += "resume $channel $start" }
        override fun seek(seconds: Long?, to: String?) = JSONObject().put("ok", true).also { played += "seek $seconds $to" }
        override fun audio(index: Int?) = JSONObject().put("ok", true).also { played += "audio $index" }
    }
    private val mcp = Mcp(tv)

    private fun post(body: String, headers: Map<String, String> = emptyMap()) = mcp.handle("POST", { headers[it] }, body)
    private fun json(r: Http.Response) = JSONObject(String(r.body))

    @Test fun initializeEchoesKnownLegacyVersions() {
        val r = json(post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""))
        assertEquals("2025-06-18", r.getJSONObject("result").getString("protocolVersion"))
        assertTrue(r.getJSONObject("result").getJSONObject("capabilities").has("tools"))
        val u = json(post("""{"jsonrpc":"2.0","id":2,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}"""))
        assertEquals(Mcp.LEGACY.first(), u.getJSONObject("result").getString("protocolVersion"))
    }

    @Test fun notificationsAreAccepted() {
        val r = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, r.code)
        assertEquals(0, r.body.size)
    }

    @Test fun getIsNotAllowed() = assertEquals(405, mcp.handle("GET", { null }, "").code)

    @Test fun toolsAreListed() {
        val tools = json(post("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(
            listOf(
                "search_channels", "search_programmes", "whats_on", "play_channel", "play_programme", "now_playing", "control", "seek",
                "audio_track", "list_favourites", "add_favourite", "remove_favourite", "continue_watching", "resume", "list_groups", "list_channels",
            ),
            names, // the order is what clients show: keep it deliberate
        )
        val control = tools.getJSONObject(names.indexOf("control")).getJSONObject("inputSchema").getJSONObject("properties").getJSONObject("action")
        assertTrue(control.getJSONArray("enum").toString().contains("next_programme"))
    }

    @Test fun toolCallReturnsTextAndPlays() {
        val r = json(post("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_channels","arguments":{"query":"1+1"}}}"""))
        val text = r.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
        assertEquals("ch1", JSONArray(text).getJSONObject(0).getString("id"))
        assertFalse(r.getJSONObject("result").getBoolean("isError"))

        post("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"play_programme","arguments":{"channel_id":"ch1","start":1000,"from_start":true}}}""")
        assertEquals("ch1@1000/true", played.last())
        val bad = json(post("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"play_channel","arguments":{"channel":"bad"}}}"""))
        assertTrue(bad.getJSONObject("result").getBoolean("isError"))

        post("""{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"list_channels","arguments":{"group":"Кіно","offset":-3}}}""")
        assertEquals("group Кіно 0 50", played.last())
    }

    private fun call(name: String, args: String) =
        json(post("""{"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"$name","arguments":$args}}""")).getJSONObject("result")

    @Test fun newToolsPassTheirArguments() {
        call("add_favourite", """{"channel":"1+1"}"""); assertEquals("fav 1+1 true", played.last())
        call("remove_favourite", """{"channel":"ch1"}"""); assertEquals("fav ch1 false", played.last())
        call("resume", "{}"); assertEquals("resume null null", played.last())
        call("resume", """{"channel_id":"ch1","start":1000}"""); assertEquals("resume ch1 1000", played.last())
        call("seek", """{"seconds":-300}"""); assertEquals("seek -300 null", played.last())
        call("seek", """{"to":"21:30"}"""); assertEquals("seek null 21:30", played.last())
        call("seek", """{"to":1793477719916}"""); assertEquals("seek null 1793477719916", played.last())
        // «track 2» is the second track: 0-based underneath
        call("audio_track", """{"track":2}"""); assertEquals("audio 1", played.last())
        call("audio_track", "{}"); assertEquals("audio null", played.last())
        assertFalse(call("continue_watching", "{}").getBoolean("isError"))
    }

    @Test fun modernRequestsAreServedAndUnknownVersionsRefused() {
        val meta = """"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}"""
        val d = json(post("""{"jsonrpc":"2.0","id":7,"method":"server/discover","params":{$meta}}"""))
        assertTrue(d.getJSONObject("result").getJSONArray("supportedVersions").toString().contains("2026-07-28"))
        assertEquals("complete", d.getJSONObject("result").getString("resultType"))
        val bad = post("""{"jsonrpc":"2.0","id":8,"method":"tools/list","params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2030-01-01"}}}""")
        assertEquals(400, bad.code)
        assertEquals(-32022, json(bad).getJSONObject("error").getInt("code"))
        val unknown = post("""{"jsonrpc":"2.0","id":9,"method":"nope","params":{$meta}}""")
        assertEquals(404, unknown.code)
    }

    @Test fun unknownMethodIsAJsonRpcError() {
        val r = json(post("""{"jsonrpc":"2.0","id":10,"method":"resources/list"}"""))
        assertEquals(-32601, r.getJSONObject("error").getInt("code"))
        assertEquals(400, post("not json").code)
    }
}

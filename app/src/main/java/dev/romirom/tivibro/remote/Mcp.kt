package dev.romirom.tivibro.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * The MCP endpoint (`POST /mcp`, Streamable HTTP), stateless: every request is answered on its own with
 * `application/json`, never SSE, no sessions. Dual-era in the simplest way:
 *
 * - legacy clients (2024-11-05 … 2025-11-25) open with `initialize`, which echoes their version when it is one of
 *   [LEGACY] (else the newest of them), then send `notifications/initialized` (202) and call tools;
 * - modern clients (2026-07-28) put the version in every request's `_meta` and may ask `server/discover`; a version
 *   outside [VERSIONS] gets `UnsupportedProtocolVersionError` (-32022) with HTTP 400.
 *
 * Header/body cross-checks of 2026-07-28 (`Mcp-Method`, `Mcp-Name`) are not enforced: the token already decides who
 * may talk, and no intermediary routes on those headers here. GET and DELETE are 405 (no standalone stream, no session).
 */
class Mcp(private val tv: Tv) {
    fun handle(method: String, header: (String) -> String?, body: String): Http.Response {
        if (method != "POST") return Http.Response.empty(405, mapOf("Allow" to "POST"))
        val msg = runCatching { JSONObject(body) }.getOrElse { return error(null, -32700, "Parse error", 400) }
        val id = msg.opt("id")
        val name = msg.optString("method")
        if (name.isEmpty()) return error(id, -32600, "Invalid Request", 400)
        if (id == null) return Http.Response.empty(202) // a notification (notifications/initialized and the like)
        val params = msg.optJSONObject("params") ?: JSONObject()
        val modern = params.optJSONObject("_meta")?.optString("io.modelcontextprotocol/protocolVersion")?.takeIf { it.isNotEmpty() }
            ?: header("mcp-protocol-version")?.takeIf { it > LEGACY.first() } // a dated version after the legacy era
        if (modern != null && modern !in VERSIONS && name != "initialize") {
            return Http.Response.json(
                JSONObject().put("jsonrpc", "2.0").put("id", id).put(
                    "error",
                    JSONObject().put("code", -32022).put("message", "Unsupported protocol version")
                        .put("data", JSONObject().put("supported", JSONArray(VERSIONS)).put("requested", modern)),
                ).toString(),
                400,
            )
        }
        val result = when (name) {
            "initialize" -> {
                val asked = params.optString("protocolVersion")
                JSONObject().put("protocolVersion", if (asked in LEGACY) asked else LEGACY.first())
                    .put("capabilities", JSONObject().put("tools", JSONObject()))
                    .put("serverInfo", SERVER_INFO).put("instructions", INSTRUCTIONS)
            }
            "server/discover" -> JSONObject().put("supportedVersions", JSONArray(VERSIONS))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("_meta", JSONObject().put("io.modelcontextprotocol/serverInfo", SERVER_INFO)).put("instructions", INSTRUCTIONS)
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", TOOLS)
            "tools/call" -> call(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject())
                ?: return error(id, -32602, "Unknown tool: ${params.optString("name")}", if (modern != null) 400 else 200)
            else -> return error(id, -32601, "Method not found: $name", if (modern != null) 404 else 200)
        }
        if (modern != null) result.put("resultType", "complete")
        return Http.Response.json(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString())
    }

    /** A tool's result: its JSON as one text block (what every client shows the model), isError when it failed. */
    private fun call(name: String, a: JSONObject): JSONObject? {
        val limit = a.optInt("limit", 10).coerceIn(1, 50)
        val out: Any = when (name) {
            "search_channels" -> tv.channels(a.optString("query"), limit)
            "search_programmes" -> JSONObject().put("programmes", tv.programmes(a.optString("query"), limit))
                .also { if (tv.indexing) it.put("note", "The guide index is being rebuilt; results may be incomplete for a few seconds.") }
            "whats_on" -> tv.whatsOn(a.optString("channel").ifBlank { null })
            "now_playing" -> tv.nowPlaying()
            "play_channel" -> tv.play(a.optString("channel"), null, false)
            "play_programme" -> {
                val start = a.optLong("start", 0)
                if (start <= 0) JSONObject().put("ok", false).put("error", "start (epoch ms, from search_programmes) is required.")
                else tv.play(a.optString("channel_id"), start, a.optBoolean("from_start", false))
            }
            "control" -> tv.control(a.optString("action"))
            "list_groups" -> tv.groups()
            "list_channels" -> tv.groupChannels(a.optString("group"), a.optInt("offset", 0).coerceAtLeast(0), a.optInt("limit", 50).coerceIn(1, 100))
            "list_favourites" -> tv.favourites()
            "add_favourite", "remove_favourite" -> tv.setFavourite(a.optString("channel"), name == "add_favourite")
            "continue_watching" -> tv.bookmarks()
            "resume" -> tv.resume(a.optString("channel_id").ifBlank { null }, a.optLong("start", 0).takeIf { it > 0 })
            "seek" -> tv.seek(if (a.has("seconds")) a.optLong("seconds") else null, a.opt("to")?.toString()?.ifBlank { null })
            // a person says «track 2»: numbered from 1 here, from 0 in the JSON API
            "audio_track" -> tv.audio(a.optInt("track", 0).takeIf { it > 0 }?.minus(1))
            "volume" -> tv.volume(
                a.takeIf { !it.isNull("set") }?.optInt("set"), a.takeIf { !it.isNull("change") }?.optInt("change"),
                a.takeIf { !it.isNull("mute") }?.optBoolean("mute"),
            )
            else -> return null
        }
        val failed = out is JSONObject && out.has("ok") && !out.optBoolean("ok")
        return JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", out.toString().replace("\\/", "/")))).put("isError", failed)
    }

    private fun error(id: Any?, code: Int, message: String, status: Int): Http.Response = Http.Response.json(
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message)).toString(),
        status,
    )

    companion object {
        /** Newest first. */
        val LEGACY = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        val VERSIONS = listOf("2026-07-28") + LEGACY

        private val SERVER_INFO get() = JSONObject().put("name", "tivibro").put("title", "TiViBro TV").put("version", dev.romirom.tivibro.BuildConfig.VERSION_NAME)

        private const val INSTRUCTIONS = "Controls the TiViBro IPTV player on the user's TV. Find things with search_channels / " +
            "search_programmes / whats_on, then start them with play_channel (live) or play_programme (archive, or the current " +
            "programme from its start) using the channel id and start time returned. While something plays, seek, control and " +
            "audio_track steer it; volume sets the TV's own sound level. Times are epoch milliseconds; the «local» " +
            "fields are the TV's local time. Programme titles are usually Ukrainian or Russian: search in the title's language " +
            "and try a shorter word if nothing is found."

        private fun tool(name: String, desc: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
            JSONObject().put("name", name).put("description", desc)
                .put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))

        private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

        val TOOLS: JSONArray = JSONArray()
            .put(tool("search_channels", "Find TV channels by name (\"1+1\", \"Discovery\") or number. Returns id, num, name, " +
                "archiveDays (0 = live only) and what is on now.",
                JSONObject().put("query", prop("string", "Channel name or number.")).put("limit", prop("integer", "Max results, default 10.")), listOf("query")))
            .put(tool("search_programmes", "Find programmes by title in the TV guide: on now, in the archive (can be watched) " +
                "and upcoming (cannot be played yet). Best first: on now, then most recent archive, then soonest upcoming. " +
                "Each result has channelId, start (epoch ms), end, local time and when = now | archive | upcoming.",
                JSONObject().put("query", prop("string", "Words of the title, e.g. \"футбол\" or \"Шерлок\".")).put("limit", prop("integer", "Max results, default 10.")), listOf("query")))
            .put(tool("whats_on", "What is on now and next on one channel (with the next few programmes), or, without a channel, " +
                "now and next on each of the user's favourite channels.",
                JSONObject().put("channel", prop("string", "Channel id, number or name. Omit for favourites."))))
            .put(tool("play_channel", "Switch the TV to a channel, live.",
                JSONObject().put("channel", prop("string", "Channel id (best), number or name.")), listOf("channel")))
            .put(tool("play_programme", "Play a programme on the TV: one from the archive, or the one on now from its beginning " +
                "(from_start = true; without it, a programme on now plays live). Pass channel_id and start exactly as returned " +
                "by search_programmes or whats_on. Upcoming programmes cannot be played.",
                JSONObject().put("channel_id", prop("string", "The channel id.")).put("start", prop("integer", "The programme's start, epoch ms."))
                    .put("from_start", prop("boolean", "For the programme on now: start it from its beginning instead of live.")),
                listOf("channel_id", "start")))
            .put(tool("now_playing", "What the TV is playing: channel, live or archive, paused, and the programme."))
            .put(tool("control", "Remote control while TiViBro is on screen: pause, resume, playpause, next / prev " +
                "(channel up / down), live (back to the live broadcast), restart (this programme from its start), " +
                "previous_programme / next_programme (in the archive).",
                JSONObject().put("action", JSONObject().put("type", "string").put("enum", JSONArray(Remote.KEYS))
                    .put("description", "What to do.")), listOf("action")))
            .put(tool("seek", "Rewind or fast-forward what plays: by seconds (negative goes back, e.g. -300 for five minutes), " +
                "or to a time of day (\"21:30\", the most recent one) or epoch ms. Live TV goes back into the archive; " +
                "forward past the broadcast returns to live.",
                JSONObject().put("seconds", prop("integer", "Relative jump in seconds; negative is back.")).put("to", prop("string", "A local time HH:MM, or epoch ms."))))
            .put(tool("audio_track", "List the audio tracks of what plays, or switch to one (track numbers start at 1).",
                JSONObject().put("track", prop("integer", "Track number to switch to. Omit to list."))))
            .put(tool("volume", "The TV's system volume (what the TV remote's volume keys change, for everything the TV " +
                "plays). Without arguments it only reports it. change is relative, in percent: \"make it quieter\" or \"a bit " +
                "lower\" is about -10, \"louder\" +10, \"much louder\" +25; set is an absolute level in percent (\"volume to " +
                "30\"); mute = true / false silences or brings back the sound. Changing the level to anything above 0 " +
                "also unmutes, and a change while muted goes from the level the TV had. The TV shows its volume bar. Always " +
                "answers level, max (the TV's steps), percent and muted (while muted, level and percent read 0).",
                JSONObject().put("set", prop("integer", "Absolute volume, 0 to 100 percent."))
                    .put("change", prop("integer", "Relative change in percent; negative is quieter, e.g. -10."))
                    .put("mute", prop("boolean", "true mutes, false unmutes."))))
            .put(tool("list_favourites", "The user's favourite channels, in their order, with what is on now."))
            .put(tool("add_favourite", "Add a channel to the favourites.",
                JSONObject().put("channel", prop("string", "Channel id (best), number or name.")), listOf("channel")))
            .put(tool("remove_favourite", "Remove a channel from the favourites.",
                JSONObject().put("channel", prop("string", "Channel id (best), number or name.")), listOf("channel")))
            .put(tool("continue_watching", "Archive programmes the user stopped part way, newest first, with progress in percent."))
            .put(tool("resume", "Continue a programme from continue_watching where the user stopped. Without arguments, the most " +
                "recent one.",
                JSONObject().put("channel_id", prop("string", "The channel id.")).put("start", prop("integer", "The programme's start, epoch ms."))))
            .put(tool("list_groups", "The user's channel groups: favourites first (id \"*fav\"), then the playlist's groups, " +
                "each with id, name and how many channels it has. Hidden groups and channels are left out."))
            .put(tool("list_channels", "The channels of one group, in the user's order, with what is on now. Paged: pass " +
                "offset to get more (the answer says total).",
                JSONObject().put("group", prop("string", "Group id from list_groups (or its name).")).put("offset", prop("integer", "Skip this many, default 0."))
                    .put("limit", prop("integer", "Max channels, default 50.")), listOf("group")))
    }
}

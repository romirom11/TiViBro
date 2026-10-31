package io.github.romirom11.tivibro.data

import android.content.Context

/**
 * Settings. What belongs to a playlist (favourites, what is hidden, the last channel and group) is stored under the
 * active playlist's [scope]; the first playlist, migrated from the single-playlist days, keeps the old unprefixed keys.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("tivibro", Context.MODE_PRIVATE)

    /** Id of the playlist the per-playlist settings below belong to. */
    var scope = ""

    private fun k(key: String) = if (scope == PlaylistRepo.LEGACY_ID) key else "$scope/$key"

    /** The single playlist link of earlier versions; [PlaylistRepo] moves it into [playlistsJson]. */
    var legacyPlaylistUrl: String?
        get() = sp.getString("playlist", null)
        set(v) = sp.edit().putString("playlist", v).apply()

    var playlistsJson: String?
        get() = sp.getString("playlists", null)
        set(v) = sp.edit().putString("playlists", v).apply()

    var activePlaylist: String?
        get() = sp.getString("activePlaylist", null)
        set(v) = sp.edit().putString("activePlaylist", v).apply()

    var guideAt: Long
        get() = sp.getLong("guideAt", 0)
        set(v) = sp.edit().putLong("guideAt", v).apply()

    var favorites: List<String>
        get() = sp.getString(k("fav"), "")!!.split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(k("fav"), v.joinToString(",")).apply()

    var lastChannel: String?
        get() = sp.getString(k("last"), null)
        set(v) = sp.edit().putString(k("last"), v).apply()

    /** «Нещодавні»: channel ids, most recent first. */
    var recent: List<String>
        get() = sp.getString(k("recent"), "")!!.split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString(k("recent"), v.joinToString(",")).apply()

    var lastGroup: String?
        get() = sp.getString(k("lastGroup"), null)
        set(v) = sp.edit().putString(k("lastGroup"), v).apply()

    // ---- what is shown. Groups are keyed by name without the provider's numbering, so a renumbered playlist keeps
    //      the user's choice; a group with nothing stored follows [def].

    fun groupShown(name: String, def: Boolean) = sp.getBoolean(k("gShow:$name"), def)
    fun setGroupShown(name: String, v: Boolean) = sp.edit().putBoolean(k("gShow:$name"), v).apply()

    var hiddenChannels: Set<String>
        get() = sp.getStringSet(k("hiddenCh"), null)?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(k("hiddenCh"), v).apply()

    /** Drops every per-playlist setting of [id] (the playlist was deleted). */
    fun forget(id: String) {
        val e = sp.edit()
        if (id == PlaylistRepo.LEGACY_ID) {
            for (key in sp.all.keys) if (key in LEGACY_KEYS || key.startsWith("gShow:")) e.remove(key)
        } else {
            for (key in sp.all.keys) if (key.startsWith("$id/")) e.remove(key)
        }
        e.apply()
    }

    private companion object {
        val LEGACY_KEYS = setOf("fav", "last", "lastGroup", "hiddenCh", "recent")
    }
}

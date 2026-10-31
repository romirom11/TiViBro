package dev.romirom.tivibro.tvhome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.romirom.tivibro.R
import dev.romirom.tivibro.data.MIN
import dev.romirom.tivibro.str
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The app on the Android TV home screen, the way video apps do it: a preview channel «TiViBro» (what to continue,
 * then what is on now on the Home sources) and Watch Next for unfinished archive programmes. Only titles, times,
 * pictures and `tivibro://` links go to the TV provider: never a stream link or a token.
 *
 * The app writes a [Snapshot] of what it knows (bookmarks, the sources' next few programmes, TMDB pictures it found)
 * and publishes it; the periodic worker publishes the saved snapshot again, so that what is "on now" stays current
 * while the app is closed, without reading the guide or the network. Everything here needs Android 8 and is
 * best effort: a launcher without the TV provider just doesn't show it.
 */
object TvHome {
    private const val TAG = "tivibro"
    private const val CHANNEL_KEY = "tivibro-home"
    private const val MAX_PROGRAMS = 12
    private const val MAX_CONTINUE = 6

    val supported get() = Build.VERSION.SDK_INT >= 26

    /** One programme of a source channel, as saved for the worker. */
    class Prog(val start: Long, val end: Long, val title: String, val picture: String?, val movie: Boolean?)

    /** A bookmark: an archive programme the user stopped in. */
    class Cont(
        val playlist: String, val ch: String, val chName: String, val logo: String?, val prog: Prog,
        val pos: Long, val at: Long, val expires: Long,
    ) {
        val key get() = "c:$playlist:$ch:${prog.start}"
    }

    /** A source channel and its next few programmes. */
    class Live(val playlist: String, val ch: String, val chName: String, val logo: String?, val progs: List<Prog>)

    class Snapshot(val cont: List<Cont>, val live: List<Live>) {
        fun toJson(): JSONObject = JSONObject()
            .put("cont", JSONArray(cont.map { c ->
                JSONObject().put("pl", c.playlist).put("ch", c.ch).put("name", c.chName).put("logo", c.logo ?: "").put("p", prog(c.prog))
                    .put("pos", c.pos).put("at", c.at).put("exp", c.expires)
            }))
            .put("live", JSONArray(live.map { l ->
                JSONObject().put("pl", l.playlist).put("ch", l.ch).put("name", l.chName).put("logo", l.logo ?: "").put("p", JSONArray(l.progs.map(::prog)))
            }))

        companion object {
            private fun prog(p: Prog) = JSONObject().put("s", p.start).put("e", p.end).put("t", p.title).put("img", p.picture ?: "")
                .put("m", when (p.movie) { true -> 1; false -> 0; null -> -1 })

            private fun prog(o: JSONObject) = Prog(
                o.getLong("s"), o.getLong("e"), o.getString("t"), o.optString("img").ifBlank { null },
                when (o.optInt("m", -1)) { 1 -> true; 0 -> false; else -> null },
            )

            fun fromJson(o: JSONObject): Snapshot {
                val c = o.optJSONArray("cont") ?: JSONArray()
                val l = o.optJSONArray("live") ?: JSONArray()
                return Snapshot(
                    List(c.length()) { c.getJSONObject(it) }.map {
                        Cont(it.getString("pl"), it.getString("ch"), it.getString("name"), it.optString("logo").ifBlank { null }, prog(it.getJSONObject("p")),
                            it.getLong("pos"), it.getLong("at"), it.getLong("exp"))
                    },
                    List(l.length()) { l.getJSONObject(it) }.map {
                        val ps = it.getJSONArray("p")
                        Live(it.getString("pl"), it.getString("ch"), it.getString("name"), it.optString("logo").ifBlank { null },
                            List(ps.length()) { i -> prog(ps.getJSONObject(i)) })
                    },
                )
            }
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "tvhome.json")

    fun save(ctx: Context, s: Snapshot) = runCatching {
        val tmp = File(ctx.filesDir, "tvhome.json.tmp")
        tmp.writeText(s.toJson().toString())
        tmp.renameTo(file(ctx))
    }

    fun load(ctx: Context): Snapshot? = runCatching { Snapshot.fromJson(JSONObject(file(ctx).readText())) }.getOrNull()

    /** Publishes [s] (or the saved snapshot) as it stands at [now]. Blocking I/O and a few image loads: a background
     *  thread only. */
    suspend fun publish(ctx: Context, s: Snapshot? = load(ctx), now: Long = System.currentTimeMillis()) {
        if (!supported || s == null) return
        try {
            val cont = s.cont.filter { it.expires > now }.sortedByDescending { it.at }.take(MAX_CONTINUE)
            publishWatchNext(ctx, cont)
            publishChannel(ctx, cont, s.live, now)
        } catch (e: Exception) {
            Log.w(TAG, "tv home: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- the preview channel

    private class Card(val key: String, val title: String, val desc: String, val start: Long, val end: Long, val live: Boolean,
                       val picture: String?, val logo: String?, val chName: String, val movie: Boolean?, val link: DeepLink, val badge: String)

    @SuppressLint("RestrictedApi")
    private suspend fun publishChannel(ctx: Context, cont: List<Cont>, live: List<Live>, now: Long) {
        val cards = ArrayList<Card>()
        for (c in cont) cards += Card(
            c.key, c.prog.title, c.chName, c.prog.start, c.prog.end, false, c.prog.picture, c.logo, c.chName, c.prog.movie,
            DeepLink.Archive(c.playlist, c.ch, c.prog.start, c.pos - c.prog.start), str(R.string.archive),
        )
        for (l in live) {
            if (cards.size >= MAX_PROGRAMS) break
            val p = l.progs.firstOrNull { it.start <= now && it.end > now } ?: continue
            cards += Card("n:${l.playlist}:${l.ch}:${p.start}", p.title, l.chName, p.start, p.end, true, p.picture, l.logo, l.chName, p.movie,
                DeepLink.Live(l.playlist, l.ch), str(R.string.badge_live))
        }
        val helper = PreviewChannelHelper(ctx)
        val existing = helper.allChannels.firstOrNull { it.internalProviderId == CHANNEL_KEY }
        val channelId = existing?.id ?: run {
            if (cards.isEmpty()) return // nothing to show yet: no empty channel on the home screen
            val logo = ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(256, 256)
            helper.publishDefaultChannel(
                PreviewChannel.Builder().setDisplayName(ctx.getString(R.string.app_name)).setInternalProviderId(CHANNEL_KEY)
                    .setAppLinkIntentUri(Uri.parse(DeepLink.Home.uri())).setLogo(logo).build(),
            )
        }
        // the same programmes as last time: leave the launcher alone
        val hash = sha1(cards.joinToString("\n") { "${it.key}|${it.title}|${it.picture}|${it.link.uri()}" })
        val sp = ctx.getSharedPreferences("tvhome", Context.MODE_PRIVATE)
        if (existing != null && sp.getString("hash", null) == hash) return
        val old = programIds(ctx, channelId)
        val keep = HashSet<String>()
        for (c in cards) {
            val art = c.picture?.let(Uri::parse) ?: cardImage(ctx, c)?.also { keep += it.lastPathSegment.orEmpty() } ?: continue
            val b = PreviewProgram.Builder().setChannelId(channelId)
                .setType(typeOf(c.movie))
                .setTitle(c.title).setDescription(c.desc)
                .setPosterArtUri(art).setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setIntentUri(Uri.parse(c.link.uri())).setInternalProviderId(c.key)
                .setStartTimeUtcMillis(c.start).setEndTimeUtcMillis(c.end)
                .setLive(c.live)
            if (c.logo != null) b.setLogoUri(Uri.parse(c.logo))
            helper.publishPreviewProgram(b.build())
        }
        for (id in old) helper.deletePreviewProgram(id)
        sp.edit().putString("hash", hash).apply()
        CardProvider.prune(ctx, keep)
        Log.i(TAG, "tv home: ${cards.size} programmes on the channel")
    }

    private fun typeOf(movie: Boolean?) = when (movie) {
        true -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        false -> TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE
        null -> TvContractCompat.PreviewPrograms.TYPE_CLIP
    }

    private fun programIds(ctx: Context, channelId: Long): List<Long> =
        ctx.contentResolver.query(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), arrayOf(TvContractCompat.PreviewPrograms._ID), null, null, null)
            ?.use { c -> List(c.count) { c.moveToNext(); c.getLong(0) } }.orEmpty()

    // ---------------------------------------------------------------- Watch Next

    /** «Продовжити перегляд» in Watch Next: added and updated by bookmark; ours that are finished or out of the archive
     *  go; one the user removed from the row (not browsable any more) is left as the user wanted. */
    @SuppressLint("RestrictedApi")
    private suspend fun publishWatchNext(ctx: Context, cont: List<Cont>) {
        val cr = ctx.contentResolver
        val ours = HashMap<String, Pair<Long, Boolean>>()
        cr.query(TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, TvContractCompat.WatchNextPrograms.COLUMN_BROWSABLE),
            null, null, null)?.use { c ->
            while (c.moveToNext()) ours[c.getString(1) ?: continue] = c.getLong(0) to (c.getInt(2) != 0)
        }
        val want = cont.associateBy { it.key }
        for ((key, row) in ours) if (key !in want) cr.delete(TvContractCompat.buildWatchNextProgramUri(row.first), null, null)
        for (c in cont) {
            val row = ours[c.key]
            if (row != null && !row.second) continue // removed by the user
            val card = Card(c.key, c.prog.title, c.chName, c.prog.start, c.prog.end, false, c.prog.picture, c.logo, c.chName, c.prog.movie,
                DeepLink.Archive(c.playlist, c.ch, c.prog.start, c.pos - c.prog.start), str(R.string.archive))
            val art = c.prog.picture?.let(Uri::parse) ?: cardImage(ctx, card) ?: continue
            val p = WatchNextProgram.Builder()
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setType(typeOf(c.prog.movie))
                .setTitle(c.prog.title).setDescription(c.chName)
                .setPosterArtUri(art).setPosterArtAspectRatio(TvContractCompat.WatchNextPrograms.ASPECT_RATIO_16_9)
                .setLastEngagementTimeUtcMillis(c.at)
                .setLastPlaybackPositionMillis((c.pos - c.prog.start).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                .setDurationMillis((c.prog.end - c.prog.start).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                .setIntentUri(Uri.parse(card.link.uri())).setInternalProviderId(c.key)
                .build()
            if (row == null) cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, p.toContentValues())
            else cr.update(TvContractCompat.buildWatchNextProgramUri(row.first), p.toContentValues(), null, null)
        }
    }

    // ---------------------------------------------------------------- generated card pictures

    /** A 16:9 picture for a programme without one: the channel's logo on its plate, the title, the time, a badge. Made
     *  once per content and served by [CardProvider]. */
    private suspend fun cardImage(ctx: Context, c: Card): Uri? = runCatching {
        val name = sha1("v2|${c.chName}|${c.title}|${c.start}|${c.end}|${c.badge}|${c.logo}").take(24) + ".jpg"
        val f = File(CardProvider.dir(ctx), name)
        if (!f.exists()) {
            val logo = c.logo?.let { loadLogo(ctx, it) }
            val bmp = drawCard(c, logo)
            val tmp = File(f.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            bmp.recycle()
            tmp.renameTo(f)
        }
        CardProvider.uri(ctx, name)
    }.onFailure { Log.w(TAG, "tv home: card: $it") }.getOrNull()

    private suspend fun loadLogo(ctx: Context, url: String): Bitmap? = withTimeoutOrNull(8000) {
        runCatching {
            val r = SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(url).size(160).allowHardware(false).build())
            r.image?.toBitmap()
        }.getOrNull()
    }

    private fun drawCard(c: Card, logo: Bitmap?): Bitmap {
        val w = 640
        val h = 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // a dark ground with a tint of its own per channel, so a row of these isn't one grey block
        val hue = (c.chName.hashCode() and 0xFFFF) % 360
        val tint = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), .45f, .28f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), tint, 0xFF0A0B0E.toInt(), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // the logo on its light plate, top left
        p.color = 0xFFE4E4E6.toInt()
        val plate = RectF(32f, 30f, 32f + 96f, 30f + 96f)
        cv.drawRoundRect(plate, 18f, 18f, p)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        if (logo != null) {
            val s = minOf(80f / logo.width, 80f / logo.height)
            val lw = logo.width * s
            val lh = logo.height * s
            cv.drawBitmap(logo, null, RectF(plate.centerX() - lw / 2, plate.centerY() - lh / 2, plate.centerX() + lw / 2, plate.centerY() + lh / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            tp.color = 0xFF0A0B0E.toInt(); tp.textSize = 30f; tp.typeface = Typeface.DEFAULT_BOLD
            cv.drawText(c.chName.take(3), plate.left + 12f, plate.centerY() + 10f, tp)
        }
        tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tp.textSize = 24f
        tp.color = 0xFFA3A3AB.toInt()
        cv.drawText(TextUtils.ellipsize(c.chName, tp, w - 180f, TextUtils.TruncateAt.END).toString(), 148f, 88f, tp)
        // the badge, top right; a live programme gets the launcher's own LIVE badge instead
        if (!c.live) drawBadge(cv, c, p, tp, w)
        // the title, up to two lines, and the time
        tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tp.color = 0xFFF5F5F7.toInt()
        tp.textSize = 46f
        val layout = staticLayout(c.title, tp, w - 64)
        val top = h - 64f - layout.height
        cv.save(); cv.translate(32f, top); layout.draw(cv); cv.restore()
        tp.textSize = 24f
        tp.typeface = Typeface.DEFAULT
        tp.color = 0xFFA3A3AB.toInt()
        cv.drawText("${hm(c.start)} – ${hm(c.end)}", 32f, h - 30f, tp)
        return bmp
    }

    private fun drawBadge(cv: Canvas, c: Card, p: Paint, tp: TextPaint, w: Int) {
        tp.textSize = 22f
        val bw = tp.measureText(c.badge) + 28f
        p.color = 0xFFFFB340.toInt()
        cv.drawRoundRect(RectF(w - 32f - bw, 34f, w - 32f, 72f), 10f, 10f, p)
        tp.color = 0xFF0A0B0E.toInt()
        cv.drawText(c.badge, w - 32f - bw + 14f, 61f, tp)
    }

    @Suppress("DEPRECATION")
    private fun staticLayout(text: String, tp: TextPaint, width: Int): StaticLayout =
        if (Build.VERSION.SDK_INT >= 23) StaticLayout.Builder.obtain(text, 0, text.length, tp, width).setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.05f).build()
        else StaticLayout(text, tp, width, Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false)

    private fun hm(t: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(t))

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** How often the worker publishes the saved snapshot again. */
    const val REFRESH = 45 * MIN
}

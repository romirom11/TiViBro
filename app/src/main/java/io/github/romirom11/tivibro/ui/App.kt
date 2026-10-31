package io.github.romirom11.tivibro.ui

import io.github.romirom11.tivibro.L10n
import io.github.romirom11.tivibro.R
import io.github.romirom11.tivibro.str
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.FilterQuality
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.romirom11.tivibro.AppViewModel
import io.github.romirom11.tivibro.AppViewModel.Companion.ago
import io.github.romirom11.tivibro.AppViewModel.Companion.dayStart
import io.github.romirom11.tivibro.Focus
import io.github.romirom11.tivibro.Layer
import io.github.romirom11.tivibro.Mode
import io.github.romirom11.tivibro.OsdButton
import io.github.romirom11.tivibro.Sheet
import io.github.romirom11.tivibro.data.DAY
import io.github.romirom11.tivibro.data.HOUR
import io.github.romirom11.tivibro.data.MIN
import io.github.romirom11.tivibro.data.now
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HM = SimpleDateFormat("HH:mm", Locale.US)
fun hm(t: Long): String = HM.format(Date(t))

private fun cal(t: Long) = java.util.Calendar.getInstance().apply { timeInMillis = t }
private fun dow(c: java.util.Calendar) = L10n.array(R.array.dow_short)[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
private fun mon(c: java.util.Calendar) = L10n.array(R.array.mon_short)[c.get(java.util.Calendar.MONTH)]
private fun relDay(diff: Int): String? = when (diff) {
    0 -> str(R.string.today); -1 -> str(R.string.yesterday); 1 -> str(R.string.tomorrow); else -> null
}

/** «Сьогодні», «Вчора», «Завтра» or «пт, 30 жов» (Today, Yesterday, Tomorrow or Fri, Oct 30). */
fun dayWord(t: Long): String {
    val diff = Math.round((dayStart(t) - dayStart(now())).toDouble() / DAY).toInt()
    val c = cal(t)
    return relDay(diff) ?: str(R.string.day_fmt, dow(c), c.get(java.util.Calendar.DAY_OF_MONTH), mon(c))
}

/** For the days column: big name + small date. */
fun dayParts(d: Long): Pair<String, String> {
    val diff = Math.round((d - dayStart(now())).toDouble() / DAY).toInt()
    val c = cal(d)
    val dow = dow(c)
    val date = str(R.string.date_fmt, c.get(java.util.Calendar.DAY_OF_MONTH), mon(c))
    val name = relDay(diff) ?: dow.replaceFirstChar { it.uppercase() }
    return name to if (diff in -1..1) str(R.string.day_fmt, dow, c.get(java.util.Calendar.DAY_OF_MONTH), mon(c)) else date
}

/** Runs [content] with the current time and re-runs just it on every VM tick (1 s). Wrap only what moves with the
 *  clock (clocks, progress bars, «ще N хв»), so the rest of the tree doesn't recompose every second. */
@Composable
fun Ticking(vm: AppViewModel, content: @Composable (now: Long) -> Unit) {
    vm.tick
    content(now())
}

@Composable
fun App(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (vm.playlistUrl == null) { Setup(vm); return@Box }
        Video(vm)
        if (vm.channels.isEmpty()) { Loading(vm.status); return@Box }

        if (vm.layer == Layer.NONE && (vm.banner || vm.osd)) { // channels draw their own logo top-right, so the clock only rides with the panels
            Ticking(vm) { t -> T(hm(t), 34, Tok.T1, FontWeight.SemiBold, Modifier.align(Alignment.TopEnd).padding(top = 48.px, end = 72.px)) }
        }
        AnimatedVisibility(vm.layer == Layer.NONE && vm.banner, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut()) { Banner(vm) }
        AnimatedVisibility(vm.layer == Layer.NONE && vm.osd, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 6 }, exit = fadeOut()) { Osd(vm) }
        if (vm.layer == Layer.LIST) ListOverlay(vm)
        if (Sheet.MENU in vm.sheets) Menu(vm)
        vm.sheets.lastOrNull { it != Sheet.MENU }?.let { SideSheet(vm, it) }
        vm.ctx?.let { ChannelMenu(vm, it) }
        vm.markCtx?.let { MarkMenu(vm, it) }
        Hints(vm, Modifier.align(Alignment.BottomStart))

        if (vm.numBuf.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 120.px, end = 72.px)
                    .background(Tok.GlassHi, RoundedCornerShape(28.px)).padding(horizontal = 36.px, vertical = 20.px),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                T(vm.numBuf, 72, Tok.T1, FontWeight.Bold)
                T(vm.channelByNum(vm.numBuf.toInt())?.name ?: str(R.string.no_channel), 22, Tok.T2)
            }
        }
        AnimatedVisibility(vm.toast != null, Modifier.align(Alignment.TopCenter).padding(top = 56.px), enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut()) {
            Box(Modifier.background(Tok.GlassHi, RoundedCornerShape(50)).border(1.dp, Tok.Hair, RoundedCornerShape(50)).padding(horizontal = 30.px, vertical = 18.px)) {
                T(vm.toast ?: "", 25, Tok.T1, FontWeight.Medium)
            }
        }
        if (vm.status != null && (vm.layer == Layer.LIST || vm.sheets.isNotEmpty())) {
            T(vm.status!!, 20, Tok.T3, modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.px, end = 72.px))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Video(vm: AppViewModel) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                player = vm.player
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                isFocusable = false
                keepScreenOn = true
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    if (vm.buffering && vm.layer == Layer.NONE) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T(str(R.string.loading), 26, Tok.T2) }
    }
}

@Composable
private fun Loading(status: String?) {
    Box(Modifier.fillMaxSize().background(Tok.Ink), contentAlignment = Alignment.Center) {
        T(status ?: str(R.string.loading_list), 28, Tok.T2)
    }
}

@Composable
private fun Banner(vm: AppViewModel) {
    val ch = vm.cur ?: return
    val p = vm.progAt(ch, now())
    val next = p?.let { vm.progAt(ch, it.end + 1) }
    Row(
        Modifier.padding(start = 72.px, end = 72.px, bottom = 100.px).fillMaxWidth()
            .background(Tok.Glass, RoundedCornerShape(28.px)).border(1.dp, Tok.Hair, RoundedCornerShape(28.px))
            .padding(horizontal = 36.px, vertical = 30.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(ch, 120)
        Column(Modifier.weight(1f).padding(horizontal = 32.px)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T("${ch.num}", 24, Tok.T1, FontWeight.SemiBold)
                T(ch.name, 24, Tok.T2, FontWeight.SemiBold, Modifier.padding(start = 14.px))
                if (vm.isFav(ch)) T("★", 22, Color(0xFFFFD60A), modifier = Modifier.padding(start = 12.px))
            }
            T(p?.title ?: str(R.string.no_programme), 46, Tok.T1, FontWeight.Bold, Modifier.padding(top = 8.px))
            if (p != null) {
                Row(Modifier.padding(top = 16.px), verticalAlignment = Alignment.CenterVertically) {
                    T("${hm(p.start)} – ${hm(p.end)}", 22, Tok.T2)
                    Ticking(vm) { t ->
                        Progress((t - p.start).toFloat() / p.duration, Modifier.padding(horizontal = 20.px).width(280.px))
                        T(str(R.string.remaining, ago(p.end - t)), 22, Tok.T2)
                    }
                }
            }
            if (next != null) T(str(R.string.next_prog, hm(next.start), next.title), 22, Tok.T2, modifier = Modifier.padding(top = 12.px))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.px)) {
            LivePill()
            Row(horizontalArrangement = Arrangement.spacedBy(10.px)) {
                if (ch.hd) Pill("HD", Tok.T2, outline = true)
                if (ch.archiveDays > 0) Pill(str(R.string.archive_days, ch.archiveDays), Tok.T2, outline = true)
            }
        }
    }
}

/** The player control panel: what is playing, a timeline (focused first) and a row of buttons. */
@Composable
private fun Osd(vm: AppViewModel) {
    val ch = vm.cur ?: return
    vm.tick // the whole panel follows the playhead: re-read every second
    val t = vm.posAbs()
    val p = vm.progAt(ch, t)
    val start = p?.start ?: (t - 30 * MIN)
    val end = p?.end ?: (t + 30 * MIN)
    val frac = (t - start).toFloat() / (end - start)
    val behind = now() - t
    val live = vm.mode == Mode.LIVE
    val onLine = !vm.osdOnButtons
    Box {
        if (vm.seekAcc != 0L) {
            Box(
                Modifier.align(Alignment.TopCenter).offset(y = (-20).px).background(Tok.GlassHi, RoundedCornerShape(18.px))
                    .padding(horizontal = 28.px, vertical = 12.px),
            ) { T((if (vm.seekAcc > 0) "+" else "−") + hms(kotlin.math.abs(vm.seekAcc)), 42, Tok.T1, FontWeight.Bold) }
        }
        Column(
            Modifier.padding(start = 72.px, end = 72.px, bottom = 100.px, top = if (vm.seekAcc != 0L) 110.px else 0.px).fillMaxWidth()
                .background(Tok.Glass, RoundedCornerShape(28.px)).border(1.dp, Tok.Hair, RoundedCornerShape(28.px))
                .padding(start = 36.px, end = 36.px, top = 30.px, bottom = 26.px),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(ch, 64)
                Row(Modifier.weight(1f).padding(horizontal = 22.px), verticalAlignment = Alignment.Bottom) {
                    T(p?.title ?: ch.name, 38, Tok.T1, FontWeight.Bold, Modifier.weight(1f, fill = false))
                    T(" · ${ch.name}", 24, Tok.T2, FontWeight.Medium)
                }
                when {
                    vm.paused -> Pill(str(R.string.paused), Tok.Arch, Color(0x29FFB340))
                    live -> LivePill()
                    behind < 3 * HOUR -> Pill(str(R.string.behind_live, ago(behind)), Tok.Arch, Color(0x29FFB340))
                    else -> Pill(str(R.string.archive), Tok.Arch, Color(0x29FFB340))
                }
            }
            // timeline; focused = the scrubber gets a ring and the track lights up
            Box(Modifier.padding(top = 30.px, bottom = 14.px).fillMaxWidth().height(36.px), contentAlignment = Alignment.CenterStart) {
                Progress(frac, Modifier.fillMaxWidth(), if (onLine) Tok.T1 else Tok.T2, if (onLine) Color(0x33FFFFFF) else Color(0x1FFFFFFF), if (onLine) 8 else 6)
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val knob = if (onLine) 36.px else 20.px
                    Box(
                        Modifier.offset(x = maxWidth * frac.coerceIn(0f, 1f) - knob / 2).size(knob)
                            .then(if (onLine) Modifier.border(5.px, Color(0x66FFFFFF), CircleShape).padding(5.px) else Modifier)
                            .background(if (onLine) Color.White else Tok.T2, CircleShape),
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                T(hm(start), 22, Tok.T2)
                Spacer(Modifier.weight(1f))
                T(hm(t), 22, Tok.T1, FontWeight.SemiBold)
                T(" · ${dayWord(t)}", 22, Tok.T2)
                Spacer(Modifier.weight(1f))
                T(hm(end), 22, Tok.T2)
            }
            Row(Modifier.padding(top = 22.px), horizontalArrangement = Arrangement.spacedBy(14.px)) {
                for (b in OsdButton.entries) {
                    val on = vm.osdEnabled(b)
                    val foc = vm.osdOnButtons && vm.osdBtn == b && on
                    val label = when (b) {
                        OsdButton.RESTART -> str(R.string.osd_restart)
                        OsdButton.PREV -> str(R.string.osd_prev)
                        OsdButton.NEXT -> str(R.string.osd_next)
                        OsdButton.LIVE -> str(R.string.osd_live)
                        OsdButton.AUDIO -> str(R.string.osd_audio) + if (vm.audioTracks > 1) " · ${vm.audioTracks}" else ""
                    }
                    Plate(foc, Modifier.height(64.px).alpha(if (on) 1f else 0.35f), quiet = on && !foc, radius = 16, lift = 1.05f) {
                        T(
                            label, 23, if (foc) Tok.OnPlate else if (b == OsdButton.LIVE && on) Tok.Live else Tok.T1, FontWeight.SemiBold,
                            Modifier.align(Alignment.Center).padding(horizontal = 26.px),
                        )
                    }
                }
            }
        }
    }
}

fun hms(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    return (if (h > 0) "$h:" else "") + "%02d:%02d".format((s / 60) % 60, s % 60)
}

@Composable
private fun Hints(vm: AppViewModel, modifier: Modifier) {
    fun h(key: String, @androidx.annotation.StringRes label: Int) = key to str(label)
    val items: List<Pair<String, String>> = when {
        vm.ctx != null || vm.markCtx != null -> listOf(h("▲▼", R.string.hint_choose), h("OK", R.string.hint_apply), h("BACK", R.string.hint_close))
        vm.sheets.isNotEmpty() -> when (vm.sheets.last()) {
            Sheet.MENU -> listOf(h("▲▼", R.string.hint_choose), h("OK", R.string.hint_open), h("▶", R.string.hint_to_channels), h("BACK", R.string.hint_close))
            Sheet.SETTINGS -> listOf(h("▲▼", R.string.hint_choose), h("OK", R.string.hint_open), h("BACK", R.string.hint_back))
            Sheet.GROUPS -> listOf(h("▲▼", R.string.hint_group), h("OK", R.string.hint_toggle_group), h("BACK", R.string.hint_done))
            Sheet.HIDDEN -> if (vm.hiddenChannels().isEmpty()) listOf(h("BACK", R.string.hint_back))
            else listOf(h("▲▼", R.string.hint_channel), h("OK", R.string.hint_unhide), h("BACK", R.string.hint_back))
            Sheet.PLAYLISTS -> listOf(h("▲▼", R.string.hint_playlist), h("OK", R.string.hint_open), h("BACK", R.string.hint_back))
            Sheet.PLAYLIST -> listOf(h("▲▼", R.string.hint_action), h("OK", R.string.hint_run), h("BACK", R.string.hint_back))
            Sheet.GUIDE, Sheet.LANGUAGE -> listOf(h("▲▼", R.string.hint_choose), h("OK", R.string.hint_apply), h("BACK", R.string.hint_back))
            Sheet.LINK -> listOf(h("BACK", R.string.hint_cancel))
        }
        vm.layer == Layer.LIST -> when (vm.focus) {
            Focus.GROUPS -> listOf(h("▲▼", R.string.hint_group), h("▶", R.string.hint_channels), h("BACK", R.string.hint_menu))
            Focus.DAYS -> listOf(h("▲▼", R.string.hint_day), h("▶", R.string.hint_progs), h("BACK", R.string.hint_channels))
            Focus.PROGS -> {
                val ch = vm.listChannel()
                val p = ch?.let { vm.progList(it).getOrNull(vm.progIdx) }
                val long = when {
                    ch == null || p == null -> null
                    vm.hasMarkMenu(ch, p) -> h("OK", R.string.hint_long_mark)
                    p.isNow() && vm.canArchive(ch) -> h("OK", R.string.hint_long_restart)
                    else -> null
                }
                listOfNotNull(h("▲▼", R.string.hint_programme), h("OK", R.string.hint_watch), long, h("◀", R.string.hint_days), h("BACK", R.string.hint_channels))
            }
            Focus.CHANNELS -> if (vm.listMark() != null) listOf(
                h("▲▼", R.string.hint_programme), h("OK", R.string.hint_resume), h("OK", R.string.hint_long_actions),
                h("▶", R.string.hint_channel_guide), h("BACK", R.string.hint_groups),
            ) else listOf(
                h("▲▼", R.string.hint_channel), h("OK", R.string.hint_watch), h("▶", R.string.hint_guide),
                h("OK", R.string.hint_long_channel_menu), h("BACK", R.string.hint_groups),
            )
        }
        vm.osd && !vm.osdOnButtons -> listOf(
            h("◀▶", R.string.hint_seek), h("OK", if (vm.paused) R.string.hint_play else R.string.hint_pause),
            h("▼", R.string.hint_buttons), h("BACK", R.string.hint_hide),
        )
        vm.osd -> listOf(h("◀▶", R.string.hint_choose), h("OK", R.string.hint_run), h("▲", R.string.hint_timeline), h("BACK", R.string.hint_hide))
        vm.banner -> listOf(
            h("▲▼", R.string.hint_channels), h("◀", R.string.hint_list), h("▶", R.string.hint_guide),
            h("OK", R.string.hint_controls), h("OK", R.string.hint_long_channel_menu),
        )
        else -> return
    }
    Row(
        modifier.fillMaxWidth().height(84.px).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
            .padding(horizontal = 72.px),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(40.px),
    ) {
        for ((k, label) in items) Row(verticalAlignment = Alignment.CenterVertically) {
            KeyCap(k)
            T(label, 21, Tok.T2, FontWeight.Medium, Modifier.padding(start = 10.px))
        }
    }
}

@Composable
private fun Setup(vm: AppViewModel) {
    // started before its address is read: the address only exists once the socket is open
    val address = remember(vm.setupServer) { vm.setupServer.start(); vm.setupServer.address }
    val qr = remember(address) { address?.let(::qrBitmap) }
    Row(Modifier.fillMaxSize().background(Tok.Ink).padding(horizontal = 160.px), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(str(R.string.setup_title), 56, Tok.T1, FontWeight.Bold)
            T(str(R.string.setup_steps), 26, Tok.T2, modifier = Modifier.padding(top = 32.px), maxLines = 6)
            if (address != null) T(str(R.string.or_open, address), 24, Tok.T3, modifier = Modifier.padding(top = 32.px))
            else T(str(R.string.no_network), 24, Tok.Live, modifier = Modifier.padding(top = 32.px))
            if (vm.status != null) T(vm.status!!, 24, Tok.Live, modifier = Modifier.padding(top = 24.px), maxLines = 3)
        }
        if (qr != null) {
            Box(Modifier.size(420.px).background(Color.White, RoundedCornerShape(28.px)).padding(24.px)) {
                Image(qr.asImageBitmap(), null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
        }
    }
}

internal fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}


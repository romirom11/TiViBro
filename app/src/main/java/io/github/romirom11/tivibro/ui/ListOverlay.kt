package io.github.romirom11.tivibro.ui

import io.github.romirom11.tivibro.R
import io.github.romirom11.tivibro.plural
import io.github.romirom11.tivibro.str
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.romirom11.tivibro.AppViewModel
import io.github.romirom11.tivibro.AppViewModel.Companion.DONE
import io.github.romirom11.tivibro.AppViewModel.Companion.ago
import io.github.romirom11.tivibro.AppViewModel.Companion.dayStart
import io.github.romirom11.tivibro.Focus
import io.github.romirom11.tivibro.data.Channel
import io.github.romirom11.tivibro.data.Mark
import io.github.romirom11.tivibro.data.Programme
import io.github.romirom11.tivibro.data.inArchive
import io.github.romirom11.tivibro.data.now
import kotlin.math.max

@Composable
fun ListOverlay(vm: AppViewModel) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        android.util.Log.i("tivibro", "list: first frame ${android.os.SystemClock.uptimeMillis() - vm.listKeyAt} ms after the key")
    }
    val g = vm.currentGroup() ?: return
    val ch = vm.listChannel()
    val progMode = ch != null && (vm.focus == Focus.DAYS || vm.focus == Focus.PROGS)
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                if (progMode) listOf(Color(0xF0060709), Color(0xEB060709), Color(0xD9060709))
                else listOf(Color(0xE6060709), Color(0xDB060709), Color(0x66060709)),
            ),
        ),
    ) {
        Row(Modifier.fillMaxSize().padding(bottom = 84.px)) {
            if (progMode) ProgrammeView(vm, ch!!, g.channels) else {
                GroupsRail(vm)
                ChannelColumn(vm)
                val m = vm.listMark()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        m != null -> ProgrammeDetail(vm, m.ch, m.p, Modifier.align(Alignment.BottomStart))
                        ch != null -> ChannelDetail(vm, ch, Modifier.align(Alignment.BottomStart))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- groups + channels

@Composable
private fun GroupsRail(vm: AppViewModel) {
    val open = vm.focus == Focus.GROUPS
    val w by animateDpAsState(if (open) 460.px else 40.px, label = "rail")
    val state = rememberLazyListState()
    LaunchedEffect(vm.groupIdx) { state.scrollToItem(max(0, vm.groupIdx - 5)) }
    LazyColumn(
        Modifier.width(w).fillMaxHeight().background(if (open) Color(0xB30A0B0E) else Color.Transparent),
        state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 48.px, bottom = 48.px),
    ) {
        itemsIndexed(vm.groups, key = { _, it -> it.id }) { i, x ->
            val sel = i == vm.groupIdx
            Plate(open && sel, Modifier.padding(horizontal = 18.px, vertical = 3.px).fillMaxWidth().height(68.px), quiet = sel && !open, lift = 1.02f) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.px), verticalAlignment = Alignment.CenterVertically) {
                    val c = if (open && sel) Tok.OnPlate else if (sel) Tok.T1 else Tok.T2
                    if (open) {
                        T(groupGlyph(x.id), 26, c, FontWeight.SemiBold, Modifier.width(44.px))
                        T(groupTitle(x.name), 26, c, FontWeight.Medium, Modifier.weight(1f).padding(start = 12.px))
                        T("${x.channels.size}", 20, if (sel) Tok.OnPlate2 else Tok.T3)
                    }
                }
            }
        }
    }
}

/** Symbol for the built-in groups; provider groups go by name only. */
private fun groupGlyph(id: String) = when (id) {
    "cont" -> "▶"; "fav" -> "★"; "recent" -> "↺"; "all" -> "☰"; else -> ""
}

@Composable
private fun ChannelColumn(vm: AppViewModel) {
    val g = vm.currentGroup() ?: return
    val state = rememberLazyListState()
    LaunchedEffect(vm.chIdx, vm.groupIdx) { state.scrollToItem(max(0, vm.chIdx - 4)) }
    // the guide for the rows on screen and around them, decoded off the main thread before they are asked for
    LaunchedEffect(vm.chIdx, vm.groupIdx) { snapshotFlow { vm.guideVersion }.collect { vm.prefetch(g.channels, vm.chIdx) } }
    Column(Modifier.width(780.px).fillMaxHeight().padding(top = 40.px)) {
        Row(Modifier.fillMaxWidth().padding(start = 40.px, end = 40.px, bottom = 22.px)) {
            Eyebrow(groupTitle(g.name), Modifier.weight(1f))
            if (g.channels.isNotEmpty()) Eyebrow("${vm.chIdx + 1} / ${g.channels.size}")
        }
        if (g.channels.isEmpty()) T(str(if (g.id == "fav") R.string.fav_empty else R.string.group_empty), 24, Tok.T2, modifier = Modifier.padding(horizontal = 40.px))
        LazyColumn(state = state) {
            itemsIndexed(g.channels, key = { i, c -> c.id + i }) { i, c ->
                val foc = i == vm.chIdx && vm.focus == Focus.CHANNELS
                val sel = i == vm.chIdx
                Plate(foc, Modifier.padding(horizontal = 20.px, vertical = 2.px).fillMaxWidth().height(88.px), quiet = sel && !foc) {
                    val m = g.marks?.getOrNull(i)
                    if (m != null) ContinueRow(vm, m, foc) else ChannelRow(vm, c, foc)
                    if (m == null && c == vm.cur) Box(Modifier.align(Alignment.CenterStart).padding(start = 6.px).width(4.px).height(40.px).background(Tok.Live)) // now playing
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(vm: AppViewModel, c: Channel, foc: Boolean) {
    val p = vm.progAt(c, now())
    Row(Modifier.fillMaxSize().padding(horizontal = 20.px), verticalAlignment = Alignment.CenterVertically) {
        T("${c.num}", 22, if (foc) Tok.OnPlate2 else Tok.T3, FontWeight.SemiBold, Modifier.width(66.px).padding(end = 14.px), maxLines = 1)
        Logo(c, 64)
        Column(Modifier.weight(1f).padding(start = 22.px)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(c.name, 27, if (foc) Tok.OnPlate else Tok.T1, FontWeight.SemiBold, Modifier.weight(1f, fill = false))
                if (vm.isFav(c)) T(" ★", 20, Color(0xFFFFD60A))
            }
            T(p?.let { "${hm(it.start)} · ${it.title}" } ?: "", 21, if (foc) Tok.OnPlate2 else Tok.T2, modifier = Modifier.padding(top = 2.px, bottom = 6.px))
            if (p != null) Ticking(vm) { t -> Progress((t - p.start).toFloat() / p.duration, Modifier.fillMaxWidth(), if (foc) Tok.OnPlate else Tok.T1, if (foc) Color(0x1F000000) else Color(0x24FFFFFF), 3) }
        }
    }
}

@Composable
private fun ContinueRow(vm: AppViewModel, m: Mark, foc: Boolean) {
    Row(Modifier.fillMaxSize().padding(horizontal = 20.px), verticalAlignment = Alignment.CenterVertically) {
        Logo(m.ch, 64)
        Column(Modifier.weight(1f).padding(start = 22.px)) {
            T(m.p.title, 27, if (foc) Tok.OnPlate else Tok.T1, FontWeight.SemiBold)
            T("${m.ch.name} · ${dayWord(m.p.start)}, ${hm(m.p.start)} · " + str(R.string.remaining, ago(m.p.end - m.pos)), 21, if (foc) Tok.OnPlate2 else Tok.T2, modifier = Modifier.padding(top = 2.px, bottom = 6.px))
            Progress(m.watched, Modifier.fillMaxWidth(), if (foc) Tok.ArchOnPlate else Tok.Arch, if (foc) Color(0x1F000000) else Color(0x24FFFFFF), 3)
        }
    }
}

@Composable
private fun ChannelDetail(vm: AppViewModel, ch: Channel, modifier: Modifier) {
    val t = now()
    val p = vm.progAt(ch, t)
    Column(modifier.padding(start = 40.px, end = 72.px, bottom = 40.px)) {
        Logo(ch, 96)
        T(p?.title ?: ch.name, 40, Tok.T1, FontWeight.Bold, Modifier.padding(top = 28.px, bottom = 14.px), maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.px)) {
            LivePill()
            if (p != null) { T("${hm(p.start)} – ${hm(p.end)}", 22, Tok.T2); Ticking(vm) { T(str(R.string.remaining, ago(p.end - it)), 22, Tok.T2) } }
            if (ch.hd) Pill("HD", Tok.T2, outline = true)
            if (ch.archiveDays > 0) Pill(str(R.string.archive_days, ch.archiveDays), Tok.T2, outline = true)
        }
        val d = p?.let(vm::desc)
        if (!d.isNullOrBlank()) T(d, 23, Tok.T2, modifier = Modifier.padding(top = 18.px), maxLines = 3)
        var n = p?.let { vm.progAt(ch, it.end + 1) }
        Column(Modifier.padding(top = 22.px)) {
            repeat(3) {
                val q = n ?: return@repeat
                Row(Modifier.padding(vertical = 5.px)) {
                    T(hm(q.start), 23, Tok.T3, FontWeight.SemiBold, Modifier.width(90.px))
                    T(q.title, 23, Tok.T1)
                }
                n = vm.progAt(ch, q.end + 1)
            }
        }
    }
}

// ---------------------------------------------------------------- programme view: channels › days › programmes › details

@Composable
private fun ProgrammeView(vm: AppViewModel, ch: Channel, groupChannels: List<Channel>) {
    val list = vm.progList(ch)
    val days = vm.progDays(ch)
    if (list.isEmpty() || days.isEmpty()) return
    val progIdx = vm.progIdx.coerceIn(0, list.size - 1)
    val dayIdx = (if (vm.focus == Focus.DAYS) vm.dayIdx else days.indexOf(dayStart(list[progIdx].start))).coerceIn(0, days.size - 1)
    val day = days[dayIdx]
    val dayProgs = list.withIndex().filter { dayStart(it.value.start) == day }
    val anchor = if (vm.focus == Focus.DAYS) {
        // same rule as AppViewModel.sameTimeOn: the latest of the day's programmes started by this clock time
        val tod = day + (now() - dayStart(now()))
        max(0, dayProgs.indexOfLast { it.value.start <= tod })
    } else dayProgs.indexOfFirst { it.index == progIdx }.coerceAtLeast(0)

    // channels, collapsed to logos
    val ci = vm.chIdx
    val top = max(0, (ci - 4).coerceAtMost(groupChannels.size - 9))
    Column(Modifier.width(128.px).fillMaxHeight().padding(top = 104.px)) {
        groupChannels.drop(top).take(9).forEachIndexed { k, c ->
            val sel = top + k == ci
            Box(
                Modifier.padding(horizontal = 18.px, vertical = 2.px).fillMaxWidth().height(88.px)
                    .background(if (sel) Tok.Fill2 else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(18.px)),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.alpha(if (sel) 1f else 0.32f)) { Logo(c, 64) } }
        }
    }
    // days (a week of archive + what's ahead can be more than fit: keep the selected one in view)
    val dayState = rememberLazyListState()
    LaunchedEffect(dayIdx) { dayState.scrollToItem(max(0, dayIdx - 7)) }
    Column(Modifier.width(250.px).fillMaxHeight().padding(top = 40.px)) {
        Eyebrow(str(R.string.days_title), Modifier.padding(start = 34.px, bottom = 22.px))
        LazyColumn(state = dayState) {
            itemsIndexed(days, key = { _, d -> d }) { i, d ->
                val (name, date) = dayParts(d)
                val foc = i == dayIdx && vm.focus == Focus.DAYS
                Plate(foc, Modifier.padding(horizontal = 14.px, vertical = 3.px).fillMaxWidth().height(82.px), quiet = i == dayIdx && !foc, lift = 1.04f) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 22.px), verticalArrangement = Arrangement.Center) {
                        T(name, 26, if (foc) Tok.OnPlate else Tok.T1, FontWeight.SemiBold)
                        T(date, 19, if (foc) Tok.OnPlate2 else Tok.T2)
                    }
                }
            }
        }
    }
    // programmes of the day
    val state = rememberLazyListState()
    LaunchedEffect(anchor, day) { state.scrollToItem(max(0, anchor - 4)) }
    Column(Modifier.width(740.px).fillMaxHeight().padding(top = 40.px, start = 8.px, end = 8.px)) {
        Row(Modifier.fillMaxWidth().padding(start = 22.px, end = 22.px, bottom = 22.px)) {
            Eyebrow(ch.name, Modifier.weight(1f))
            Eyebrow(plural(R.plurals.programmes, dayProgs.size))
        }
        LazyColumn(state = state) {
            itemsIndexed(dayProgs, key = { _, it -> it.value.start }) { j, (i, p) ->
                val foc = vm.focus == Focus.PROGS && i == progIdx
                Plate(foc, Modifier.padding(vertical = 2.px).fillMaxWidth().height(68.px), quiet = vm.focus == Focus.DAYS && j == anchor, radius = 12, lift = 1.02f) {
                    ProgRow(vm, ch, p, foc)
                }
            }
        }
    }
    // details
    val shown = dayProgs.getOrNull(anchor)?.value ?: list[progIdx]
    Box(Modifier.fillMaxHeight().fillMaxWidth()) { ProgrammeDetail(vm, ch, shown, Modifier.align(Alignment.BottomStart)) }
}

@Composable
private fun ProgRow(vm: AppViewModel, ch: Channel, p: Programme, foc: Boolean) {
    val w = vm.watched(ch, p)
    val done = w >= DONE
    val now = p.isNow()
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.px), verticalAlignment = Alignment.CenterVertically) {
            T(hm(p.start), 24, if (foc) Tok.OnPlate2 else if (now) Tok.Live else Tok.T3, FontWeight.SemiBold, Modifier.width(100.px))
            T(p.title, 24, if (foc) Tok.OnPlate else if (done) Tok.T3 else if (p.isPast()) Tok.T2 else Tok.T1, modifier = Modifier.weight(1f))
            when {
                now -> LivePill(foc)
                else -> T(str(R.string.dur_min, (p.duration / 60_000).toInt()), 20, if (foc) Tok.OnPlate2 else Tok.T3, modifier = Modifier.padding(start = 16.px))
            }
            if (done) T("  ✓", 22, if (foc) Tok.OnPlate2 else Tok.T3)
        }
        if (w > 0f && !done) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 22.px, end = 22.px, bottom = 7.px).fillMaxWidth()) {
                Progress(w, Modifier.fillMaxWidth(), if (foc) Tok.ArchOnPlate else Tok.Arch, Color.Transparent, 3)
            }
        }
    }
}

@Composable
private fun ProgrammeDetail(vm: AppViewModel, ch: Channel, p: Programme, modifier: Modifier) {
    val w = vm.watched(ch, p)
    val done = w >= DONE
    val past = p.isPast()
    val arch = past && ch.inArchive(p)
    val resume = arch && w > 0f && !done
    Column(modifier.padding(start = 32.px, end = 64.px, bottom = 40.px)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(ch, 72)
            T(ch.name, 24, Tok.T2, FontWeight.SemiBold, Modifier.padding(start = 18.px))
        }
        T(p.title, 40, Tok.T1, FontWeight.Bold, Modifier.padding(top = 28.px, bottom = 14.px), maxLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.px)) {
            when {
                p.isNow() -> LivePill()
                !past -> Pill(str(R.string.soon), Tok.T2, outline = true)
                !arch -> Pill(str(R.string.out_archive), Tok.T2, outline = true)
                done -> Pill(str(R.string.watched_pill), Tok.T2, outline = true)
                else -> Pill(str(R.string.archive), Tok.Arch, Color(0x29FFB340))
            }
            T("${dayWord(p.start)}, ${hm(p.start)} – ${hm(p.end)}", 22, Tok.T2)
            T(str(R.string.dur_min, (p.duration / 60_000).toInt()), 22, Tok.T2)
        }
        if (resume) Row(Modifier.padding(top = 16.px), verticalAlignment = Alignment.CenterVertically) {
            Progress(w, Modifier.width(200.px), Tok.Arch)
            T(str(R.string.watched_of, ago((w * p.duration).toLong()), ago(p.duration)), 21, Tok.T2, modifier = Modifier.padding(start = 18.px))
        }
        val d = vm.desc(p) // null while it is being read: leave the space blank rather than flash «Опису немає»
        T(d?.ifBlank { str(R.string.no_desc) } ?: "", 23, if (d.isNullOrBlank()) Tok.T3 else Tok.T2, modifier = Modifier.padding(top = 18.px), maxLines = 4)
        val acts = when {
            p.isNow() -> listOf("OK" to str(R.string.act_live), str(R.string.key_ok_long) to str(R.string.act_from_start_prog))
            resume -> listOf("OK" to str(R.string.act_resume, ago((w * p.duration).toLong())), str(R.string.key_ok_long) to str(R.string.act_mark_menu))
            arch && done -> listOf("OK" to str(R.string.act_rewatch), str(R.string.key_ok_long) to str(R.string.act_mark_menu))
            arch -> listOf("OK" to str(R.string.act_watch))
            else -> emptyList()
        }
        Row(Modifier.padding(top = 28.px), horizontalArrangement = Arrangement.spacedBy(32.px)) {
            for ((k, label) in acts) Row(verticalAlignment = Alignment.CenterVertically) {
                KeyCap(k); T(label, 21, Tok.T2, modifier = Modifier.padding(start = 10.px))
            }
        }
    }
}

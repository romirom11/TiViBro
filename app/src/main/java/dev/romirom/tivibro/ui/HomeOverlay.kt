package dev.romirom.tivibro.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.romirom.tivibro.AppViewModel
import dev.romirom.tivibro.AppViewModel.Companion.DONE
import dev.romirom.tivibro.AppViewModel.Companion.ago
import dev.romirom.tivibro.R
import dev.romirom.tivibro.data.MIN
import dev.romirom.tivibro.data.inArchive
import dev.romirom.tivibro.data.now
import dev.romirom.tivibro.home.HomeCard
import dev.romirom.tivibro.home.HomeKind
import dev.romirom.tivibro.home.HomeRow
import dev.romirom.tivibro.str
import dev.romirom.tivibro.L10n
import dev.romirom.tivibro.tmdb.TmdbInfo
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlin.math.max

/** Card size on the 1920×1080 stage (16:9). */
private const val CARD_W = 352
private const val CARD_H = 198
/** A row: its title, the cards, room for the focused card's lift. */
private const val ROW_H = CARD_H + 92

/**
 * Home: a glass layer over the playing video. The focused card's details on top, the rows under them; the video
 * shows through on the right of the details.
 */
@Composable
fun HomeOverlay(vm: AppViewModel) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        android.util.Log.i("tivibro", "home: first frame ${android.os.SystemClock.uptimeMillis() - vm.listKeyAt} ms after the key")
    }
    val rows = vm.homeRows
    // a sheet, the menu or a context menu over Home: its focus is the lit one, Home's goes quiet
    val active = vm.sheets.isEmpty() && vm.ctx == null && vm.markCtx == null
    Box(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(0f to Color(0xF0060709), .45f to Color(0xC8060709), 1f to Color(0x5A060709)))
            .background(Brush.verticalGradient(0f to Color.Transparent, .38f to Color(0x66060709), .5f to Color(0xD8060709), 1f to Color(0xF5060709))),
    ) {
        Column(Modifier.fillMaxSize().padding(bottom = 84.px)) {
            Row(Modifier.fillMaxWidth().padding(start = 96.px, end = 72.px, top = 48.px), verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(str(R.string.menu_home) + (vm.playlist?.title?.let { " · $it" } ?: ""), Modifier.weight(1f))
                Ticking(vm) { t -> T(hm(t), 34, Tok.T1, FontWeight.SemiBold) }
            }
            Box(Modifier.fillMaxWidth().height(380.px).padding(start = 96.px, end = 72.px)) {
                val c = vm.homeCard()
                if (c != null) HomeDetails(vm, c, Modifier.align(Alignment.BottomStart))
                else if (rows.getOrNull(vm.homeRowIdx)?.kind == HomeKind.SETUP) SetupDetails(Modifier.align(Alignment.BottomStart))
            }
            HomeRows(vm, rows, active)
        }
    }
}

@Composable
private fun HomeRows(vm: AppViewModel, rows: List<HomeRow>, active: Boolean) {
    val state = rememberLazyListState()
    val stage = LocalStage.current
    LaunchedEffect(vm.homeRowIdx, rows.size, stage) { state.scrollNear(vm.homeRowIdx, 0) }
    LazyColumn(Modifier.fillMaxWidth().padding(top = 16.px).fadeEdges(40), state = state) {
        itemsIndexed(rows, key = { _, r -> r.kind }) { i, r ->
            val focusedRow = i == vm.homeRowIdx
            if (r.kind == HomeKind.SETUP) SetupRow(focusedRow, active) else CardRow(vm, r, focusedRow, if (focusedRow) vm.homeColIdx else -1, active)
        }
        if (vm.homeLoading) item(key = "loading") {
            T(str(R.string.home_loading), 24, Tok.T3, modifier = Modifier.padding(start = 96.px, top = 12.px))
        } else if (vm.homeEmpty) item(key = "empty") {
            T(str(R.string.home_nothing), 24, Tok.T3, modifier = Modifier.padding(start = 96.px, top = 12.px))
        }
    }
}

fun rowTitle(k: HomeKind): String = when (k) {
    HomeKind.CONTINUE -> str(R.string.group_cont)
    HomeKind.NOW -> str(R.string.home_now)
    HomeKind.STARTED -> str(R.string.home_started)
    HomeKind.SOON -> str(R.string.home_soon)
    HomeKind.FILMS -> str(R.string.home_films)
    HomeKind.SERIES -> str(R.string.home_series)
    HomeKind.SETUP -> ""
}

@Composable
private fun CardRow(vm: AppViewModel, r: HomeRow, focusedRow: Boolean, col: Int, active: Boolean) {
    val state = rememberLazyListState()
    LaunchedEffect(col) { if (col >= 0) state.animateScrollToItem(max(0, col - 1)) }
    Column(Modifier.fillMaxWidth().height(ROW_H.px)) {
        Row(Modifier.padding(start = 96.px, end = 72.px, top = 8.px), verticalAlignment = Alignment.CenterVertically) {
            T(rowTitle(r.kind), 26, if (focusedRow) Tok.T1 else Tok.T2, FontWeight.SemiBold)
            T("  ${r.cards.size}", 20, Tok.T3, FontWeight.Medium)
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(start = 96.px, end = 96.px, top = 14.px, bottom = 14.px),
            horizontalArrangement = Arrangement.spacedBy(24.px),
        ) {
            itemsIndexed(r.cards, key = { _, c -> c.key }) { j, c -> HomeCardView(vm, c, j == col && active, j == col) }
        }
    }
}

@Composable
private fun HomeCardView(vm: AppViewModel, c: HomeCard, foc: Boolean, sel: Boolean) {
    val s by animateFloatAsState(if (foc) 1.06f else 1f, tween(140), label = "lift")
    val shape = RoundedCornerShape(18.px)
    val info = vm.tmdbInfo(c.p)
    val picture = info?.backdropUrl != null
    Box(
        Modifier.size(CARD_W.px, CARD_H.px)
            .graphicsLayer { scaleX = s; scaleY = s }
            .then(if (foc) Modifier.shadow(14.dp, shape) else Modifier)
            .clip(shape)
            .background(if (foc && !picture) Tok.Plate else if (sel) Color(0x38FFFFFF) else Color(0x1FFFFFFF)),
    ) {
        when {
            picture -> PictureCard(vm, c, info!!, foc)
            info?.posterUrl != null -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxSize()) { TypeCard(vm, c, foc) }
                AsyncImage(info.posterUrl, null, Modifier.width((CARD_H * 2 / 3).px).fillMaxSize(), contentScale = ContentScale.Crop)
            }
            else -> TypeCard(vm, c, foc)
        }
        // on a picture the lit plate would hide it: a white frame instead
        if (foc && picture) Box(Modifier.fillMaxSize().border(4.px, Tok.Plate, shape))
        else if (!foc) Box(Modifier.fillMaxSize().border(1.dp, if (sel) Color(0x66FFFFFF) else Tok.Hair, shape))
    }
}

/** A card on TMDB's backdrop: the picture, a shade under the title, the channel's logo and the badge kept. */
@Composable
private fun BoxScope.PictureCard(vm: AppViewModel, c: HomeCard, info: TmdbInfo, foc: Boolean) {
    AsyncImage(info.backdropUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x33000000), .45f to Color(0x1A000000), 1f to Color(0xE6000000))))
    Column(Modifier.fillMaxSize().padding(start = 22.px, end = 22.px, top = 18.px, bottom = 20.px)) {
        Logo(c.ch, 52)
        Spacer(Modifier.weight(1f))
        T(c.p.title, 29, Tok.T1, FontWeight.SemiBold, maxLines = 2)
        Ticking(vm) { t -> CardMeta(c, false, t) }
    }
    Ticking(vm) { t ->
        val f = cardProgress(c, t)
        if (f != null) Progress(f, Modifier.align(Alignment.BottomStart).fillMaxWidth(), if (c.mark != null) Tok.Arch else Tok.Live, Color(0x33FFFFFF), 5)
    }
}

/** A card without a picture: the channel's logo on its plate, a large title, the time and a badge. */
@Composable
private fun BoxScope.TypeCard(vm: AppViewModel, c: HomeCard, foc: Boolean) {
    Column(Modifier.fillMaxSize().padding(start = 22.px, end = 22.px, top = 18.px, bottom = 20.px)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(c.ch, 52)
            T(c.ch.name, 19, if (foc) Tok.OnPlate2 else Tok.T2, FontWeight.Medium, Modifier.weight(1f).padding(start = 14.px))
        }
        Spacer(Modifier.weight(1f))
        T(c.p.title, 29, if (foc) Tok.OnPlate else Tok.T1, FontWeight.SemiBold, maxLines = 2)
        Ticking(vm) { t -> CardMeta(c, foc, t) }
    }
    Ticking(vm) { t ->
        val f = cardProgress(c, t)
        if (f != null) Progress(
            f, Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            if (c.mark != null) (if (foc) Tok.ArchOnPlate else Tok.Arch) else if (foc) Tok.LiveOnPlate else Tok.Live,
            if (foc) Color(0x1F000000) else Color(0x24FFFFFF), 5,
        )
    }
}

@Composable
private fun CardMeta(c: HomeCard, foc: Boolean, t: Long) {
    Row(Modifier.padding(top = 6.px), verticalAlignment = Alignment.CenterVertically) {
        val (text, color) = badge(c, t, foc)
        T(text, 19, color, FontWeight.Bold)
        T("  ${hm(c.p.start)} – ${hm(c.p.end)}", 19, if (foc) Tok.OnPlate2 else Tok.T2)
    }
}

/** «Ефір», «Через 12 хв», «З початку −18 хв», «Архів», «О 21:30». */
private fun badge(c: HomeCard, t: Long, foc: Boolean): Pair<String, Color> {
    val p = c.p
    val live = if (foc) Tok.LiveOnPlate else Tok.Live
    val arch = if (foc) Tok.ArchOnPlate else Tok.Arch
    val plain = if (foc) Tok.OnPlate else Tok.T1
    return when {
        c.mark != null || p.isPast(t) -> str(R.string.archive) to arch
        p.isNow(t) && c.kind == HomeKind.STARTED -> str(R.string.badge_started, ago(t - p.start)) to arch
        p.isNow(t) -> str(R.string.badge_live) to live
        p.start - t < 60 * MIN -> str(R.string.badge_in, ago(p.start - t + MIN - 1)) to plain
        else -> str(R.string.badge_at, hm(p.start)) to plain
    }
}

/** How far along: the bookmark's position, the broadcast's; nothing for what hasn't started. */
private fun cardProgress(c: HomeCard, t: Long): Float? = when {
    c.mark != null -> c.mark.watched
    c.p.isNow(t) -> (t - c.p.start).toFloat() / c.p.duration
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeDetails(vm: AppViewModel, c: HomeCard, modifier: Modifier) {
    val p = c.p
    val info = vm.tmdbInfo(p)
    Row(modifier.padding(bottom = 8.px), verticalAlignment = Alignment.Bottom) {
    if (info?.posterUrl != null) {
        AsyncImage(
            info.posterUrl, null,
            Modifier.padding(end = 40.px).size(216.px, 324.px).clip(RoundedCornerShape(16.px)).border(1.dp, Tok.Hair, RoundedCornerShape(16.px)),
            contentScale = ContentScale.Crop,
        )
    }
    Column(Modifier.widthIn(max = 1180.px)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(c.ch, 56)
            T(c.ch.name, 24, Tok.T2, FontWeight.SemiBold, Modifier.padding(start = 18.px))
        }
        T(p.title, 50, Tok.T1, FontWeight.Bold, Modifier.padding(top = 20.px, bottom = 12.px), maxLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.px), verticalArrangement = Arrangement.spacedBy(10.px)) {
            Ticking(vm) { t ->
                val (text, _) = badge(c, t, false)
                when {
                    p.isNow(t) && c.kind != HomeKind.STARTED && c.mark == null -> LivePill()
                    c.mark != null || p.isPast(t) || c.kind == HomeKind.STARTED -> Pill(text, Tok.Arch, Color(0x29FFB340))
                    else -> Pill(text, Tok.T2, outline = true)
                }
            }
            T("${dayWord(p.start)}, ${hm(p.start)} – ${hm(p.end)}", 22, Tok.T2)
            T(str(R.string.dur_min, (p.duration / 60_000).toInt()), 22, Tok.T2)
            if (info != null) TmdbMeta(info)
        }
        // a film is best told by TMDB's overview; a show's episode by the guide's own description
        val guide = vm.desc(p)
        val d = if (info?.type == "movie") info.overview.ifBlank { guide.orEmpty() } else guide?.ifBlank { info?.overview.orEmpty() } ?: info?.overview.orEmpty()
        T(d, 23, Tok.T2, modifier = Modifier.padding(top = 14.px).heightIn(min = 62.px), maxLines = 2)
        FlowRow(Modifier.padding(top = 16.px), horizontalArrangement = Arrangement.spacedBy(32.px), verticalArrangement = Arrangement.spacedBy(12.px)) {
            for ((k, label) in homeActions(vm, c)) Row(verticalAlignment = Alignment.CenterVertically) {
                KeyCap(k); T(label, 21, Tok.T2, modifier = Modifier.padding(start = 10.px))
            }
        }
    }
    }
}

/** «TMDB 7.8 · 2004 · Thriller, Action · 1 h 48 min». */
@Composable
private fun TmdbMeta(i: TmdbInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (i.votes > 0) {
            T("TMDB ", 20, Color(0xFF01B4E4), FontWeight.Black)
            T(String.format(L10n.locale, "%.1f", i.rating), 22, Tok.T1, FontWeight.SemiBold)
        }
        val rest = listOfNotNull(i.year?.toString(), i.genres.take(3).joinToString(", ").ifEmpty { null }, i.runtime?.let { ago(it * MIN) })
        if (rest.isNotEmpty()) T((if (i.votes > 0) " · " else "") + rest.joinToString(" · "), 22, Tok.T2)
    }
}

/** What OK and a long OK do on [c]: the same rules as AppViewModel.homeOk / homeLongOk. */
private fun homeActions(vm: AppViewModel, c: HomeCard): List<Pair<String, String>> {
    val p = c.p
    val long = str(R.string.key_ok_long)
    val w = vm.watched(c.ch, p)
    return when {
        c.mark != null -> listOf("OK" to str(R.string.act_resume, ago((w * p.duration).toLong())), long to str(R.string.act_mark_menu))
        c.kind == HomeKind.STARTED && p.isNow() && vm.canArchive(c.ch) -> listOf("OK" to str(R.string.act_from_start_prog), long to str(R.string.act_live))
        p.isNow() -> listOfNotNull("OK" to str(R.string.act_live), (long to str(R.string.act_from_start_prog)).takeIf { vm.canArchive(c.ch) })
        p.isPast() && c.ch.inArchive(p) -> listOf("OK" to str(if (w >= DONE) R.string.act_rewatch else R.string.act_watch))
        p.isPast() -> emptyList()
        else -> listOf("OK" to str(R.string.hint_when), long to str(R.string.ctx_channel))
    }
}

@Composable
private fun SetupDetails(modifier: Modifier) {
    Column(modifier.widthIn(max = 1100.px).padding(bottom = 8.px)) {
        T(str(R.string.home_setup_title), 50, Tok.T1, FontWeight.Bold, Modifier.padding(bottom = 16.px), maxLines = 2)
        T(str(R.string.home_setup_desc), 25, Tok.T2, maxLines = 3)
    }
}

@Composable
private fun SetupRow(sel: Boolean, active: Boolean) {
    val foc = sel && active
    Box(Modifier.fillMaxWidth().padding(start = 96.px, top = 24.px, bottom = 24.px)) {
        Plate(foc, Modifier.height(84.px), quiet = !foc, radius = 20, lift = 1.05f) {
            T(
                "＋  " + str(R.string.home_setup_button), 28, if (foc) Tok.OnPlate else Tok.T1, FontWeight.SemiBold,
                Modifier.align(Alignment.Center).padding(horizontal = 40.px),
            )
        }
    }
}

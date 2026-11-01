package dev.romirom.tivibro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.romirom.tivibro.AppViewModel
import dev.romirom.tivibro.R
import dev.romirom.tivibro.search.SearchItem
import dev.romirom.tivibro.search.When
import dev.romirom.tivibro.str
import kotlin.math.min

/**
 * «Пошук» on the TV: a text field on top, results under it as you type (channels, then programmes; the recent queries
 * while it is empty), over the video like Home.
 *
 * The remote is routed by the view model as everywhere else; Compose focus is used for one thing only, the text field:
 * it holds the focus (and with it the system keyboard) while [AppViewModel.searchOnField], and loses it the moment the
 * results take over, so typing never lands in a field that is not lit. Keys reach the field only when the view model
 * lets them through (AppViewModel.fieldKey); with the keyboard up, the keyboard gets them before the app does.
 */
@Composable
fun SearchOverlay(vm: AppViewModel) {
    // a sheet, the menu or a context menu over search: their focus is the lit one, and typing must not reach the field
    val active = vm.sheets.isEmpty() && vm.ctx == null && vm.markCtx == null
    val typing = active && vm.searchOnField
    Box(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(0f to Color(0xF5060709), .55f to Color(0xE6060709), 1f to Color(0xA0060709))),
    ) {
        Column(Modifier.fillMaxSize().padding(start = 96.px, end = 72.px, top = 48.px, bottom = 84.px)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow(str(R.string.menu_search) + (vm.playlist?.title?.let { " · $it" } ?: ""), Modifier.weight(1f))
                Ticking(vm) { t -> T(hm(t), 34, Tok.T1, FontWeight.SemiBold) }
            }
            SearchField(vm, typing, Modifier.padding(top = 28.px))
            SearchResults(vm, active && !vm.searchOnField)
        }
    }
}

@Composable
private fun SearchField(vm: AppViewModel, typing: Boolean, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // the field's own value, so that the cursor and the keyboard's composing text survive; the query is mirrored out
    var value by remember { mutableStateOf(TextFieldValue(vm.searchQuery, TextRange(vm.searchQuery.length))) }
    LaunchedEffect(vm.searchQuery) { // a recent query picked, or search opened afresh
        if (value.text != vm.searchQuery) value = TextFieldValue(vm.searchQuery, TextRange(vm.searchQuery.length))
    }
    LaunchedEffect(typing) {
        if (typing) focus.requestFocus()
        else { keyboard?.hide(); focusManager.clearFocus(force = true) }
    }
    LaunchedEffect(vm.searchKeyboard) { if (typing) { focus.requestFocus(); keyboard?.show() } }
    val shape = RoundedCornerShape(22.px)
    val fg = if (typing) Tok.OnPlate else Tok.T1
    Row(
        modifier.fillMaxWidth().height(96.px)
            .background(if (typing) Tok.Plate else Tok.Fill2, shape)
            .border(1.dp, if (typing) Color.Transparent else Tok.Hair, shape)
            .padding(horizontal = 32.px),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Magnifier(if (typing) Tok.OnPlate2 else Tok.T2)
        Box(Modifier.weight(1f).padding(start = 22.px), contentAlignment = Alignment.CenterStart) {
            if (value.text.isEmpty()) T(str(R.string.search_hint), 34, if (typing) Tok.OnPlate2 else Tok.T3)
            BasicTextField(
                value = value,
                onValueChange = { value = it; vm.onSearchText(it.text) },
                // focusable only while lit: with focus cleared, Compose hands it back to the first focusable it finds,
                // and a text field that gets it brings the keyboard back up over the results (seen on the TV)
                modifier = Modifier.fillMaxWidth().focusProperties { canFocus = typing }.focusRequester(focus),
                singleLine = true,
                textStyle = TextStyle(color = fg, fontSize = 34.tx, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(if (typing) Tok.OnPlate else Color.Transparent),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.onSearchSubmit() }, onDone = { keyboard?.hide(); vm.onSearchSubmit() }),
            )
        }
        if (typing) KeyHint("OK", str(R.string.hint_keyboard), Tok.OnPlate2)
    }
}

@Composable
private fun KeyHint(key: String, label: String, color: Color) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.height(32.px).border(1.dp, Color(0x33000000), RoundedCornerShape(8.px)).padding(horizontal = 9.px), contentAlignment = Alignment.Center) {
        T(key, 17, color, FontWeight.SemiBold)
    }
    T(label, 21, color, modifier = Modifier.padding(start = 10.px))
}

@Composable
private fun Magnifier(color: Color) = Canvas(Modifier.size(34.px)) {
    val w = size.minDimension
    val r = w * .34f
    val c = Offset(r + w * .06f, r + w * .06f)
    drawCircle(color, r, c, style = Stroke(w * .1f))
    drawLine(color, Offset(c.x + r * .72f, c.y + r * .72f), Offset(w * .94f, w * .94f), w * .11f, StrokeCap.Round)
}

/** What the list shows, with the section headers in between; [item] is the index into searchRes.items, or -1. */
private class Line(val key: String, val header: String? = null, val item: Int = -1)

@Composable
private fun SearchResults(vm: AppViewModel, active: Boolean) {
    val res = vm.searchRes
    val lines = remember(res) {
        val out = ArrayList<Line>()
        var i = 0
        fun section(title: String, n: Int, key: String) {
            if (n == 0) return
            out += Line("h$key", header = title)
            repeat(n) { out += Line("$key$i", item = i); i++ }
        }
        section(str(R.string.search_recent), res.recent.size, "r")
        section(str(R.string.rp_channels), res.channels.size, "c")
        section(str(R.string.rp_programmes), res.programmes.size, "p")
        out
    }
    val state = rememberLazyListState()
    val stage = LocalStage.current
    val at = lines.indexOfFirst { it.item == vm.searchIdx }
    LaunchedEffect(vm.searchIdx, res, stage, active) { if (active && at >= 0) state.scrollNear(at, 3) else if (!active) state.scrollToItem(0) }
    val empty = when {
        res.query != vm.searchQuery -> null // still searching
        vm.searchQuery.isBlank() && res.recent.isEmpty() -> str(R.string.search_empty)
        vm.searchQuery.isNotBlank() && res.items.isEmpty() -> str(R.string.rp_nothing)
        else -> null
    }
    if (empty != null) T(empty, 26, Tok.T3, modifier = Modifier.padding(start = 8.px, top = 36.px), maxLines = 2)
    if (vm.searchIndexing && vm.searchQuery.isNotBlank()) T(str(R.string.rp_indexing), 20, Tok.T3, modifier = Modifier.padding(start = 8.px, top = 18.px))
    LazyColumn(
        Modifier.fillMaxWidth().padding(top = 20.px).fadeEdges(28),
        state = state,
        contentPadding = PaddingValues(vertical = 10.px),
    ) {
        itemsIndexed(lines, key = { _, l -> l.key }) { _, l ->
            if (l.header != null) Eyebrow(l.header, Modifier.padding(start = 24.px, top = 18.px, bottom = 8.px))
            else {
                val foc = active && l.item == vm.searchIdx
                val it = res.items.getOrNull(l.item) ?: return@itemsIndexed
                val dim = it is SearchItem.Prog && it.hit.kind == When.UPCOMING
                Plate(foc, Modifier.padding(vertical = 3.px).fillMaxWidth(min(1f, 1400f / LocalStage.current.width)).height(if (it is SearchItem.Recent) 68.px else 96.px).alpha(if (dim && !foc) .55f else 1f), lift = 1.02f) {
                    when (it) {
                        is SearchItem.Recent -> Row(Modifier.fillMaxSize().padding(horizontal = 26.px), verticalAlignment = Alignment.CenterVertically) {
                            T("↺", 26, if (foc) Tok.OnPlate2 else Tok.T3, modifier = Modifier.width(52.px))
                            T(it.query, 27, if (foc) Tok.OnPlate else Tok.T1, FontWeight.Medium)
                        }
                        is SearchItem.Ch -> ChannelHitRow(vm, it, foc)
                        is SearchItem.Prog -> ProgrammeHitRow(vm, it, foc)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelHitRow(vm: AppViewModel, it: SearchItem.Ch, foc: Boolean) {
    val p = it.now
    Row(Modifier.fillMaxSize().padding(horizontal = 22.px), verticalAlignment = Alignment.CenterVertically) {
        Logo(it.ch, 64)
        Column(Modifier.weight(1f).padding(start = 22.px)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(it.ch.name, 27, if (foc) Tok.OnPlate else Tok.T1, FontWeight.SemiBold, Modifier.weight(1f, fill = false))
                if (vm.isFav(it.ch)) T(" ★", 20, Color(0xFFFFD60A))
            }
            T(p?.let { "${hm(it.start)} · ${it.title}" } ?: it.ch.group, 21, if (foc) Tok.OnPlate2 else Tok.T2, modifier = Modifier.padding(top = 2.px, bottom = 6.px))
            if (p != null) Ticking(vm) { t -> Progress((t - p.start).toFloat() / p.duration, Modifier.fillMaxWidth(.6f), if (foc) Tok.OnPlate else Tok.T1, if (foc) Color(0x1F000000) else Color(0x24FFFFFF), 3) }
        }
        T("${it.ch.num}", 22, if (foc) Tok.OnPlate2 else Tok.T3, FontWeight.SemiBold, Modifier.padding(start = 20.px))
    }
}

@Composable
private fun ProgrammeHitRow(vm: AppViewModel, it: SearchItem.Prog, foc: Boolean) {
    val h = it.hit
    Row(Modifier.fillMaxSize().padding(horizontal = 22.px), verticalAlignment = Alignment.CenterVertically) {
        Logo(h.ch, 64)
        Column(Modifier.weight(1f).padding(start = 22.px)) {
            T(h.p.title, 27, if (foc) Tok.OnPlate else Tok.T1, FontWeight.SemiBold)
            val time = if (h.kind == When.NOW) "${hm(h.p.start)}–${hm(h.p.end)}" else "${dayWord(h.p.start)}, ${hm(h.p.start)}"
            T("${h.ch.name} · $time", 21, if (foc) Tok.OnPlate2 else Tok.T2, modifier = Modifier.padding(top = 2.px, bottom = 6.px))
            if (h.kind == When.NOW) Ticking(vm) { t -> Progress((t - h.p.start).toFloat() / h.p.duration, Modifier.fillMaxWidth(.6f), if (foc) Tok.OnPlate else Tok.T1, if (foc) Color(0x1F000000) else Color(0x24FFFFFF), 3) }
        }
        Box(Modifier.padding(start = 20.px)) {
            when (h.kind) {
                When.NOW -> LivePill(onPlate = foc)
                When.ARCHIVE -> Pill(str(R.string.rp_archive), if (foc) Tok.ArchOnPlate else Tok.Arch, if (foc) Color(0x14000000) else Tok.Fill2)
                When.UPCOMING -> Pill(str(R.string.rp_upcoming), if (foc) Tok.OnPlate2 else Tok.T2, if (foc) Color(0x14000000) else Tok.Fill2)
            }
        }
    }
}

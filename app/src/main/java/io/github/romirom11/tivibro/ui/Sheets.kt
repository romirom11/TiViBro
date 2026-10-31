package io.github.romirom11.tivibro.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.romirom11.tivibro.AppViewModel
import io.github.romirom11.tivibro.CtxItem
import io.github.romirom11.tivibro.GuideRow
import io.github.romirom11.tivibro.GuideSource
import io.github.romirom11.tivibro.LinkPurpose
import io.github.romirom11.tivibro.MarkAction
import io.github.romirom11.tivibro.MenuItem
import io.github.romirom11.tivibro.PlaylistAction
import io.github.romirom11.tivibro.SettingsRow
import io.github.romirom11.tivibro.Sheet
import io.github.romirom11.tivibro.data.Channel
import io.github.romirom11.tivibro.data.GuideMode
import io.github.romirom11.tivibro.data.Mark
import io.github.romirom11.tivibro.data.PlaylistInfo
import kotlin.math.max

/** «1 канал», «3 канали», «12 каналів». */
fun channelsWord(n: Int): String {
    val a = n % 10
    val b = n % 100
    return "$n " + when {
        a == 1 && b != 11 -> "канал"
        a in 2..4 && b !in 12..14 -> "канали"
        else -> "каналів"
    }
}

// ---------------------------------------------------------------- side menu (prototype #menu)

@Composable
fun Menu(vm: AppViewModel) {
    vm.cfgVersion // the settings live in Prefs: re-read them when they change
    val top = vm.sheets.lastOrNull() == Sheet.MENU
    Box(
        Modifier.fillMaxSize().background(
            // opaque over the groups rail it opens from, fading out over the channels
            Brush.horizontalGradient(0f to Color(0xFA060709), .3f to Color(0xF2060709), .62f to Color(0x40060709), 1f to Color.Transparent),
        ),
    ) {
        Column(Modifier.width(600.px).fillMaxHeight().padding(start = 32.px, end = 32.px, top = 64.px, bottom = 84.px)) {
            Row(Modifier.padding(start = 24.px, end = 24.px, bottom = 34.px), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(72.px).background(Brush.linearGradient(listOf(Color(0xFF4A7BD8), Color(0xFF2A3F78))), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { T((vm.playlist?.title ?: "TiViBro").take(1).uppercase(), 30, Tok.T1, FontWeight.Bold) }
                Column(Modifier.padding(start = 20.px)) {
                    T(vm.playlist?.title ?: "TiViBro", 28, Tok.T1, FontWeight.SemiBold)
                    T("${channelsWord(vm.channels.size)} · показано ${vm.allGroups.count(vm::groupShown)} груп з ${vm.allGroups.size}", 21, Tok.T2)
                }
            }
            vm.menuItems().forEachIndexed { i, m ->
                val foc = top && i == vm.menuIdx
                Plate(foc, Modifier.padding(bottom = 6.px).fillMaxWidth().height(78.px), quiet = !top && i == vm.menuIdx, lift = 1.025f) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 26.px), verticalAlignment = Alignment.CenterVertically) {
                        val (glyph, title) = when (m) {
                            MenuItem.GROUPS -> "☰" to "Групи каналів"
                            MenuItem.GUIDE -> "↻" to "Оновити програму передач"
                            MenuItem.SETTINGS -> "⚙︎" to "Налаштування"
                            MenuItem.EXIT -> "⏻" to "Вихід"
                        }
                        T(glyph, 30, if (foc) Tok.OnPlate else Tok.T2, modifier = Modifier.width(54.px))
                        T(title, 28, if (foc) Tok.OnPlate else Tok.T1, FontWeight.Medium, Modifier.weight(1f))
                        val small = when (m) {
                            MenuItem.GUIDE -> vm.status?.substringAfterLast("… ", "")?.takeIf { it.endsWith("%") }.orEmpty()
                            else -> ""
                        }
                        if (small.isNotEmpty()) T(small, 21, if (foc) Tok.OnPlate2 else Tok.T2, FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- side sheets (prototype .side)

@Composable
fun SideSheet(vm: AppViewModel, sheet: Sheet) {
    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0x26000000), Color(0x99000000))))) {
        Row(Modifier.align(Alignment.TopEnd).width(900.px).fillMaxHeight()) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(Tok.Hair))
            Column(Modifier.weight(1f).fillMaxHeight().background(Tok.GlassHi).padding(top = 64.px, bottom = 96.px)) {
                when (sheet) {
                    Sheet.SETTINGS -> SettingsSheet(vm)
                    Sheet.GROUPS -> GroupsSheet(vm)
                    Sheet.HIDDEN -> HiddenSheet(vm)
                    Sheet.PLAYLISTS -> PlaylistsSheet(vm)
                    Sheet.PLAYLIST -> PlaylistSheet(vm)
                    Sheet.GUIDE -> GuideSheet(vm)
                    Sheet.LINK -> LinkSheet(vm)
                    Sheet.MENU -> Unit
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, sub: String) {
    T(title, 44, Tok.T1, FontWeight.Bold, Modifier.padding(start = 64.px, end = 64.px, bottom = 10.px))
    T(sub, 22, Tok.T2, modifier = Modifier.padding(start = 64.px, end = 64.px, bottom = 30.px), maxLines = 2)
}

@Composable
private fun Section(text: String) = Eyebrow(text, Modifier.padding(start = 64.px, top = 26.px, bottom = 10.px))

/** One `.it` row: title, optional explanation, value on the right. */
@Composable
private fun SheetRow(foc: Boolean, title: String, desc: String?, dim: Boolean = false, value: @Composable () -> Unit = {}) {
    Plate(foc, Modifier.padding(start = 36.px, end = 36.px, bottom = 4.px).fillMaxWidth().heightIn(min = 84.px), lift = 1.02f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 28.px, vertical = 14.px), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T(title, 26, if (foc) Tok.OnPlate else if (dim) Tok.T3 else Tok.T1, FontWeight.Medium)
                if (desc != null) T(desc, 20, if (foc) Tok.OnPlate2 else Tok.T2, modifier = Modifier.padding(top = 5.px), maxLines = 2)
            }
            value()
        }
    }
}

@Composable
private fun Value(text: String, foc: Boolean) = T(text, 23, if (foc) Tok.OnPlate2 else Tok.T2, FontWeight.Medium, Modifier.padding(start = 24.px))

@Composable
private fun SettingsSheet(vm: AppViewModel) {
    vm.cfgVersion
    SheetTitle("Налаштування", "Плейлисти, які групи й канали показувати, програма передач")
    SettingsRow.entries.forEachIndexed { i, r ->
        val foc = vm.rowIdx == i
        when (r) {
            SettingsRow.PLAYLISTS -> {
                Section("Плейлист")
                SheetRow(foc, "Плейлисти", vm.playlist?.let { "Зараз: ${it.title}" }) {
                    Value(vm.playlists.size.toString(), foc)
                }
            }
            SettingsRow.GROUPS -> {
                Section("Канали")
                SheetRow(foc, "Групи каналів", "Які групи показувати у списку") {
                    Value("${vm.allGroups.count(vm::groupShown)} з ${vm.allGroups.size}", foc)
                }
            }
            SettingsRow.HIDDEN -> SheetRow(foc, "Приховані канали", "Повернути канали, які ви приховали") {
                Value(vm.hiddenChannels().size.takeIf { it > 0 }?.toString() ?: "немає", foc)
            }
            SettingsRow.GUIDE -> {
                Section("Програма передач")
                SheetRow(foc, "Програма передач", guideStatus(vm)) {
                    Value(sourceLabel(vm.playlist?.guideMode ?: GuideMode.AUTO), foc)
                }
            }
        }
    }
}

@Composable
private fun GroupsSheet(vm: AppViewModel) {
    vm.cfgVersion
    SheetTitle("Групи каналів", "OK — показати або сховати групу. Сховані групи зникають зі списку і з перемикання")
    val state = rememberLazyListState()
    LaunchedEffect(vm.rowIdx) { state.scrollToItem(max(0, vm.rowIdx - 4)) }
    LazyColumn(state = state, contentPadding = PaddingValues(vertical = 6.px)) {
        itemsIndexed(vm.allGroups, key = { _, g -> g.id }) { i, g ->
            vm.cfgVersion // settings live in Prefs: strong skipping would keep the old row without this read
            val foc = i == vm.rowIdx
            val show = vm.groupShown(g)
            SheetRow(foc, g.name, channelsWord(g.channels.size) + if (show) "" else " · прихована", dim = !show) {
                Switch(show, onPlate = foc, modifier = Modifier.padding(start = 24.px))
            }
        }
    }
}

@Composable
private fun HiddenSheet(vm: AppViewModel) {
    vm.cfgVersion
    val list = vm.hiddenChannels()
    SheetTitle("Приховані канали", if (list.isEmpty()) "Утримайте OK на каналі → «Приховати канал»" else "OK — повернути канал у список")
    if (list.isEmpty()) {
        T("Прихованих каналів немає", 26, Tok.T2, modifier = Modifier.padding(start = 64.px, top = 12.px))
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(vm.rowIdx) { state.scrollToItem(max(0, vm.rowIdx - 4)) }
    LazyColumn(state = state, contentPadding = PaddingValues(vertical = 6.px)) {
        itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
            val foc = i == vm.rowIdx
            Plate(foc, Modifier.padding(start = 36.px, end = 36.px, bottom = 4.px).fillMaxWidth().height(96.px), lift = 1.02f) {
                Row(Modifier.fillMaxSize().padding(horizontal = 28.px), verticalAlignment = Alignment.CenterVertically) {
                    Logo(c, 56)
                    Column(Modifier.weight(1f).padding(start = 22.px)) {
                        T("${c.num}  ${c.name}", 26, if (foc) Tok.OnPlate else Tok.T1, FontWeight.Medium)
                        T(c.group, 20, if (foc) Tok.OnPlate2 else Tok.T3, modifier = Modifier.padding(top = 4.px))
                    }
                    if (foc) Value("Показати", true)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- channel context menu (prototype #ctx)

@Composable
fun ChannelMenu(vm: AppViewModel, ch: Channel) {
    vm.cfgVersion
    Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(28.px)
        Column(
            Modifier.width(680.px).shadow(40.dp, shape).background(Tok.GlassHi, shape).border(1.dp, Tok.Hair, shape)
                .padding(start = 22.px, end = 22.px, top = 36.px, bottom = 22.px),
        ) {
            Eyebrow("Канал", Modifier.padding(start = 24.px, end = 24.px, bottom = 8.px))
            Row(Modifier.padding(start = 24.px, end = 24.px, bottom = 22.px), verticalAlignment = Alignment.CenterVertically) {
                Logo(ch, 56)
                T("${ch.num} · ${ch.name}", 32, Tok.T1, FontWeight.Bold, Modifier.padding(start = 18.px))
            }
            CtxItem.entries.forEachIndexed { i, item ->
                val foc = i == vm.ctxIdx
                val (glyph, label) = when (item) {
                    CtxItem.FAV -> if (vm.isFav(ch)) "★" to "Прибрати з обраного" else "☆" to "Додати в обране"
                    CtxItem.HIDE -> if (vm.isHidden(ch)) "◉" to "Показати канал" else "⊘" to "Приховати канал"
                }
                Plate(foc, Modifier.padding(bottom = 4.px).fillMaxWidth().height(72.px), lift = 1.02f) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.px), verticalAlignment = Alignment.CenterVertically) {
                        T(glyph, 26, if (foc) Tok.OnPlate else Tok.T2, modifier = Modifier.width(48.px))
                        T(label, 26, if (foc) Tok.OnPlate else Tok.T1, FontWeight.Medium, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- bookmark context menu («Продовжити перегляд»)

@Composable
fun MarkMenu(vm: AppViewModel, m: Mark) {
    Box(Modifier.fillMaxSize().background(Color(0x80000000)), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(28.px)
        Column(
            Modifier.width(760.px).shadow(40.dp, shape).background(Tok.GlassHi, shape).border(1.dp, Tok.Hair, shape)
                .padding(start = 22.px, end = 22.px, top = 36.px, bottom = 22.px),
        ) {
            Eyebrow("${m.ch.name} · ${dayWord(m.p.start)}, ${hm(m.p.start)}", Modifier.padding(start = 24.px, end = 24.px, bottom = 8.px))
            Row(Modifier.padding(start = 24.px, end = 24.px, bottom = 22.px), verticalAlignment = Alignment.CenterVertically) {
                Logo(m.ch, 56)
                T(m.p.title, 32, Tok.T1, FontWeight.Bold, Modifier.padding(start = 18.px))
            }
            vm.markActions(m).forEachIndexed { i, a ->
                val foc = i == vm.ctxIdx
                val (glyph, label) = when (a) {
                    MarkAction.FROM_START -> "⟲" to "З початку"
                    MarkAction.FORGET -> if (m.watched >= AppViewModel.DONE) "○" to "Позначити непереглянутим" else "✕" to "Прибрати з «Продовжити перегляд»"
                    MarkAction.DONE -> "✓" to "Позначити переглянутим"
                }
                Plate(foc, Modifier.padding(bottom = 4.px).fillMaxWidth().height(72.px), lift = 1.02f) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 24.px), verticalAlignment = Alignment.CenterVertically) {
                        T(glyph, 26, if (foc) Tok.OnPlate else Tok.T2, modifier = Modifier.width(48.px))
                        T(label, 26, if (foc) Tok.OnPlate else Tok.T1, FontWeight.Medium, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- playlists

/** «оновлено сьогодні, 14:05». */
private fun updated(at: Long) = if (at > 0) "оновлено ${dayWord(at).lowercase()}, ${hm(at)}" else "ще не завантажений"

@Composable
private fun PlaylistsSheet(vm: AppViewModel) {
    SheetTitle("Плейлисти", "OK — відкрити, оновити, перейменувати або видалити. В кожного плейлиста своє обране й приховані канали")
    val state = rememberLazyListState()
    LaunchedEffect(vm.rowIdx) { state.scrollToItem(max(0, vm.rowIdx - 4)) }
    LazyColumn(state = state, contentPadding = PaddingValues(vertical = 6.px)) {
        itemsIndexed(vm.playlists, key = { _, p -> p.id }) { i, p ->
            val foc = i == vm.rowIdx
            val active = p.id == vm.playlist?.id
            val loading = vm.loadingPlaylist == p.id
            SheetRow(foc, p.title, if (loading) "Завантажую…" else "${channelsWord(p.count)} · ${updated(p.updatedAt)}") {
                if (active) Value("✓ Відкритий", foc)
            }
        }
        item(key = "add") {
            val foc = vm.rowIdx == vm.playlists.size
            val adding = vm.loadingPlaylist != null && vm.playlists.none { it.id == vm.loadingPlaylist && it.updatedAt > 0 }
            SheetRow(foc, "＋  Додати плейлист", if (adding) "Завантажую новий плейлист…" else "Посилання — з телефону, через QR-код")
        }
    }
}

@Composable
private fun PlaylistSheet(vm: AppViewModel) {
    val p: PlaylistInfo = vm.playlist?.takeIf { it.id == vm.sheetPlaylist?.id } ?: vm.sheetPlaylist ?: return
    val active = p.id == vm.playlist?.id
    SheetTitle(p.title, "${channelsWord(p.count)} · ${updated(p.updatedAt)}" + if (active) " · відкритий" else "")
    PlaylistAction.entries.forEachIndexed { i, a ->
        val foc = vm.rowIdx == i
        when (a) {
            PlaylistAction.USE -> SheetRow(foc, if (active) "Відкритий зараз" else "Відкрити", if (active) null else "Перейти на канали цього плейлиста", dim = active)
            PlaylistAction.REFRESH -> SheetRow(foc, "Оновити", if (vm.loadingPlaylist == p.id) "Завантажую…" else "Завантажити список каналів заново")
            PlaylistAction.RENAME -> SheetRow(foc, "Перейменувати", "Назва з плейлиста: ${p.autoName}")
            PlaylistAction.DELETE -> SheetRow(foc, "Видалити", "Разом з його обраним, прихованими каналами й закладками")
        }
    }
}

// ---------------------------------------------------------------- guide source

private fun sourceLabel(m: GuideMode) = when (m) {
    GuideMode.AUTO -> "Авто"
    GuideMode.PLAYLIST -> "З плейлиста"
    GuideMode.CUSTOM -> "Власне посилання"
}

/** What the guide is doing: progress, the last error, or when it was last updated and from where. */
private fun guideStatus(vm: AppViewModel): String {
    vm.status?.let { return it }
    val from = when (val s = vm.guideSource) {
        GuideSource.Segmented -> "epg.team"
        is GuideSource.Xmltv -> "XMLTV · " + PlaylistInfo.hostOf(s.url) + (vm.xmltvChannels()?.let { " · " + channelsWord(it) } ?: "")
    }
    vm.guideError?.let { return "$from · помилка: $it" }
    val at = vm.guideAt()
    return if (at > 0) "$from · ${updated(at)}" else "$from · ще не завантажена"
}

@Composable
private fun GuideSheet(vm: AppViewModel) {
    vm.guideVersion
    val p = vm.playlist
    SheetTitle("Програма передач", guideStatus(vm))
    GuideRow.entries.forEachIndexed { i, r ->
        val foc = vm.rowIdx == i
        val mode = p?.guideMode ?: GuideMode.AUTO
        when (r) {
            GuideRow.AUTO -> {
                Section("Джерело")
                val tvg = p?.tvgUrl
                SheetRow(foc, "Авто", if (tvg == null || AppViewModel.isEpgTeam(tvg)) "epg.team — швидко, частинами за днями" else "З плейлиста: ${PlaylistInfo.hostOf(tvg)}") {
                    Radio(mode == GuideMode.AUTO, foc)
                }
            }
            GuideRow.PLAYLIST -> SheetRow(
                foc, "З плейлиста",
                p?.tvgUrl?.let { "XMLTV з url-tvg: " + PlaylistInfo.hostOf(it) } ?: "У плейлисті немає посилання на програму",
                dim = p?.tvgUrl == null,
            ) { Radio(mode == GuideMode.PLAYLIST, foc) }
            GuideRow.CUSTOM -> SheetRow(
                foc, "Власне посилання",
                p?.guideUrl?.let { "XMLTV: " + PlaylistInfo.hostOf(it) } ?: "Надіслати посилання з телефону",
            ) { Radio(mode == GuideMode.CUSTOM, foc) }
            GuideRow.REFRESH -> {
                Section("Оновлення")
                SheetRow(foc, "Оновити зараз", null)
            }
        }
    }
}

@Composable
private fun Radio(on: Boolean, foc: Boolean) {
    val c = if (foc) Tok.OnPlate else Tok.T1
    Box(Modifier.padding(start = 24.px).size(30.px).border(3.px, if (on) c else Tok.T3, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(14.px).background(c, CircleShape))
    }
}

// ---------------------------------------------------------------- QR / LAN page

@Composable
private fun LinkSheet(vm: AppViewModel) {
    val (title, sub) = when (vm.linkPurpose) {
        LinkPurpose.ADD_PLAYLIST -> "Додати плейлист" to "Відскануйте QR-код телефоном (той самий Wi-Fi) і вставте посилання на M3U-плейлист"
        LinkPurpose.GUIDE_URL -> "Власна програма передач" to "Відскануйте QR-код телефоном і вставте посилання на XMLTV (.xml або .xml.gz)"
        LinkPurpose.RENAME -> "Перейменувати плейлист" to "Відскануйте QR-код телефоном і введіть нову назву"
    }
    SheetTitle(title, sub)
    val address = vm.setupServer.address
    val qr = remember(address) { address?.let(::qrBitmap) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 64.px)) {
        if (qr != null) {
            Box(Modifier.size(380.px).background(Color.White, RoundedCornerShape(28.px)).padding(22.px)) {
                Image(qr.asImageBitmap(), null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
            T("Або відкрийте $address", 24, Tok.T2, modifier = Modifier.padding(top = 28.px))
        } else T("Немає мережі: підключіть телевізор до Wi-Fi", 26, Tok.Live)
        if (vm.loadingPlaylist != null && vm.linkPurpose == LinkPurpose.ADD_PLAYLIST) {
            T("Завантажую плейлист…", 24, Tok.T1, FontWeight.Medium, Modifier.padding(top = 20.px))
        }
    }
}

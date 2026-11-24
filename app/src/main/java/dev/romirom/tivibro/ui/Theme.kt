package dev.romirom.tivibro.ui

import dev.romirom.tivibro.R
import dev.romirom.tivibro.str
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.romirom.tivibro.data.Channel

/** Onest: a geometric face drawn for Cyrillic, with every letter of the seven languages (Kazakh's Ә Ғ Қ Ң Ұ too). */
val AppFont = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
    Font(R.font.onest_extrabold, FontWeight.ExtraBold),
)

/** Design tokens. Drawn on a 1920×1080 stage; TV dp is half of that. Glass: frosted panes over the video ([glass],
 *  [Frosted]); the accents are the two glows of the logo, a warm amber and a blue. */
object Tok {
    val Ink = Color(0xFF07080B)
    /** The tint over the blurred video in a glass pane; [GlassHi] where text sits densest (dialogs, sheets). */
    val Glass = Color(0x7A0D0F14)
    val GlassHi = Color(0xB40B0D11)
    /** A pane drawn over glass: a lighter sheet with a hairline edge. */
    val Card = Color(0x14FFFFFF)
    val CardHi = Color(0x24FFFFFF)
    val Hair = Color(0x1FFFFFFF)
    val Fill = Color(0x12FFFFFF)
    val Fill2 = Color(0x1FFFFFFF)
    val T1 = Color(0xFFF5F5F7)
    val T2 = Color(0xFFA3A3AB)
    val T3 = Color(0xFF74747C)
    val Plate = Color(0xFFF5F5F7)
    val OnPlate = Color(0xFF0A0B0E)
    val OnPlate2 = Color(0xFF55555C)
    val Live = Color(0xFFFF453A)
    val LiveOnPlate = Color(0xFFD70015)
    val Arch = Color(0xFFFFB340)
    val ArchOnPlate = Color(0xFFB86E00)
    val LogoPlate = Color(0xFFE4E4E6)
    val Accent = Color(0xFFFF9F5A)
    val AccentDeep = Color(0xFFC2560F)
    val Blue = Color(0xFF5B8CFF)
    val Ok = Accent
}

/** A card's hairline: brighter at the top, where the light falls. */
val CardEdge = Brush.verticalGradient(0f to Color(0x38FFFFFF), 1f to Color(0x0FFFFFFF))

/** Prototype pixels → dp / sp. The UI scale (Settings → «Масштаб інтерфейсу») is applied once, at the root of [App],
 *  through a scaled LocalDensity: these stay plain, and every overlay grows and shrinks together. */
val Int.px: Dp get() = (this / 2f).dp
val Int.tx: TextUnit get() = (this / 2f).sp

/** The stage the overlays are laid out on, in prototype pixels: 1920×1080 at 100 %, 1536×864 at 125 %, 1280×720 at
 *  150 %. Fixed-width columns size themselves from it so that nothing is pushed off screen at a large UI scale. */
val LocalStage = staticCompositionLocalOf { IntSize(1920, 1080) }

/** Caps a list's height at a whole number of [row]-tall rows (prototype px, plus [extra] for its content padding), so
 *  that a larger UI scale shows fewer rows instead of a row cut in half at the bottom. */
fun Modifier.wholeRows(row: Int, extra: Int = 0): Modifier = layout { m, c ->
    val r = row.px.toPx() + 1f // + 1: each row's parts round to whole pixels on their own
    val e = extra.px.roundToPx()
    val h = if (c.hasBoundedHeight) max(c.minHeight, e + (((c.maxHeight - e) / r).toInt() * r).toInt()) else c.maxHeight
    val p = m.measure(c.copy(maxHeight = h))
    layout(p.width, p.height) { p.place(0, 0) }
}

/** Fades a scrolling list out over [edge] prototype px at the top and bottom, so a row the list is scrolled past reads
 *  as "more this way" rather than as a row cut in half. */
fun Modifier.fadeEdges(edge: Int = 28): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val f = (edge.px.toPx() / size.height).coerceIn(0f, .5f)
        drawRect(Brush.verticalGradient(0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }

/** Scrolls so row [i] sits [lead] rows below the top, or fewer when the viewport holds fewer rows (a larger UI scale),
 *  then, once laid out, makes sure the whole row is on screen (rows of different heights, a scale that just changed). */
suspend fun LazyListState.scrollNear(i: Int, lead: Int) {
    fun fit(): Int? {
        val info = layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty()) return null
        val avg = vis.sumOf { it.size } / vis.size
        return (info.viewportEndOffset - info.viewportStartOffset) / max(1, avg)
    }
    scrollToItem(max(0, i - (fit()?.let { min(lead, max(0, it - 2)) } ?: lead)))
    repeat(2) {
        withFrameNanos { }
        val info = layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == i }
        when {
            row == null -> scrollToItem(i)
            row.offset < info.viewportStartOffset -> scrollBy((row.offset - info.viewportStartOffset).toFloat())
            row.offset + row.size > info.viewportEndOffset -> {
                // drop whole rows off the top until it fits, so no row is left cut in half at the top edge
                val need = row.offset + row.size - info.viewportEndOffset
                val first = info.visibleItemsInfo.firstOrNull { it.offset + it.size - info.viewportStartOffset >= need }
                scrollToItem(min(i, (first?.index ?: i) + 1))
            }
            else -> return
        }
    }
}

@Composable
fun T(
    text: String,
    size: Int,
    color: Color = Tok.T1,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    marquee: Boolean = false,
) = BasicText(
    text,
    // [marquee]: a line that doesn't fit runs past like a ticker (only one that doesn't fit, and only after a pause, so
    // a quick run down a list doesn't set every row moving); it only moves the drawn line, nothing is laid out again
    if (marquee) modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200, repeatDelayMillis = 1800, spacing = MarqueeSpacing(64.px), velocity = 42.dp)
    else modifier,
    style = TextStyle(fontFamily = AppFont, color = color, fontSize = size.tx, fontWeight = weight, letterSpacing = letterSpacing, lineHeight = (size * 1.35f).toInt().tx),
    maxLines = if (marquee) 1 else maxLines,
    softWrap = !marquee,
    overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
)

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) =
    T(text.uppercase(), 20, Tok.T3, FontWeight.SemiBold, modifier, letterSpacing = 1.4.sp)

/** The lit plate: the only bright thing on screen. [quiet] = selected but not focused (a layer underneath). */
@Composable
fun Plate(
    focused: Boolean,
    modifier: Modifier = Modifier,
    quiet: Boolean = false,
    radius: Int = 18,
    lift: Float = 1.035f,
    content: @Composable BoxScope.() -> Unit,
) {
    val s by animateFloatAsState(if (focused) lift else 1f, tween(140), label = "lift")
    val shape = RoundedCornerShape(radius.px)
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .then(if (focused) Modifier.shadow(18.dp, shape, ambientColor = Color.White, spotColor = Color.White) else Modifier)
            .background(if (focused) Tok.Plate else if (quiet) Tok.CardHi else Color.Transparent, shape)
            .then(if (quiet) Modifier.border(1.dp, CardEdge, shape) else Modifier),
        content = content,
    )
}

@Composable
fun Logo(ch: Channel, size: Int = 64) {
    Box(
        Modifier.size(size.px).clip(RoundedCornerShape((size / 5).px)).background(Tok.LogoPlate),
        contentAlignment = Alignment.Center,
    ) {
        if (ch.logo != null) AsyncImage(ch.logo, null, Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Fit)
        else T(ch.name.take(3), (size * 0.28f).toInt(), Tok.OnPlate, FontWeight.Bold)
    }
}

@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier, color: Color = Tok.T1, track: Color = Color(0x24FFFFFF), height: Int = 4) {
    Box(modifier.height(height.px).clip(RoundedCornerShape(height.px)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
fun LivePill(onPlate: Boolean = false) {
    val c = if (onPlate) Tok.LiveOnPlate else Tok.Live
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.px).background(c, CircleShape))
        T(str(R.string.live), 19, c, FontWeight.Bold, Modifier.padding(start = 8.px), letterSpacing = 0.6.sp)
    }
}

@Composable
fun Pill(text: String, color: Color = Tok.T1, bg: Color = Tok.Fill2, outline: Boolean = false) {
    Box(
        Modifier
            .height(34.px)
            .then(if (outline) Modifier.border(1.5.dp / 2, Tok.Hair, RoundedCornerShape(9.px)) else Modifier.background(bg, RoundedCornerShape(9.px)))
            .padding(horizontal = 12.px),
        contentAlignment = Alignment.Center,
    ) { T(text, 20, color, FontWeight.SemiBold) }
}

@Composable
fun KeyCap(text: String) {
    Box(
        Modifier.height(32.px).border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(8.px)).padding(horizontal = 9.px),
        contentAlignment = Alignment.Center,
    ) { T(text, 17, Tok.T1, FontWeight.SemiBold) }
}

/** A switch (prototype `.sw`). [onPlate] = inside the lit row, where the off track darkens instead of lightening. */
@Composable
fun Switch(on: Boolean, onPlate: Boolean = false, modifier: Modifier = Modifier) {
    val x by animateDpAsState(if (on) 29.px else 3.px, tween(180), label = "sw")
    Box(
        modifier.width(62.px).height(36.px)
            .background(if (on) Tok.Ok else if (onPlate) Color(0x24000000) else Color(0x2EFFFFFF), RoundedCornerShape(18.px)),
    ) {
        Box(Modifier.offset(x = x, y = 3.px).size(30.px).shadow(3.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** A small light pane in a pill: the clock, counters. */
@Composable
fun GlassPill(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.background(Tok.Card, shape).border(1.dp, CardEdge, shape).padding(horizontal = 24.px, vertical = 8.px),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

package io.github.romirom11.tivibro.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.romirom11.tivibro.data.Channel

/** Design tokens from prototype/style.css. The prototype is drawn on a 1920×1080 stage; TV dp is half of that. */
object Tok {
    val Ink = Color(0xFF0A0B0E)
    val Glass = Color(0xD0141519)
    val GlassHi = Color(0xEB101115)
    val Hair = Color(0x17FFFFFF)
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
    val Ok = Color(0xFF30D158)
}

/** Prototype pixels → dp / sp. */
val Int.px: Dp get() = (this / 2f).dp
val Int.tx: TextUnit get() = (this / 2f).sp

@Composable
fun T(
    text: String,
    size: Int,
    color: Color = Tok.T1,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    letterSpacing: TextUnit = TextUnit.Unspecified,
) = BasicText(
    text,
    modifier,
    style = TextStyle(color = color, fontSize = size.tx, fontWeight = weight, letterSpacing = letterSpacing, lineHeight = (size * 1.35f).toInt().tx),
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
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
            .then(if (focused) Modifier.shadow(12.dp, shape) else Modifier)
            .background(if (focused) Tok.Plate else if (quiet) Tok.Fill2 else Color.Transparent, shape),
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
        T("НАЖИВО", 19, c, FontWeight.Bold, Modifier.padding(start = 8.px), letterSpacing = 0.6.sp)
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

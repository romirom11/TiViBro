package dev.romirom.tivibro.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** The dark stage with the logo's two glows: amber low on the left, blue high on the right. Static: drawn once. */
fun Modifier.ambient(): Modifier = background(Tok.Ink).drawBehind {
    val m = maxOf(size.width, size.height)
    drawRect(Brush.radialGradient(listOf(Color(0x66C2560F), Color(0x22C2560F), Color.Transparent), Offset(0f, size.height * .92f), m * .55f))
    drawRect(Brush.radialGradient(listOf(Color(0x5C1E5BD8), Color(0x1E1E5BD8), Color.Transparent), Offset(size.width, 0f), m * .5f))
}

/** Where 0..1 of the whole intro falls inside [a]..[b], eased out. */
private fun phase(t: Float, a: Float, b: Float): Float {
    val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x) * (1f - x)
}

/**
 * The launch: the logo builds itself (the plate comes up, the rewind arrow runs round, play drops in), the name
 * follows, and the whole stage fades into the picture. ~1.5 s, drawn on one clock read only by draw and layer
 * lambdas (no recomposition per frame); the channels load and the stream starts underneath meanwhile.
 */
@Composable
fun Intro(onDone: () -> Unit) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        clock.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
        onDone()
    }
    val t = { clock.value }
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            val out = phase(t(), .78f, 1f)
            alpha = 1f - out
            scaleX = 1f + out * .06f; scaleY = scaleX
        }.ambient(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Canvas(Modifier.size(300.px).graphicsLayer {
                val k = phase(t(), 0f, .22f)
                alpha = k
                scaleX = .82f + .18f * k; scaleY = scaleX
            }) { drawLogo(t()) }
            T(
                "TiViBro", 64, Tok.T1, FontWeight.ExtraBold,
                Modifier.padding(top = 44.px).graphicsLayer {
                    val k = phase(t(), .42f, .66f)
                    alpha = k
                    translationY = (1f - k) * 24.px.toPx()
                },
                letterSpacing = 1.sp,
            )
        }
    }
}

private const val TOTAL_MS = 1500

/** The app icon, drawn: a white rounded plate with a soft glow, a rewind arrow round a play triangle. */
private fun DrawScope.drawLogo(t: Float) {
    val s = size.minDimension
    val r = CornerRadius(s * .23f)
    // glow: a soft light behind the plate
    val g = phase(t, .1f, .5f)
    drawCircle(
        Brush.radialGradient(0f to Color.White.copy(alpha = .22f * g), .55f to Color.White.copy(alpha = .07f * g), 1f to Color.Transparent, center = center, radius = s * .95f),
        radius = s * .95f,
    )
    drawRoundRect(Color(0xFFF7F7F8), cornerRadius = r)
    val ink = Color(0xFF0B0B0D)
    val c = Offset(s * .5f, s * .495f)
    val rad = s * .335f
    val w = s * .066f
    // the arrow runs round from the left, down, up the right and over the top, ending upper left
    val start = 189f
    val sweep = -324f * phase(t, .14f, .6f)
    if (sweep < -1f) {
        drawArc(ink, start, sweep, false, Offset(c.x - rad, c.y - rad), Size(2 * rad, 2 * rad), style = Stroke(w, cap = StrokeCap.Round))
        // its head, along the way it travels
        val a = Math.toRadians((start + sweep).toDouble())
        val tip = Offset(c.x + rad * cos(a).toFloat(), c.y + rad * sin(a).toFloat())
        val dir = Offset(sin(a).toFloat(), -cos(a).toFloat()) // decreasing angle
        val nrm = Offset(-dir.y, dir.x)
        val h = s * .13f * phase(t, .3f, .6f)
        val head = Path().apply {
            moveTo(tip.x + dir.x * h * .55f, tip.y + dir.y * h * .55f)
            lineTo(tip.x - dir.x * h * .35f + nrm.x * h * .62f, tip.y - dir.y * h * .35f + nrm.y * h * .62f)
            lineTo(tip.x - dir.x * h * .35f - nrm.x * h * .62f, tip.y - dir.y * h * .35f - nrm.y * h * .62f)
            close()
        }
        drawPath(head, ink)
        drawPath(head, ink, style = Stroke(s * .02f, join = StrokeJoin.Round))
    }
    // play, dropping into place
    val p = phase(t, .4f, .62f)
    if (p > 0f) {
        val k = .6f + .4f * p
        val m = Offset(s * .51f, s * .5f)
        fun pt(x: Float, y: Float) = Offset(m.x + (x * s - m.x) * k, m.y + (y * s - m.y) * k)
        val tri = Path().apply {
            val a = pt(.405f, .355f); val b = pt(.655f, .497f); val d = pt(.405f, .645f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(d.x, d.y); close()
        }
        drawPath(tri, ink.copy(alpha = p))
        drawPath(tri, ink.copy(alpha = p), style = Stroke(s * .035f, join = StrokeJoin.Round))
    }
}

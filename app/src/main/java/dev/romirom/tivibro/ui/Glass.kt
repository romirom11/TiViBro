package dev.romirom.tivibro.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/**
 * Frosted glass over the video without blurring it live. A real backdrop blur would need the player on a TextureView
 * with a RenderEffect, which costs every frame on a slow TV. Instead, while anything made of glass is on screen, the
 * GPU scales the frame on the SurfaceView down to [W]×[H] (PixelCopy) every [EVERY] ms, it is blurred on the CPU (a few
 * thousand pixels: well under a millisecond) and each glass surface draws its part of it, stretched, under a tint.
 * The picture under the glass follows the video at that pace; playback itself is never touched.
 */
class Backdrop {
    /** The blurred frame, or null before the first copy (or where PixelCopy is missing: Android 6). */
    var frame by mutableStateOf<ImageBitmap?>(null); private set
    /** How bright the frame is, 0..1: glass over a bright scene gets a darker tint, so its text stays readable. */
    var bright by mutableStateOf(0f); private set
    /** Where the video surface is, in window pixels: the frame is drawn into this rectangle. */
    var rect by mutableStateOf(Rect.Zero); private set
    private var users by mutableIntStateOf(0)
    /** Settings → «Ефекти скла». Off: no copies at all, and the panes are plain dark glass. */
    var enabled by mutableStateOf(true)
    private var view: SurfaceView? = null
    private var busy = false
    private val thread by lazy { HandlerThread("glass").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    fun attach(v: SurfaceView?) { view = v }
    fun hold() { users++ }
    fun release() { users-- }

    /** Copies and blurs a frame at once when glass comes on screen, then every [EVERY] ms while it stays; asleep
     *  (no timer at all) while there is none. */
    suspend fun run() {
        snapshotFlow { users > 0 && enabled }.collectLatest { shown ->
            if (!enabled) frame = null
            while (shown) {
                capture()
                delay(EVERY)
            }
        }
    }

    private fun capture() {
        if (Build.VERSION.SDK_INT < 24 || busy) return
        val v = view ?: return
        if (!v.holder.surface.isValid || v.width == 0 || v.height == 0) return
        val loc = IntArray(2).also(v::getLocationInWindow)
        val r = Rect(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + v.width).toFloat(), (loc[1] + v.height).toFloat())
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        busy = true
        runCatching {
            PixelCopy.request(v, bmp, { res ->
                if (res == PixelCopy.SUCCESS) {
                    val l = blur(bmp)
                    val img = bmp.asImageBitmap()
                    v.post { frame = img; rect = r; bright = l; busy = false }
                } else v.post { busy = false }
            }, handler)
        }.onFailure { busy = false }
    }

    /** Two passes of a 7-wide box blur each way (≈ a gaussian), in place, on a [W]×[H] bitmap; then the colour is
     *  lifted the way frosted glass does it (more saturated), so the pane takes on the picture's colours. */
    private fun blur(b: Bitmap): Float {
        val px = IntArray(W * H)
        b.getPixels(px, 0, W, 0, 0, W, H)
        val tmp = IntArray(W * H)
        repeat(2) {
            boxBlur(px, tmp, W, H, R, horizontal = true)
            boxBlur(tmp, px, W, H, R, horizontal = false)
        }
        var sum = 0L
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
            val l = (r * 77 + g * 150 + bl * 29) shr 8
            sum += l
            fun sat(c: Int) = (l + (c - l) * SATURATE / 100).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (sat(r) shl 16) or (sat(g) shl 8) or sat(bl)
        }
        b.setPixels(px, 0, W, 0, 0, W, H)
        return sum / (px.size * 255f)
    }

    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val n = 2 * r + 1
        for (l in 0 until lines) {
            fun at(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) src[l * w + c] else src[c * w + l] }
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val p = at(i); sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
            for (i in 0 until len) {
                val o = if (horizontal) l * w + i else i * w + l
                dst[o] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val a = at(i - r); val z = at(i + r + 1)
                sr += ((z shr 16) and 255) - ((a shr 16) and 255)
                sg += ((z shr 8) and 255) - ((a shr 8) and 255)
                sb += (z and 255) - (a and 255)
            }
        }
    }

    fun close() { if (Build.VERSION.SDK_INT >= 18) thread.quitSafely() }

    companion object {
        const val W = 96
        const val H = 54
        private const val R = 3
        private const val SATURATE = 135 // percent
        const val EVERY = 2000L
    }
}

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Keeps [b] copying frames for as long as the app is composed. */
@Composable
fun RunBackdrop(b: Backdrop) {
    LaunchedEffect(b) { b.run() }
    DisposableEffect(b) { onDispose { b.close() } }
}

/**
 * A glass surface: the blurred video behind it, a [tint] over that, a light catching the top-left, and a hairline
 * edge brighter at the top. Draws behind the content only, so focused children can still grow past its edge.
 */
fun Modifier.glass(shape: Shape, tint: Color = Tok.Glass, edge: Boolean = true): Modifier = composed {
    val b = LocalBackdrop.current
    DisposableEffect(b) { b?.hold(); onDispose { b?.release() } }
    var at by remember { mutableStateOf(Offset.Zero) }
    val path = remember { Path() }
    this
        .onPlaced { at = it.positionInWindow() }
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            path.reset()
            path.addOutline(outline)
            clipPath(path) {
                val f = b?.frame
                val r = b?.rect ?: Rect.Zero
                if (!drawFrame(b, at)) drawRect(NO_FRAME)
                drawRect(tint)
                b?.let { if (it.frame != null) drawRect(Color.Black.copy(alpha = it.bright * BRIGHT_SHADE)) }
                drawRect(Brush.linearGradient(0f to Color(0x1AFFFFFF), .45f to Color(0x05FFFFFF), 1f to Color.Transparent, end = Offset(size.width * .6f, size.height)))
            }
            if (edge) drawPath(
                path, Brush.verticalGradient(0f to Color(0x40FFFFFF), 1f to Color(0x12FFFFFF)), style = Stroke(1.dp.toPx()),
            )
        }
}

/** Extra shade over a frame as bright as it gets (white): text on glass reads the same over a sunny field as at night. */
private const val BRIGHT_SHADE = .45f

/** No frame to frost with (Android 6, the first moments, a protected stream): a plain dark pane instead. */
private val NO_FRAME = Color(0x8C07080B)

/** Draws the blurred frame where the video is, seen from an element at [at] (window px). False when there is none. */
private fun DrawScope.drawFrame(b: Backdrop?, at: Offset): Boolean {
    val f = b?.frame ?: return false
    val r = b.rect
    if (r.width <= 0f) return false
    translate(r.left - at.x, r.top - at.y) {
        drawImage(f, IntOffset.Zero, IntSize(f.width, f.height), IntOffset.Zero, IntSize(r.width.roundToInt(), r.height.roundToInt()), filterQuality = FilterQuality.Low)
    }
    return true
}

/**
 * A full-screen frosted veil for the overlays that cover the picture (channel list, Home, search, menu): the blurred
 * video under [tint], kept where [mask] is opaque and faded out where it is clear, so the live picture shows through
 * the room the overlay leaves. The cards on it are light panes ([Tok.Card]), not glass of their own: one blurred
 * image per screen, however many cards.
 */
@Composable
fun Frosted(tint: Brush, mask: Brush? = null, modifier: Modifier = Modifier) {
    val b = LocalBackdrop.current
    DisposableEffect(b) { b?.hold(); onDispose { b?.release() } }
    var at by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.fillMaxSize()
            .onPlaced { at = it.positionInWindow() }
            .graphicsLayer { if (mask != null) compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                if (!drawFrame(b, at)) drawRect(NO_FRAME)
                drawRect(tint)
                b?.let { if (it.frame != null) drawRect(Color.Black.copy(alpha = it.bright * BRIGHT_SHADE)) }
                if (mask != null) drawRect(mask, blendMode = BlendMode.DstIn)
            },
    )
}

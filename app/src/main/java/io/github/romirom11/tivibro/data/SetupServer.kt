package io.github.romirom11.tivibro.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.SecureRandom
import io.github.romirom11.tivibro.L10n
import io.github.romirom11.tivibro.R
import io.github.romirom11.tivibro.str
import kotlin.concurrent.thread

/**
 * Tiny LAN page for pasting a link (or typing a name) on a phone instead of with the remote: the playlist on first run
 * and when adding one, an XMLTV link, a playlist's new name. Runs only while a screen that shows its QR code is open;
 * [page] says what it asks for, [onSubmit] gets the answer (on the server thread). The address carries a random key
 * made on every [start]: only someone who saw the QR code on the TV can open the page or send an answer.
 */
class SetupServer(@Volatile var onSubmit: (String) -> Unit) {
    /** What the page asks for. [link]: the answer must be an http(s) URL; otherwise any non-blank text. */
    class Page(val title: String, val hint: String, val button: String, val link: Boolean = true, val value: String = "")

    /** What the page asks for; null: the playlist link, in the current language. */
    @Volatile var page: Page? = null
    private var socket: ServerSocket? = null
    @Volatile private var key = ""

    val address: String? get() = socket?.let { s -> localIp()?.let { "http://$it:${s.localPort}/$key" } }

    fun start() {
        if (socket != null) return
        val s = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
        key = newKey()
        socket = s
        thread(isDaemon = true, name = "setup-server") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                runCatching { c.use { handle(it.getInputStream().bufferedReader(), it.getOutputStream()) } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(r: BufferedReader, out: java.io.OutputStream) {
        val request = r.readLine() ?: return
        val path = request.split(' ').getOrNull(1)?.substringBefore('?')
        if (key.isEmpty() || path != "/$key") {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        var length = 0
        while (true) {
            val h = r.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        var message = ""
        if (request.startsWith("POST")) {
            val buf = CharArray(length)
            var n = 0
            if (length > 64 * 1024) return
            while (n < length) { val k = r.read(buf, n, length - n); if (k < 0) break; n += k }
            val answer = String(buf, 0, n).split('&').firstOrNull { it.startsWith("url=") }
                ?.let { URLDecoder.decode(it.substring(4), "UTF-8").trim() }
            val pg = page ?: playlistPage()
            message = when {
                answer.isNullOrBlank() -> "<p class=err>${esc(str(R.string.page_empty))}</p>"
                pg.link && !answer.startsWith("http") -> "<p class=err>${esc(str(R.string.page_not_link))}</p>"
                else -> { onSubmit(answer.take(2000)); "<p class=ok>${esc(str(R.string.page_done))}</p>" }
            }
        }
        val pg = page ?: playlistPage()
        val field = if (pg.link) "<textarea name=url placeholder=\"https://…\" autofocus>${esc(pg.value)}</textarea>"
        else "<input name=url value=\"${esc(pg.value)}\" autofocus>"
        val html = PAGE.replace("{lang}", L10n.locale.language).replace("{title}", esc(pg.title)).replace("{hint}", esc(pg.hint)).replace("{button}", esc(pg.button))
            .replace("{field}", field).replace("{msg}", message).toByteArray()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(html)
        out.flush()
    }

    companion object {
        const val PORT = 8765

        /** The playlist link: first run and «Додати плейлист». */
        fun playlistPage() = Page(str(R.string.page_playlist_title), str(R.string.page_playlist_hint), str(R.string.page_send))

        private fun newKey(): String {
            val b = ByteArray(6)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }?.hostAddress

        private val PAGE = """
            <!doctype html><html lang={lang}><meta name=viewport content="width=device-width,initial-scale=1">
            <title>TiViBro</title>
            <style>body{font:17px -apple-system,Roboto,sans-serif;background:#0a0b0e;color:#f5f5f7;margin:0;padding:28px}
            h1{font-size:24px}p{color:#a3a3ab;line-height:1.5}textarea,input{width:100%;box-sizing:border-box;border-radius:14px;border:0;padding:14px;font:15px monospace;background:#1c1d22;color:#fff}textarea{height:120px}input{font:17px -apple-system,Roboto,sans-serif}
            button{margin-top:14px;width:100%;height:52px;border:0;border-radius:14px;background:#f5f5f7;color:#0a0b0e;font-size:17px;font-weight:600}
            .ok{color:#30d158}.err{color:#ff453a}</style>
            <h1>{title}</h1>
            <p>{hint}</p>
            <form method=post>{field}<button>{button}</button></form>
            {msg}
        """.trimIndent()
    }
}

package dev.romirom.tivibro.data

import java.net.URLDecoder
import java.security.SecureRandom
import dev.romirom.tivibro.L10n
import dev.romirom.tivibro.R
import dev.romirom.tivibro.str
import dev.romirom.tivibro.remote.Http
import dev.romirom.tivibro.remote.Remote

/**
 * Tiny LAN page for pasting a link (or typing a name) on a phone instead of with the remote: the playlist on first run
 * and when adding one, an XMLTV link, a playlist's new name. Served only while a screen that shows its QR code is open;
 * [page] says what it asks for, [onSubmit] gets the answer (on the server thread). The address carries a random key
 * made on every [start]: only someone who saw the QR code on the TV can open the page or send an answer.
 *
 * A route of the app's one LAN server ([Remote.http], port 8765), which also serves the phone remote: [start] and
 * [stop] only make the page's key valid or not.
 */
class SetupServer(@Volatile var onSubmit: (String) -> Unit) {
    /** What the page asks for. [link]: the answer must be an http(s) URL; otherwise any non-blank text. */
    class Page(val title: String, val hint: String, val button: String, val link: Boolean = true, val value: String = "")

    /** What the page asks for; null: the playlist link, in the current language. */
    @Volatile var page: Page? = null
    @Volatile private var key = ""

    val address: String? get() = key.takeIf { it.isNotEmpty() }?.let { k -> Remote.base()?.let { "$it/$k" } }

    fun start() {
        if (key.isNotEmpty()) return
        key = newKey()
        Remote.setup = this
    }

    fun stop() {
        key = ""
        if (Remote.setup === this) Remote.setup = null
    }

    /** The page if [r] is for it (its path is the current key), else null. */
    fun handle(r: Http.Request): Http.Response? {
        val k = key
        if (k.isEmpty() || r.path != "/$k") return null
        var message = ""
        if (r.method == "POST") {
            val answer = r.text.split('&').firstOrNull { it.startsWith("url=") }
                ?.let { runCatching { URLDecoder.decode(it.substring(4), "UTF-8").trim() }.getOrNull() }
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
        return Http.Response.html(
            PAGE.replace("{lang}", L10n.locale.language).replace("{title}", esc(pg.title)).replace("{hint}", esc(pg.hint)).replace("{button}", esc(pg.button))
                .replace("{field}", field).replace("{msg}", message),
        )
    }

    companion object {
        /** The playlist link: first run and «Додати плейлист». */
        fun playlistPage() = Page(str(R.string.page_playlist_title), str(R.string.page_playlist_hint), str(R.string.page_send))

        private fun newKey(): String {
            val b = ByteArray(6)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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

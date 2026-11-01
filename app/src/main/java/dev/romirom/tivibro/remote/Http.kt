package dev.romirom.tivibro.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * The app's one LAN HTTP server, on [PORT] for as long as the process lives: the QR setup page (data.SetupServer),
 * the phone remote and the MCP endpoint are routes of it, so there is one port to know and nothing to start or stop
 * per screen. Raw sockets on purpose: a few hundred lines of HTTP/1.1 subset beat a server library in an APK this size.
 *
 * One thread per connection, one request per connection (`Connection: close`): the clients are a phone page and an MCP
 * client on the same Wi-Fi. An idle connection is held for [idleMs] (browsers open spare preconnections and may send a
 * request on one many seconds later); once bytes arrive, the client always gets an answer, 400/408/500 included.
 * A TLS ClientHello (a browser trying https:// first) is refused at once with a TLS alert, so the browser falls back
 * to http:// (or shows its "not secure" page) right away instead of waiting on a server that reads it as a request.
 *
 * Every connection ends in one logcat line under [LOG] (`adb logcat -s tivibro-http`): who, what came first (an HTTP
 * request, TLS, nothing), the request line with the token masked ([mask]), the status, and how the connection ended.
 * A browser that says it can't reach the page while other clients can is diagnosed from those lines.
 */
class Http(
    private val route: (Request) -> Response,
    /** How long a connection may sit without a request: well past how long browsers keep a spare one (Chrome for
     *  Android was seen keeping an unused one for 277 s, longer than the 120 s this used to be). */
    private val idleMs: Int = 15 * 60_000,
    /** How long any one read may take once the request has started. */
    private val requestMs: Int = 15_000,
    /** Hides secrets (the remote's token) in a request target before it is logged. */
    private val mask: (String) -> String = { it },
) {
    class Request(
        val method: String,
        /** Decoded path, without the query. */
        val path: String,
        val query: Map<String, String>,
        /** Header names in lower case. */
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String) = headers[name.lowercase()]
        val text get() = String(body, Charsets.UTF_8)
    }

    class Response(val code: Int, val type: String? = null, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap()) {
        companion object {
            fun html(s: String) = Response(200, "text/html; charset=utf-8", s.toByteArray())
            // org.json escapes every "/" as "\\/": valid, but noise in what a language model reads
            fun json(s: String, code: Int = 200) = Response(code, "application/json; charset=utf-8", s.replace("\\/", "/").toByteArray())
            fun empty(code: Int, headers: Map<String, String> = emptyMap()) = Response(code, headers = headers)
        }
    }

    @Volatile private var socket: ServerSocket? = null
    private val open = java.util.concurrent.atomic.AtomicInteger()

    /** The port actually bound: [PORT], or a free one if something else holds it. */
    val port: Int get() = socket?.localPort ?: 0

    fun start() {
        if (socket != null) return
        val s = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
        socket = s
        thread(isDaemon = true, name = "lan-http") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                // idle connections are held long, so their number is capped: a flood can't pile up threads without end
                if (open.incrementAndGet() > MAX_OPEN) {
                    open.decrementAndGet()
                    runCatching { c.close() }
                    runCatching { android.util.Log.w(LOG, "over $MAX_OPEN open connections, refused one") }
                    continue
                }
                thread(isDaemon = true, name = "lan-conn") { try { runCatching { c.use { serve(it) } } } finally { open.decrementAndGet() } }
            }
        }
    }

    private fun serve(c: Socket) {
        val t0 = System.nanoTime()
        val who = (c.remoteSocketAddress as? java.net.InetSocketAddress)?.let { "${it.address?.hostAddress}:${it.port}" } ?: "?"
        fun log(what: String, quiet: Boolean = false) {
            val line = "$who $what (${(System.nanoTime() - t0) / 1_000_000} ms)"
            runCatching { if (quiet) android.util.Log.d(LOG, line) else android.util.Log.i(LOG, line) }
        }
        val inp = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        // An idle connection may wait long for its first byte: Chrome preconnects spare sockets (the omnibox does while
        // the address is typed) and was seen using one a minute later. Closing one first races with Chrome sending the
        // request into it, which it sees as an empty response and retries on another socket.
        c.soTimeout = idleMs
        inp.mark(1)
        val first = try { inp.read() } catch (_: SocketTimeoutException) { return log("idle, closed by us after ${idleMs / 1000} s") }
        catch (e: IOException) { return log("idle, ${e.javaClass.simpleName}: ${e.message}") }
        if (first < 0) return log("idle, closed by the client without a request", quiet = true)
        inp.reset()
        // Once a request has started, it must arrive briskly; from here on every outcome gets an answer
        c.soTimeout = requestMs
        if (first == TLS_HANDSHAKE) {
            // https:// to this plain port: a TLS "handshake failure" alert ends the handshake at once, where a plain-text
            // 400 would only be read as one after the ClientHello happened to contain a line break
            runCatching { out.write(TLS_ALERT); out.flush() }
            return log("TLS ClientHello, refused with a TLS alert; ${finish(c, inp)}")
        }
        var line = "?"
        val res = try {
            val req = parse(inp) { line = mask(it.take(200)) } ?: return log("request cut off: $line")
            runCatching { route(req) }.getOrElse {
                android.util.Log.w("tivibro", "http: ${req.method} failed", it)
                Response.json("""{"error":"internal error"}""", 500)
            }
        } catch (e: Bad) { Response.empty(e.code) } catch (_: SocketTimeoutException) { Response.empty(408) }
        catch (e: IOException) { return log("$line: ${e.javaClass.simpleName} while reading: ${e.message}") }
        val sent = runCatching { write(out, res) }.exceptionOrNull()
        if (sent != null) return log("$line -> ${res.code}, not sent: ${sent.javaClass.simpleName}: ${sent.message}")
        val printable = if (first in 0x20..0x7E) "" else " (first byte 0x%02x)".format(first)
        log("$line -> ${res.code} ${res.body.size} B$printable; ${finish(c, inp)}", quiet = res.code in 200..299 || res.code == 404)
    }

    /**
     * Unread request bytes (a body we refused, pipelined junk, the rest of a ClientHello) would make close() send RST,
     * which may destroy the response before the client reads it: half-close, then drain briefly until the client closes
     * its side. Says how the connection ended, for the log.
     */
    private fun finish(c: Socket, inp: InputStream): String = try {
        c.shutdownOutput()
        c.soTimeout = 2_000
        val buf = ByteArray(4096)
        var left = 1 shl 20
        var end = "closed by the client"
        while (true) {
            val k = inp.read(buf)
            if (k < 0) break
            left -= k
            if (left <= 0) { end = "client kept sending, closed by us"; break }
        }
        end
    } catch (_: SocketTimeoutException) { "client did not close within 2 s, closed by us" }
    catch (e: IOException) { "ended by ${e.javaClass.simpleName}: ${e.message}" }

    private class Bad(val code: Int) : Exception(null, null, false, false)

    /** The request, null when the client went away mid-request; throws [Bad] for one we refuse. */
    private fun parse(inp: InputStream, seen: (String) -> Unit = {}): Request? {
        val line = readLine(inp) ?: return null
        seen(line)
        val parts = line.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) throw Bad(400)
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(inp) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0 && headers.size < 64) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        if (headers["transfer-encoding"] != null) throw Bad(411)
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw Bad(400) } ?: 0
        if (length !in 0..MAX_BODY) throw Bad(413)
        val body = ByteArray(length)
        var n = 0
        while (n < length) { val k = inp.read(body, n, length - n); if (k < 0) return null; n += k }
        val target = parts[1]
        val path = runCatching { URLDecoder.decode(target.substringBefore('?').replace("+", "%2B"), "UTF-8") }.getOrDefault(target)
        return Request(parts[0].uppercase(), path, parseQuery(target.substringAfter('?', "")), headers, body)
    }

    private fun write(out: OutputStream, r: Response) {
        val head = StringBuilder("HTTP/1.1 ${r.code} ${reason(r.code)}\r\n")
        if (r.type != null) head.append("Content-Type: ${r.type}\r\n")
        for ((k, v) in r.headers) head.append("$k: $v\r\n")
        head.append("Content-Length: ${r.body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        out.write(r.body)
        out.flush()
    }

    companion object {
        const val PORT = 8765
        private const val MAX_OPEN = 64
        /** The logcat tag of the per-connection lines. */
        const val LOG = "tivibro-http"
        /** The first byte of a TLS handshake record. HTTP methods start with a letter. */
        private const val TLS_HANDSHAKE = 0x16
        /** A TLS 1.0-framed fatal alert, handshake_failure (40): every TLS client reads it, whatever version it offered. */
        private val TLS_ALERT = byteArrayOf(0x15, 0x03, 0x01, 0x00, 0x02, 0x02, 0x28)
        private const val MAX_BODY = 64 * 1024

        /** A request line or header, ISO-8859-1, without CRLF; null at EOF. Capped so a garbage stream can't eat memory. */
        private fun readLine(inp: InputStream): String? {
            val b = ByteArrayOutputStream(128)
            while (true) {
                val c = inp.read()
                if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
                if (c == '\n'.code) break
                if (c != '\r'.code && b.size() < 8192) b.write(c)
            }
            return b.toString("ISO-8859-1")
        }

        fun parseQuery(q: String): Map<String, String> = if (q.isEmpty()) emptyMap() else q.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=')
            if (k.isEmpty()) null else runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") }.getOrNull()
        }.toMap()

        private fun reason(code: Int) = when (code) {
            200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 411 -> "Length Required"; 413 -> "Payload Too Large"; else -> "Error"
        }

        /** The TV's address on the local network (the first site-local IPv4); null without a network. */
        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}

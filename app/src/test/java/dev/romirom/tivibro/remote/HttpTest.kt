package dev.romirom.tivibro.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

class HttpTest {
    // A short request timeout, so the test can idle past it in well under a second
    private val http = Http({ Http.Response.html("hi ${it.method} ${it.path} ${it.text}") }, idleMs = 5_000, requestMs = 300)
        .also { it.start() }


    /** Sends [request] after [idleMs] of silence; returns everything the server wrote until it closed. */
    private fun exchange(request: String, idleMs: Long = 0): String = Socket("127.0.0.1", http.port).use { s ->
        s.soTimeout = 5_000
        Thread.sleep(idleMs)
        s.getOutputStream().write(request.toByteArray())
        s.getOutputStream().flush()
        String(s.getInputStream().readBytes(), Charsets.ISO_8859_1)
    }

    private fun get(path: String) = "GET $path HTTP/1.1\r\nHost: x\r\n\r\n"

    @Test fun answersARequest() {
        val r = exchange(get("/a%20b?x=1"))
        assertTrue(r, r.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(r, r.endsWith("hi GET /a b "))
    }

    @Test fun anIdlePreconnectionStillGetsAnAnswer() {
        // the bug: a browser's spare connection, used after the old 10 s read timeout, got closed with no response
        val r = exchange(get("/"), idleMs = 1_000)
        assertTrue(r, r.startsWith("HTTP/1.1 200 OK\r\n"))
    }

    @Test fun readsTheBody() {
        val r = exchange("POST /p HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello")
        assertTrue(r, r.endsWith("hi POST /p hello"))
    }

    @Test fun malformedRequestsGetAnErrorNotASilentClose() {
        assertTrue(exchange("garbage\r\n\r\n").startsWith("HTTP/1.1 400 "))
        assertTrue(exchange("POST / HTTP/1.1\r\nContent-Length: nope\r\n\r\n").startsWith("HTTP/1.1 400 "))
        assertTrue(exchange("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n").startsWith("HTTP/1.1 411 "))
    }

    @Test fun aRefusedBodyStillLetsTheClientReadTheAnswer() {
        val big = "x".repeat(200_000)
        val r = exchange("POST / HTTP/1.1\r\nContent-Length: ${big.length}\r\n\r\n$big")
        assertTrue(r, r.startsWith("HTTP/1.1 413 "))
    }

    @Test fun aStalledRequestTimesOutWithAnAnswer() {
        val r = exchange("GET / HTTP/1.1\r\nHost: x\r\n")
        assertEquals("HTTP/1.1 408 Request Timeout", r.substringBefore("\r\n"))
    }

    @Test fun aTlsClientHelloIsRefusedAtOnceWithAnAlert() {
        // https:// typed to this plain port (or an HTTPS upgrade): no line break in it, so it used to sit out requestMs
        val hello = ByteArray(1700) { 0x41 }.also { it[0] = 0x16; it[1] = 0x03; it[2] = 0x01 }
        val t0 = System.nanoTime()
        val r = Socket("127.0.0.1", http.port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(hello)
            s.shutdownOutput()
            s.getInputStream().readBytes()
        }
        assertEquals(listOf(0x15, 0x03, 0x01, 0x00, 0x02, 0x02, 0x28), r.map { it.toInt() and 0xFF })
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 250)
    }
}

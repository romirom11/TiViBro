package dev.romirom.tivibro.search

import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.Guide
import dev.romirom.tivibro.data.ProgrammeGuide
import dev.romirom.tivibro.data.XmltvGuide
import dev.romirom.tivibro.data.now
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.zip.CRC32

/**
 * Keeps [index] in step with the guide, on a lowest-priority thread of its own: whenever the playlist's channels or
 * the guide's bound days (or XMLTV import) change, the index saved in [dir] is loaded if it was built from the same
 * (well under a second), else it is rebuilt (some 20 s for a full guide on a weak TV) and saved. A build in progress
 * stops as soon as a newer [request] comes in: a guide sync binds its days one by one, and only the last state counts.
 * The first build waits until the app's start has settled; a load does not.
 */
class SearchIndexer(private val dir: File) {
    @Volatile var index: ProgrammeIndex = ProgrammeIndex.EMPTY; private set
    @Volatile var building = false; private set
    @Volatile private var wanted: String? = null
    private var built: String? = null
    private val born = SystemClock.uptimeMillis()
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST); r.run() }, "search-index").apply { isDaemon = true }
    }

    /** [guideKey] names the guide's state; [channels] are compared by identity first, then by content. Cheap when
     *  nothing changed: call it on every guide or channel change. */
    fun request(channels: List<Channel>, guide: ProgrammeGuide, guideKey: String) {
        val k = "${System.identityHashCode(channels)}:${channels.size}:$guideKey"
        if (k == wanted) return
        wanted = k
        building = true
        exec.execute {
            try {
                if (wanted == k) update(k, channels, guide, guideKey + "|" + signature(channels))
            } catch (e: CancellationException) {
                Log.i("tivibro", "search: build dropped for a newer one")
            } catch (e: Throwable) {
                Log.w("tivibro", "search: index failed", e)
            } finally {
                if (wanted == k) building = false
            }
        }
    }

    private fun update(k: String, channels: List<Channel>, guide: ProgrammeGuide, key: String) {
        if (key == built) return
        dir.mkdirs()
        val file = File(dir, "index.bin")
        val t0 = SystemClock.uptimeMillis()
        if (ProgrammeIndex.keyOf(file) == key) {
            ProgrammeIndex.load(file, key, channels)?.let {
                index = it; built = key
                Log.i("tivibro", "search: index of ${it.size} programmes loaded in ${SystemClock.uptimeMillis() - t0} ms")
                return
            }
        }
        val wait = STARTUP_CALM - (SystemClock.uptimeMillis() - born)
        if (wait > 0) Thread.sleep(wait)
        val cancelled = { wanted != k }
        if (cancelled()) return
        val t1 = SystemClock.uptimeMillis()
        val b = ProgrammeIndex.Builder(channels, now())
        when (guide) {
            is Guide -> GuideScan.segmented(guide, b, cancelled)
            is XmltvGuide -> GuideScan.xmltv(guide, b)
        }
        if (cancelled()) return
        val read = SystemClock.uptimeMillis()
        val fresh = b.build()
        val made = SystemClock.uptimeMillis()
        // saved, then loaded back: the loaded copy keeps its display titles on disk, the fresh one holds them all
        val titles = File(dir, "titles-${System.currentTimeMillis()}.bin")
        val tmp = File(dir, "index.bin.tmp")
        fresh.save(tmp, titles, key)
        check(tmp.renameTo(file))
        index = ProgrammeIndex.load(file, key, channels) ?: fresh
        built = key
        dir.listFiles()?.forEach { if (it.name.startsWith("titles-") && it.name != titles.name) it.delete() } // open ones stay readable
        Log.i("tivibro", "search: index of ${fresh.size} programmes, ${fresh.titleCount} titles, ${channels.size} channels: " +
            "read ${read - t1} ms, built ${made - read} ms, saved ${SystemClock.uptimeMillis() - made} ms")
    }

    /** What of the channels the index depends on, stable across starts (unlike the list's identity). */
    private fun signature(channels: List<Channel>): String {
        val c = CRC32()
        for (ch in channels) c.update("${ch.id}\u0001${ch.epgId}\u0001${ch.name}\u0001${ch.url.substringBefore('?').takeLast(40)}\u0001${ch.archiveDays}\n".toByteArray())
        return "${channels.size}:${c.value}"
    }

    private companion object {
        const val STARTUP_CALM = 15_000L
    }
}

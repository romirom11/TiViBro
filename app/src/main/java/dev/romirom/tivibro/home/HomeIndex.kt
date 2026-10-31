package dev.romirom.tivibro.home

import android.os.SystemClock
import android.util.Log
import dev.romirom.tivibro.data.Channel
import dev.romirom.tivibro.data.HOUR
import dev.romirom.tivibro.data.MIN
import dev.romirom.tivibro.data.ProgrammeGuide
import dev.romirom.tivibro.data.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A compact now/next index of the Home source channels: for each, its programmes from a little before now to some
 * hours ahead, nothing else. Built from the guide a chunk of channels at a time: the guide's getters only look into
 * memory and queue the decode for its own low-priority thread, so this asks for a chunk, waits until it has landed
 * (or a few seconds passed), keeps the window and moves on, publishing what is ready after every chunk. The guide's
 * own caches can drop those channels again; Home keeps working from this.
 */
class HomeIndex(private val scope: CoroutineScope, private val onUpdate: () -> Unit) {
    @Volatile var slots: List<Slot> = emptyList(); private set
    var building = false; private set
    private var builtAt = 0L
    private var builtFor: List<String> = emptyList()
    private var job: Job? = null

    /** Rebuilds when the channels changed, the last build is old, or it found nothing (the guide may have arrived since). */
    fun ensure(channels: List<Channel>, guide: ProgrammeGuide, force: Boolean = false) {
        val ids = channels.map { it.id }
        if (ids.isEmpty()) { job?.cancel(); building = false; slots = emptyList(); builtFor = ids; return }
        val stale = now() - builtAt > MAX_AGE || slots.isEmpty()
        if (!force && ids == builtFor && (building || !stale)) return
        rebuild(channels, guide)
    }

    fun clear() { job?.cancel(); building = false; slots = emptyList(); builtFor = emptyList(); builtAt = 0 }

    private fun rebuild(channels: List<Channel>, guide: ProgrammeGuide) {
        job?.cancel()
        building = true
        builtFor = channels.map { it.id }
        val old = slots.associateBy { it.ch.id }
        job = scope.launch {
            val t0 = SystemClock.uptimeMillis()
            // what the last build had stays on screen until its channel is read again
            val have = LinkedHashMap<String, Slot>()
            for (c in channels) old[c.id]?.let { have[c.id] = it }
            if (guide.hasData) for (chunk in channels.chunked(CHUNK)) {
                val until = SystemClock.uptimeMillis() + CHUNK_WAIT
                while (true) {
                    val t = now()
                    for (c in chunk) guide.list(c, t) // queues what is missing; cheap when it is not
                    if (chunk.all { guide.ready(it, t) } || SystemClock.uptimeMillis() > until) break
                    delay(200)
                }
                val t = now()
                for (c in chunk) {
                    val w = guide.list(c, t).filter { it.end > t - BEHIND && it.start < t + AHEAD }
                    if (w.isNotEmpty()) have[c.id] = Slot(c, w) else have.remove(c.id)
                }
                slots = channels.mapNotNull { have[it.id] }
                onUpdate()
                delay(40) // let the list rows on screen get their decodes in between
            }
            builtAt = now()
            building = false
            Log.i("tivibro", "home: index of ${slots.size} / ${channels.size} channels in ${SystemClock.uptimeMillis() - t0} ms")
            onUpdate()
        }
    }

    companion object {
        private const val CHUNK = 24
        private const val CHUNK_WAIT = 4000L
        /** Kept before now (a programme that just ended is still «just started» material for a bit) and ahead. */
        private const val BEHIND = 25 * MIN
        private const val AHEAD = 16 * HOUR
        private const val MAX_AGE = 30 * MIN
    }
}

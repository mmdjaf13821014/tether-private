package app.tether.audio

/**
 * Arrival spacing of a 20 ms frame stream: mean |gap − frame| (jitter) and the worst gap. Pure arithmetic, so it is
 * unit-tested without the framework; one writer thread per instance.
 */
class Spacing(private val frameMs: Double = 20.0) {
    private var last = 0L
    private var sum = 0.0
    var gaps = 0; private set
    var worstMs = 0.0; private set
    /** Gaps longer than 2.5 frames: a burst after a hold-up somewhere upstream. */
    var longGaps = 0; private set

    fun mark(nowNs: Long) {
        if (last != 0L) {
            val g = (nowNs - last) / 1e6
            sum += kotlin.math.abs(g - frameMs)
            gaps++
            if (g > worstMs) worstMs = g
            if (g > frameMs * 2.5) longGaps++
        }
        last = nowNs
    }

    val jitterMs get() = if (gaps > 0) sum / gaps else 0.0

    fun reset() { last = 0; sum = 0.0; gaps = 0; worstMs = 0.0; longGaps = 0 }
}

/** Delay of audio records from queueing to written into the link (the Bluetooth stack accepted them). */
class QueueDelay {
    var sent = 0; private set
    var late = 0; private set
    var overflow = 0; private set
    private var sumMs = 0.0
    var maxMs = 0.0; private set

    @Synchronized fun written(ms: Double) { sent++; sumMs += ms; if (ms > maxMs) maxMs = ms }
    @Synchronized fun droppedLate() { late++ }
    @Synchronized fun droppedOverflow() { overflow++ }
    val avgMs get() = if (sent > 0) sumMs / sent else 0.0
    @Synchronized fun reset() { sent = 0; late = 0; overflow = 0; sumMs = 0.0; maxMs = 0.0 }
}

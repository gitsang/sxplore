package com.sxplore.app.proxy

import android.os.SystemClock

/**
 * Sliding-window network speed meter (bytes/sec over the recent window).
 * Fed by the streaming proxy's prefetch workers; read by the player UI.
 */
class SpeedMonitor {

    // (elapsedRealtime, cumulative bytes) samples
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var total = 0L

    @Synchronized
    fun record(bytes: Int) {
        if (bytes <= 0) return
        total += bytes
        val now = SystemClock.elapsedRealtime()
        samples.addLast(now to total)
        while (samples.size > 2 && now - samples.first().first > WINDOW_MS) {
            samples.removeFirst()
        }
    }

    @Synchronized
    fun bytesPerSecond(): Long {
        if (samples.size < 2) return 0
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val dt = t1 - t0
        if (dt <= 0) return 0
        if (SystemClock.elapsedRealtime() - t1 > WINDOW_MS) return 0
        return (b1 - b0) * 1000 / dt
    }

    companion object {
        // Long enough to span two samples even on very slow links (~10KB/s).
        private const val WINDOW_MS = 8000L
    }
}

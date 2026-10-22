package com.isaacbenavides.aircast.features.receiver.domain.model

/** Free version: time left in the current connection's session. */
data class FreeSessionTime(
    val leftMs: Long,
    /** Monotonic clock (ms) at which [leftMs] was measured. */
    val capturedAtMs: Long,
    /** Counting down: mirroring, or an AirPlay video playing (not paused or loading). */
    val running: Boolean,
) {
    fun leftAt(nowMs: Long): Long = if (running) (leftMs - (nowMs - capturedAtMs)).coerceAtLeast(0) else leftMs
}

package com.isaacbenavides.aircast.features.receiver.domain.model

/** State of an AirPlay video playing on the TV, for the on-screen controls. */
data class StreamPlayback(
    val positionMs: Long = 0,
    /** 0 while unknown (still loading, or a live stream). */
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val buffering: Boolean = true,
    /** Monotonic clock (ms) at which [positionMs] was reported, to extrapolate playback. */
    val positionCapturedAtMs: Long = 0,
)

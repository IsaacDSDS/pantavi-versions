package com.isaacbenavides.aircast.features.receiver.domain.model

/** Track info sent by the client while streaming audio (not screen mirroring). */
data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** Encoded image (usually JPEG) as sent by the client. */
    val artwork: ByteArray? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    /** Monotonic clock (ms) at which [positionMs] was reported, to extrapolate playback. */
    val positionCapturedAtMs: Long = 0,
)

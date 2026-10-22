package com.isaacbenavides.aircast.features.receiver.domain.model

import com.isaacbenavides.aircast.core.entitlement.Entitlement

/** What the receiver advertises to clients, and how long a session may last. */
data class ReceiverCapabilities(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    /** Apple clients only send H.265 when the receiver asks for more than 1080 lines. */
    val h265: Boolean,
    /** AirPlay video: the AirPlay button inside video apps and Safari (uxplay -hls). */
    val airPlayVideo: Boolean,
    /** A client's mirroring and AirPlay video end this long after it started; null means no limit. */
    val sessionLimitMs: Long?,
) {
    companion object {
        val FREE = ReceiverCapabilities(
            maxWidth = 1920,
            maxHeight = 1080,
            maxFps = 30,
            h265 = false,
            airPlayVideo = true,
            sessionLimitMs = 15 * 60_000L,
        )
        val PRO = ReceiverCapabilities(
            maxWidth = 3840,
            maxHeight = 2160,
            maxFps = 60,
            h265 = true,
            airPlayVideo = true,
            sessionLimitMs = null,
        )

        fun of(entitlement: Entitlement) = if (entitlement == Entitlement.PRO) PRO else FREE
    }
}

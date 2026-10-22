package com.isaacbenavides.aircast.features.receiver.domain.model

enum class ReceiverPhase {
    STARTING,
    READY,
    CONNECTED,
    MIRRORING,
    /** AirPlay video: the TV plays a video URL handed over by an app (Safari, YouTube…). */
    STREAMING,
    PLAYING_AUDIO,
    ERROR,
}

enum class ReceiverError {
    NETWORK_DISCOVERY,
    SERVER,
    UNKNOWN,
}

data class ConnectedClient(
    val name: String,
    val model: String,
    val deviceId: String,
)

data class VideoSize(val width: Int, val height: Int) {
    val aspectRatio: Float get() = if (height == 0) 0f else width.toFloat() / height
}

data class ReceiverState(
    val phase: ReceiverPhase = ReceiverPhase.STARTING,
    val receiverName: String = "",
    val ipAddress: String? = null,
    val port: Int = 0,
    val client: ConnectedClient? = null,
    /** Size of the mirrored or streamed picture; non-null once the first frame is on screen. */
    val videoSize: VideoSize? = null,
    val nowPlaying: NowPlaying? = null,
    /** Non-null while [ReceiverPhase.STREAMING]. */
    val playback: StreamPlayback? = null,
    /** What the running receiver advertises (free or Pro). */
    val capabilities: ReceiverCapabilities = ReceiverCapabilities.FREE,
    /** The last session was ended by the free version's time limit. */
    val freeLimitReached: Boolean = false,
    /** Free version: time left once something has been shown on this connection; null otherwise. */
    val freeTime: FreeSessionTime? = null,
    val error: ReceiverError? = null,
)

package com.isaacbenavides.aircast.features.receiver.data.nativebridge

/** JNI entry points of libaircast (UxPlay AirPlay core + bridge). */
internal object AirPlayNative {

    const val ERROR_BAD_MAC = -1
    const val ERROR_DNSSD_INIT = -2
    const val ERROR_RAOP_INIT = -3
    const val ERROR_HTTPD_START = -4
    const val ERROR_DNSSD_REGISTER = -5
    const val ERROR_ALREADY_RUNNING = -6

    init {
        System.loadLibrary("aircast")
    }

    /** Returns the TCP port the AirPlay server listens on, or a negative ERROR_* code. */
    external fun nativeStart(
        callbacks: NativeCallbacks,
        name: String,
        hostLabel: String,
        macAddress: String,
        keyFile: String,
        width: Int,
        height: Int,
        refreshRate: Int,
        maxFps: Int,
        h265Support: Boolean,
        /** Advertise AirPlay video: apps hand the TV a URL / HLS playlist instead of mirroring. */
        hlsSupport: Boolean,
        /** Colon-separated audio language preferences for YouTube HLS, e.g. "es-MX:es:en". */
        languages: String,
        debugLog: Boolean,
    ): Int

    external fun nativeStop()

    /** Closes the client's AirPlay video connections (the client then ends its video session). */
    external fun nativeEndAirPlayVideo()

    /** Stops audio and video and drops every client connection, within about a second. */
    external fun nativeDisconnectClients()
}

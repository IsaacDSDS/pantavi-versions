package com.isaacbenavides.aircast.features.receiver.data.nativebridge

/**
 * Events raised by the native AirPlay core (android/aircast_jni.c) on its own threads.
 * Method names and JVM signatures are looked up from C — keep both sides in sync.
 */
interface NativeCallbacks {
    fun onClientRequest(deviceId: String, model: String, name: String)
    fun onConnectionCount(count: Int)
    fun onVideoCodec(isH265: Boolean)
    fun onVideoFrame(data: ByteArray, isH265: Boolean, ptsUs: Long)
    fun onVideoSize(sourceWidth: Float, sourceHeight: Float, width: Float, height: Float)
    fun onVideoStopped()
    fun onVideoFlush()
    fun onMirrorRunning(running: Boolean)
    fun onAudioFormat(compressionType: Int, framesPerPacket: Int, usingScreen: Boolean, isMedia: Boolean)
    fun onAudioEncoded(data: ByteArray, compressionType: Int, ptsUs: Long)
    fun onAudioPcm(data: ByteArray, ptsUs: Long)
    fun onAudioFlush()
    fun onAudioStopped()
    fun onVolume(volumeDb: Float)
    fun onMetadata(data: ByteArray)
    fun onCoverArt(data: ByteArray)
    fun onProgress(start: Long, current: Long, end: Long)

    // AirPlay video (URL playback). Positions are in seconds, as in the protocol.
    fun onStreamPlay(location: String, startPositionSec: Float)
    fun onStreamSeek(positionSec: Float)
    /** 0 = pause, 1 = play. */
    fun onStreamRate(rate: Float)
    fun onStreamStop()

    /**
     * Called (on an HTTP thread) for every client poll; must return immediately:
     * [duration, position, rate, seekStart, seekDuration, bufferEmpty, bufferFull].
     * position -1 = not available yet; duration -1 = playback finished (ends the session).
     */
    fun onStreamPlaybackInfo(): DoubleArray?

    /** Pauses the current item and returns its position, so it can be resumed later. */
    fun onStreamPlaylistRemove(): Float
}

package com.isaacbenavides.aircast.features.receiver.data

import android.content.Context
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import com.isaacbenavides.aircast.BuildConfig
import com.isaacbenavides.aircast.core.entitlement.EntitlementRepository
import com.isaacbenavides.aircast.core.media.AndroidRenderSurface
import com.isaacbenavides.aircast.core.network.NetworkAddressProvider
import com.isaacbenavides.aircast.features.receiver.data.identity.ReceiverIdentityStore
import com.isaacbenavides.aircast.features.receiver.data.media.AudioPlayer
import com.isaacbenavides.aircast.features.receiver.data.media.DmapMetadata
import com.isaacbenavides.aircast.features.receiver.data.media.StreamPlayer
import com.isaacbenavides.aircast.features.receiver.data.media.VideoDecoder
import com.isaacbenavides.aircast.features.receiver.data.nativebridge.AirPlayNative
import com.isaacbenavides.aircast.features.receiver.data.nativebridge.NativeCallbacks
import com.isaacbenavides.aircast.features.receiver.domain.model.ConnectedClient
import com.isaacbenavides.aircast.features.receiver.domain.model.FreeSessionTime
import com.isaacbenavides.aircast.features.receiver.domain.model.NowPlaying
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverCapabilities
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverError
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverPhase
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverState
import com.isaacbenavides.aircast.features.receiver.domain.model.StreamPlayback
import com.isaacbenavides.aircast.features.receiver.domain.model.VideoSize
import com.isaacbenavides.aircast.features.receiver.domain.repository.AirPlayReceiverRepository
import com.isaacbenavides.aircast.features.receiver.domain.repository.RenderSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class AirPlayReceiverRepositoryImpl(
    appContext: Context,
    private val identity: ReceiverIdentityStore,
    private val network: NetworkAddressProvider,
    private val entitlements: EntitlementRepository,
) : AirPlayReceiverRepository, NativeCallbacks {

    /** Session facts the native callbacks report; [ReceiverState.phase] is derived from them. */
    private data class Session(
        val connections: Int = 0,
        val mirroring: Boolean = false,
        val audioOnly: Boolean = false,
        /** AirPlay video: the TV plays a URL handed over by the client. */
        val streaming: Boolean = false,
    )

    private val _state = MutableStateFlow(ReceiverState())
    override val state: StateFlow<ReceiverState> = _state.asStateFlow()

    private val lifecycleLock = Any()
    private val stateLock = Any()
    @Volatile private var running = false
    private var session = Session()

    /** Guards the hand-over of the screen surface between [video] and [stream]. */
    private val streamLock = Any()
    private var streamActive = false

    /**
     * Free session clock: [ReceiverCapabilities.sessionLimitMs] per connection, spent only while
     * mirroring or while an AirPlay video plays. The time left carries over to the next video.
     */
    private val sessionTimer = Executors.newSingleThreadScheduledExecutor { Thread(it, "AirCastSessionLimit") }
    private val freeClockLock = Any()
    private var freeDeadline: ScheduledFuture<*>? = null
    private var freeLeftMs: Long? = null // null until something is shown on this connection
    private var freeRunningSince: Long? = null

    private val video = VideoDecoder(object : VideoDecoder.Listener {
        override fun onFirstFrameRendered(width: Int, height: Int) =
            updateState { it.copy(videoSize = VideoSize(width, height)) }

        override fun onVideoSizeChanged(width: Int, height: Int) =
            updateState { it.copy(videoSize = VideoSize(width, height)) }
    })
    private val audio = AudioPlayer()
    private val stream = StreamPlayer(appContext, object : StreamPlayer.Listener {
        override fun onVideoSizeChanged(width: Int, height: Int) =
            updateState { it.copy(videoSize = VideoSize(width, height)) }

        override fun onPlaybackChanged(positionMs: Long, durationMs: Long, paused: Boolean, buffering: Boolean) {
            updateState {
                it.copy(
                    playback = StreamPlayback(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        paused = paused,
                        buffering = buffering,
                        positionCapturedAtMs = SystemClock.elapsedRealtime(),
                    ),
                )
            }
            updateFreeClock()
        }
    })

    override fun start(): Unit = synchronized(lifecycleLock) {
        if (running) return
        val name = identity.receiverName()
        val capabilities = ReceiverCapabilities.of(entitlements.entitlement.value)
        synchronized(stateLock) {
            session = Session()
            _state.value = ReceiverState(
                phase = ReceiverPhase.STARTING,
                receiverName = name,
                ipAddress = network.currentIpv4(),
                capabilities = capabilities,
            )
        }
        video.start()
        audio.start()
        stream.setMaxVideoSize(capabilities.maxWidth, capabilities.maxHeight)
        val port = AirPlayNative.nativeStart(
            callbacks = this,
            name = name,
            hostLabel = identity.hostLabel(),
            macAddress = identity.macAddress(),
            keyFile = identity.keyFilePath(),
            width = capabilities.maxWidth,
            height = capabilities.maxHeight,
            refreshRate = REFRESH_RATE,
            maxFps = capabilities.maxFps,
            h265Support = capabilities.h265,
            hlsSupport = capabilities.airPlayVideo,
            languages = preferredLanguages(),
            debugLog = BuildConfig.DEBUG,
        )
        if (port > 0) {
            running = true
            Log.i(TAG, "receiver \"$name\" listening on port $port ($capabilities)")
            updateState { it.copy(port = port, error = null) }
        } else {
            Log.e(TAG, "native start failed: $port")
            video.stop()
            audio.stop()
            updateState { it.copy(phase = ReceiverPhase.ERROR, error = port.toReceiverError()) }
        }
    }

    override fun stop(): Unit = synchronized(lifecycleLock) {
        if (!running) return
        resetFreeClock()
        AirPlayNative.nativeStop()
        synchronized(streamLock) {
            stream.stop()
            streamActive = false
        }
        video.stop()
        audio.stop()
        running = false
        synchronized(stateLock) {
            session = Session()
            _state.value = _state.value.copy(
                phase = ReceiverPhase.STARTING,
                client = null,
                videoSize = null,
                nowPlaying = null,
                playback = null,
            )
        }
    }

    override fun restart(): Unit = synchronized(lifecycleLock) {
        stop()
        start()
    }

    override fun applyEntitlement(): Unit = synchronized(lifecycleLock) {
        if (!running) return
        val current = _state.value
        val wanted = ReceiverCapabilities.of(entitlements.entitlement.value)
        // Capabilities are announced when the server starts: never cut a connected client.
        if (wanted == current.capabilities || current.phase != ReceiverPhase.READY) return
        Log.i(TAG, "plan changed, restarting receiver")
        stop()
        start()
    }

    override fun attachRenderSurface(surface: RenderSurface?) {
        // Both get the surface; only the active one (mirroring or AirPlay video) draws on it.
        val androidSurface = (surface as? AndroidRenderSurface)?.surface
        video.setSurface(androidSurface)
        stream.setSurface(androidSurface)
        if (surface == null) updateState { it.copy(videoSize = null) }
    }

    override fun setStreamPaused(paused: Boolean) = stream.setPaused(paused)

    override fun seekStreamBy(deltaMs: Long) = stream.seekBy(deltaMs)

    override fun stopStream() {
        synchronized(streamLock) {
            if (!streamActive) return
            stream.stop(reportFinished = true)
            streamActive = false
            if (running) video.start()
        }
        updateSession { it.copy(streaming = false) }
        // Close the client's AirPlay video connections, as when a video ends, so it stops waiting.
        synchronized(lifecycleLock) {
            if (running) AirPlayNative.nativeEndAirPlayVideo()
        }
    }

    // ---------------------------------------------------------------- NativeCallbacks

    override fun onClientRequest(deviceId: String, model: String, name: String) {
        Log.i(TAG, "client request: $name ($model)")
        updateState { it.copy(client = ConnectedClient(name = name, model = model, deviceId = deviceId)) }
    }

    override fun onConnectionCount(count: Int) {
        // The client is gone: an AirPlay video it started must not keep playing on its own.
        if (count <= 0) {
            onStreamStop()
            resetFreeClock()
        }
        updateSession { s -> if (count <= 0) Session() else s.copy(connections = count) }
    }

    override fun onVideoCodec(isH265: Boolean) {
        Log.i(TAG, "mirroring codec: ${if (isH265) "H.265" else "H.264"}")
    }

    override fun onVideoFrame(data: ByteArray, isH265: Boolean, ptsUs: Long) = video.submit(data, isH265, ptsUs)

    override fun onVideoSize(sourceWidth: Float, sourceHeight: Float, width: Float, height: Float) {
        Log.i(TAG, "client screen ${sourceWidth}x$sourceHeight, stream ${width}x$height")
    }

    override fun onVideoStopped() {
        video.reset()
        updateSession { it.copy(mirroring = false) }
    }

    override fun onVideoFlush() = Unit

    override fun onMirrorRunning(running: Boolean) = updateSession { it.copy(mirroring = running) }

    override fun onAudioFormat(compressionType: Int, framesPerPacket: Int, usingScreen: Boolean, isMedia: Boolean) {
        audio.configure(compressionType, lowLatency = usingScreen)
        updateSession { it.copy(audioOnly = !usingScreen) }
    }

    override fun onAudioEncoded(data: ByteArray, compressionType: Int, ptsUs: Long) = audio.submitEncoded(data, ptsUs)

    override fun onAudioPcm(data: ByteArray, ptsUs: Long) = audio.submitPcm(data)

    override fun onAudioFlush() = audio.flush()

    override fun onAudioStopped() {
        audio.release()
        updateSession { it.copy(audioOnly = false) }
    }

    override fun onVolume(volumeDb: Float) {
        val gain = airPlayVolumeToGain(volumeDb)
        audio.setGain(gain)
        stream.setVolume(gain)
    }

    override fun onMetadata(data: ByteArray) {
        val metadata = DmapMetadata.parse(data)
        updateNowPlaying { it.copy(title = metadata.title, artist = metadata.artist, album = metadata.album) }
    }

    override fun onCoverArt(data: ByteArray) = updateNowPlaying { it.copy(artwork = data.takeIf { d -> d.isNotEmpty() }) }

    override fun onProgress(start: Long, current: Long, end: Long) {
        val position = ((current - start) and RTP_MASK) * 1000 / RTP_CLOCK_RATE
        val duration = ((end - start) and RTP_MASK) * 1000 / RTP_CLOCK_RATE
        updateNowPlaying {
            it.copy(positionMs = position, durationMs = duration, positionCapturedAtMs = SystemClock.elapsedRealtime())
        }
    }

    override fun onStreamPlay(location: String, startPositionSec: Float) {
        synchronized(streamLock) {
            if (!streamActive) {
                // The mirror decoder must let go of the screen surface before the player takes it.
                video.stop()
                audio.release()
                streamActive = true
            }
            stream.play(location, startPositionSec)
        }
        updateSession { it.copy(streaming = true, mirroring = false, audioOnly = false) }
        updateState {
            it.copy(
                playback = StreamPlayback(
                    positionMs = (startPositionSec * 1000).toLong(),
                    positionCapturedAtMs = SystemClock.elapsedRealtime(),
                ),
            )
        }
    }

    override fun onStreamSeek(positionSec: Float) = stream.seekTo(positionSec)

    override fun onStreamRate(rate: Float) = stream.setRate(rate)

    override fun onStreamStop() {
        synchronized(streamLock) {
            if (!streamActive) return
            stream.stop()
            streamActive = false
            if (running) video.start()
        }
        updateSession { it.copy(streaming = false) }
    }

    override fun onStreamPlaybackInfo(): DoubleArray? {
        val status = stream.status() ?: return null
        return doubleArrayOf(
            status.durationSec,
            status.positionSec,
            if (status.playing) 1.0 else 0.0,
            0.0,
            status.seekableSec,
            if (status.buffering) 1.0 else 0.0,
            if (status.buffering) 0.0 else 1.0,
        )
    }

    override fun onStreamPlaylistRemove(): Float = stream.pauseAndGetPosition()

    // ---------------------------------------------------------------- free session limit

    /** Starts or pauses the free session clock to match what the TV is showing. */
    private fun updateFreeClock() = synchronized(freeClockLock) {
        val state = _state.value
        val limitMs = state.capabilities.sessionLimitMs ?: return
        val playback = state.playback
        val showing = state.phase == ReceiverPhase.MIRRORING ||
            (state.phase == ReceiverPhase.STREAMING && playback != null && !playback.paused && !playback.buffering)
        val now = SystemClock.elapsedRealtime()
        val since = freeRunningSince
        if (showing && since == null) {
            val left = freeLeftMs ?: limitMs
            freeLeftMs = left
            freeRunningSince = now
            freeDeadline = sessionTimer.schedule(Runnable { endFreeSession() }, left, TimeUnit.MILLISECONDS)
            updateState {
                it.copy(freeLimitReached = false, freeTime = FreeSessionTime(left, now, running = true))
            }
        } else if (!showing && since != null) {
            val left = (freeLeftMs!! - (now - since)).coerceAtLeast(0)
            freeDeadline?.cancel(false)
            freeDeadline = null
            freeLeftMs = left
            freeRunningSince = null
            updateState { it.copy(freeTime = FreeSessionTime(left, now, running = false)) }
        }
    }

    /** The connection is gone: the next one starts with the full time. */
    private fun resetFreeClock() = synchronized(freeClockLock) {
        freeDeadline?.cancel(false)
        freeDeadline = null
        freeLeftMs = null
        freeRunningSince = null
        updateState { it.copy(freeTime = null) }
    }

    private fun endFreeSession() {
        synchronized(freeClockLock) {
            // Paused just as the time ran out: it ends when playback resumes.
            if (freeRunningSince == null) return
            freeDeadline = null
            freeLeftMs = 0
            freeRunningSince = null
        }
        Log.i(TAG, "free session limit reached, disconnecting")
        // Before the video stops, so the UI knows why it ended.
        updateState { it.copy(freeLimitReached = true, freeTime = null) }
        stopStream()
        synchronized(lifecycleLock) {
            if (running) AirPlayNative.nativeDisconnectClients()
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun updateSession(transform: (Session) -> Session) {
        synchronized(stateLock) {
            session = transform(session)
            _state.value = withDerivedFields(_state.value)
        }
        updateFreeClock()
    }

    private fun updateState(transform: (ReceiverState) -> ReceiverState) = synchronized(stateLock) {
        _state.value = withDerivedFields(transform(_state.value))
    }

    private fun updateNowPlaying(transform: (NowPlaying) -> NowPlaying) =
        updateState { it.copy(nowPlaying = transform(it.nowPlaying ?: NowPlaying())) }

    private fun withDerivedFields(state: ReceiverState): ReceiverState {
        if (state.phase == ReceiverPhase.ERROR) return state
        if (!running && state.phase == ReceiverPhase.STARTING) return state
        val s = session
        val phase = when {
            s.streaming -> ReceiverPhase.STREAMING
            s.mirroring -> ReceiverPhase.MIRRORING
            s.audioOnly -> ReceiverPhase.PLAYING_AUDIO
            s.connections > 0 -> ReceiverPhase.CONNECTED
            else -> ReceiverPhase.READY
        }
        return state.copy(
            phase = phase,
            ipAddress = state.ipAddress ?: network.currentIpv4(),
            client = if (s.connections > 0) state.client else null,
            videoSize = if (s.mirroring || s.streaming) state.videoSize else null,
            nowPlaying = if (s.audioOnly) state.nowPlaying else null,
            playback = if (s.streaming) state.playback else null,
        )
    }

    private fun Int.toReceiverError(): ReceiverError = when (this) {
        AirPlayNative.ERROR_DNSSD_INIT, AirPlayNative.ERROR_DNSSD_REGISTER -> ReceiverError.NETWORK_DISCOVERY
        AirPlayNative.ERROR_RAOP_INIT, AirPlayNative.ERROR_HTTPD_START -> ReceiverError.SERVER
        else -> ReceiverError.UNKNOWN
    }

    private companion object {
        const val TAG = "AirCastReceiver"
        /** Display refresh rate announced to clients; the frame rate cap comes from the plan. */
        const val REFRESH_RATE = 60
        const val RTP_CLOCK_RATE = 44_100L
        const val RTP_MASK = 0xFFFFFFFFL

        /** The TV's languages, most preferred first, each followed by its base language. */
        fun preferredLanguages(): String {
            val locales = LocaleList.getDefault()
            return (0 until locales.size())
                .map { locales[it] }
                .flatMap { listOf(it.toLanguageTag(), it.language) }
                .filter { it.isNotBlank() && it != "und" }
                .distinct()
                .joinToString(":")
        }

        /** AirPlay volume is -30..0 dB, with -144 meaning mute (same mapping as UxPlay). */
        fun airPlayVolumeToGain(db: Float): Float = when {
            db <= -30f -> 0f
            db >= 0f -> 1f
            else -> 10f.pow(db / 20f)
        }
    }
}

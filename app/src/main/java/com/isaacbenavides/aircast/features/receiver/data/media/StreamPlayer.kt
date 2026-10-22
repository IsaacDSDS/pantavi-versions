package com.isaacbenavides.aircast.features.receiver.data.media

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays "AirPlay video": the client hands the TV a URL (Safari and other apps) or an HLS playlist
 * served locally by the AirPlay core (YouTube app), and controls playback remotely.
 *
 * ExoPlayer lives on its own thread; the client's frequent /playback-info polls are answered from
 * [status], a snapshot refreshed on that thread, so HTTP threads never wait for the player.
 * The TV remote can also pause and seek: the client sees it on its next poll.
 */
@OptIn(UnstableApi::class)
internal class StreamPlayer(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onVideoSizeChanged(width: Int, height: Int)

        /** Pause, seek or buffering changed (not called for normal progress). */
        fun onPlaybackChanged(positionMs: Long, durationMs: Long, paused: Boolean, buffering: Boolean)
    }

    /** What the AirPlay client sees when it polls. Times in seconds, as in the protocol. */
    data class Status(
        val durationSec: Double,
        val positionSec: Double,
        val playing: Boolean,
        val seekableSec: Double,
        val buffering: Boolean,
        val finished: Boolean,
    )

    private val thread = HandlerThread("AirCastStream", Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)
    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        // Media servers that special-case Apple players (signed HLS, CDNs) expect AirPlay
        // receivers to fetch like an Apple TV does.
        .setUserAgent(USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(HTTP_TIMEOUT_MS)
        .setReadTimeoutMs(HTTP_TIMEOUT_MS)

    @Volatile private var status: Status? = null

    // Player-thread state.
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var volume = 1f
    private var maxVideoSide = Int.MAX_VALUE
    private var url: String? = null
    private var startPositionMs = 0L
    private var forcedHls = false
    private var prepared = false
    private var finished = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) prepared = true
            if (playbackState == Player.STATE_ENDED) finished = true
            publishStatus()
            reportPlayback()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publishStatus()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = reportPlayback()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = reportPlayback()

        override fun onRenderedFirstFrame() = reportVideoSize()

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = reportVideoSize()

        override fun onPlayerError(error: PlaybackException) {
            // Apps often send HLS playlists without a ".m3u8" suffix: retry once as HLS.
            val retryAsHls = !prepared && !forcedHls &&
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            val retryUrl = url
            if (retryAsHls && retryUrl != null) {
                Log.i(TAG, "not a progressive stream, retrying as HLS")
                forcedHls = true
                // Not from inside the failing player's own callback.
                handler.post { startPlayer(retryUrl, startPositionMs) }
                return
            }
            Log.e(TAG, "playback failed (${error.errorCodeName})", error)
            // Reported as finished so the client ends the session instead of waiting forever.
            finished = true
            publishStatus()
        }
    }

    private val decoderLogger = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            Log.i(TAG, "video decoder $decoderName")
        }

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            Log.i(TAG, "video ${format.sampleMimeType} ${format.width}x${format.height}")
        }
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            publishStatus()
            if (player != null) handler.postDelayed(this, STATUS_INTERVAL_MS)
        }
    }

    fun play(url: String, startPositionSec: Float) {
        status = null // "not available" until the new item is ready, never the previous one's state
        handler.post {
            this.url = url
            startPositionMs = (startPositionSec * 1000).toLong().coerceAtLeast(0L)
            forcedHls = false
            startPlayer(url, startPositionMs)
        }
    }

    fun seekTo(positionSec: Float) {
        handler.post { player?.seekTo((positionSec * 1000).toLong().coerceAtLeast(0L)) }
    }

    /** AirPlay rate: 0 pauses, anything else plays. */
    fun setRate(rate: Float) = setPaused(rate <= 0f)

    fun setPaused(paused: Boolean) {
        handler.post {
            player?.playWhenReady = !paused
            publishStatus()
        }
    }

    /** Relative seek from the TV remote; stops short of the end so a seek never ends the video. */
    fun seekBy(deltaMs: Long) {
        handler.post {
            val exo = player ?: return@post
            val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
            val durationMs = exo.duration
            exo.seekTo(
                if (durationMs == C.TIME_UNSET) target
                else target.coerceAtMost((durationMs - END_MARGIN_MS).coerceAtLeast(0L)),
            )
        }
    }


    /** Pauses now and returns the position the client should resume from. */
    fun pauseAndGetPosition(): Float {
        val position = status?.takeIf { !it.finished }?.positionSec ?: 0.0
        handler.post { player?.playWhenReady = false }
        return position.toFloat()
    }

    /** Caps the variant picked from adaptive streams (HLS); a single larger stream still plays. */
    fun setMaxVideoSize(width: Int, height: Int) {
        // The long side only, so portrait videos get the same quality as landscape ones.
        handler.post { maxVideoSide = maxOf(width, height) }
    }

    fun setVolume(gain: Float) {
        handler.post {
            volume = gain
            player?.volume = gain
        }
    }

    /** Blocking, so the surface is free for the mirror decoder (or destroyed) once this returns. */
    fun setSurface(surface: Surface?) = runOnPlayerThread {
        this.surface = surface
        player?.setVideoSurface(surface)
    }

    /**
     * Blocking: releases the player and with it the surface and audio output. With [reportFinished]
     * (closed on the TV) the client's next poll learns the video is over and ends its session.
     */
    fun stop(reportFinished: Boolean = false) {
        status = null
        runOnPlayerThread {
            releasePlayer()
            url = null
        }
        // After the release, so the status ticker can't overwrite it.
        if (reportFinished) status = FINISHED
    }

    fun status(): Status? = status

    private fun startPlayer(url: String, startPositionMs: Long) {
        releasePlayer()
        prepared = false
        finished = false
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (forcedHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        val exo = ExoPlayer.Builder(context)
            .setLooper(thread.looper)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false,
            )
            .build()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(maxVideoSide, maxVideoSide)
            .build()
        exo.addListener(playerListener)
        exo.addAnalyticsListener(decoderLogger)
        exo.volume = volume
        exo.setVideoSurface(surface)
        exo.setMediaItem(item, startPositionMs)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
        handler.removeCallbacks(statusTicker)
        handler.post(statusTicker)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(statusTicker)
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        prepared = false
        finished = false
    }

    private fun publishStatus() {
        val exo = player
        status = when {
            exo == null -> null
            finished -> FINISHED
            !prepared -> null
            else -> {
                val durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                Status(
                    durationSec = durationMs / 1000.0,
                    positionSec = exo.currentPosition / 1000.0,
                    playing = exo.playWhenReady,
                    seekableSec = if (exo.isCurrentMediaItemSeekable) durationMs / 1000.0 else 0.0,
                    buffering = exo.playbackState == Player.STATE_BUFFERING,
                    finished = false,
                )
            }
        }
    }

    private fun reportPlayback() {
        val exo = player ?: return
        listener.onPlaybackChanged(
            positionMs = exo.currentPosition,
            durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            paused = !exo.playWhenReady,
            buffering = exo.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun reportVideoSize() {
        val size = player?.videoSize ?: return
        if (size.width <= 0 || size.height <= 0) return
        listener.onVideoSizeChanged((size.width * size.pixelWidthHeightRatio).toInt(), size.height)
    }

    private fun runOnPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) {
            block()
            return
        }
        val done = CountDownLatch(1)
        handler.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (!done.await(BLOCKING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "player thread did not respond in $BLOCKING_TIMEOUT_MS ms")
        }
    }

    private companion object {
        const val TAG = "AirCastStream"
        const val USER_AGENT = "AppleCoreMedia/1.0.0 (Apple TV; U; CPU OS 17_0 like Mac OS X)"
        const val HTTP_TIMEOUT_MS = 15_000
        const val STATUS_INTERVAL_MS = 250L
        const val BLOCKING_TIMEOUT_MS = 2_000L
        const val END_MARGIN_MS = 1_000L
        val FINISHED = Status(
            durationSec = -1.0,
            positionSec = -1.0,
            playing = false,
            seekableSec = 0.0,
            buffering = false,
            finished = true,
        )
    }
}

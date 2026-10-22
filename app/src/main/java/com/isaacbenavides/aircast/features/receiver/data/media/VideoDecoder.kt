package com.isaacbenavides.aircast.features.receiver.data.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Hardware H.264/H.265 decoder for the mirroring stream.
 *
 * AirPlay clients send a keyframe only when a stream (re)starts, so the decoder must never lose
 * its reference state: while no on-screen surface exists it decodes into a placeholder surface,
 * holds the latest picture, and switches outputs with [MediaCodec.setOutputSurface].
 */
internal class VideoDecoder(private val listener: Listener) {

    interface Listener {
        fun onFirstFrameRendered(width: Int, height: Int)
        fun onVideoSizeChanged(width: Int, height: Int)
    }

    private class Frame(val data: ByteArray, val isH265: Boolean, val ptsUs: Long, val isConfig: Boolean)

    private class Work(val frame: Frame?, val surfaceChanged: Boolean, val surface: Surface?, val reset: Boolean)

    private val lock = ReentrantLock()
    private val wakeUp = lock.newCondition()
    private val pending = ArrayDeque<Frame>()
    private var pendingBytes = 0L
    private var dropUntilConfig = false
    @Volatile private var targetSurface: Surface? = null
    private var surfaceChanged = false
    private var resetRequested = false
    private var running = false
    private var thread: Thread? = null

    // Decoder-thread state.
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var codecConfig: ByteArray? = null
    private var codecIsH265 = false
    private var awaitingConfig = true
    private var heldOutputIndex = -1
    private var width = 0
    private var height = 0
    private var firstFrameReported = false
    private var placeholderTexture: SurfaceTexture? = null
    private var placeholder: Surface? = null

    fun start() {
        lock.withLock {
            if (running) return
            running = true
        }
        thread = Thread(::runLoop, "AirCastVideo").also { it.start() }
    }

    fun stop() {
        lock.withLock {
            running = false
            pending.clear()
            pendingBytes = 0
            wakeUp.signalAll()
        }
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
    }

    fun setSurface(surface: Surface?) = lock.withLock {
        targetSurface = surface
        surfaceChanged = true
        wakeUp.signalAll()
    }

    fun submit(data: ByteArray, isH265: Boolean, ptsUs: Long) {
        val isConfig = NalUnits.startsWithParameterSet(data, isH265)
        lock.withLock {
            if (!running) return
            if (isConfig) {
                // A new keyframe supersedes everything still waiting to be decoded.
                pending.clear()
                pendingBytes = 0
                dropUntilConfig = false
            }
            if (pendingBytes + data.size > MAX_PENDING_BYTES) {
                Log.w(TAG, "decoder is falling behind: dropping until next keyframe")
                pending.clear()
                pendingBytes = 0
                dropUntilConfig = true
            }
            if (dropUntilConfig) return
            pending.addLast(Frame(data, isH265, ptsUs, isConfig))
            pendingBytes += data.size
            wakeUp.signalAll()
        }
    }

    /** The mirroring session ended: release the codec and wait for the next stream. */
    fun reset() = lock.withLock {
        pending.clear()
        pendingBytes = 0
        dropUntilConfig = false
        resetRequested = true
        wakeUp.signalAll()
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val work = takeWork() ?: break
            if (work.reset) releaseCodec()
            if (work.surfaceChanged) switchOutput(work.surface)
            work.frame?.let { decode(it, info) }
            drainOutput(info)
        }
        releaseCodec()
        placeholder?.release()
        placeholderTexture?.release()
        placeholder = null
        placeholderTexture = null
    }

    private fun takeWork(): Work? = lock.withLock {
        if (running && pending.isEmpty() && !surfaceChanged && !resetRequested) {
            wakeUp.await(if (codec != null) DRAIN_INTERVAL_MS else IDLE_WAIT_MS, TimeUnit.MILLISECONDS)
        }
        if (!running) return@withLock null
        val frame = pending.removeFirstOrNull()?.also { pendingBytes -= it.data.size }
        val work = Work(frame, surfaceChanged, targetSurface, resetRequested)
        surfaceChanged = false
        resetRequested = false
        work
    }

    private fun decode(frame: Frame, info: MediaCodec.BufferInfo) {
        try {
            if (frame.isConfig) {
                val config = NalUnits.parameterSets(frame.data, frame.isH265)
                if (codec == null || frame.isH265 != codecIsH265 || !config.contentEquals(codecConfig)) {
                    configure(config, frame.isH265)
                }
                awaitingConfig = false
            }
            val decoder = codec ?: return
            if (awaitingConfig) return
            queueInput(decoder, frame, info)
        } catch (e: Exception) {
            Log.e(TAG, "video decoder failure, waiting for the next keyframe", e)
            releaseCodec()
        }
    }

    private fun queueInput(decoder: MediaCodec, frame: Frame, info: MediaCodec.BufferInfo) {
        repeat(INPUT_ATTEMPTS) {
            val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) {
                val buffer = decoder.getInputBuffer(index) ?: return
                buffer.clear()
                if (frame.data.size > buffer.capacity()) {
                    Log.w(TAG, "frame of ${frame.data.size} bytes exceeds input buffer ${buffer.capacity()}")
                    decoder.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
                    return
                }
                buffer.put(frame.data)
                decoder.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
                return
            }
            drainOutput(info)
        }
        Log.w(TAG, "no decoder input buffer available, frame dropped")
    }

    private fun configure(config: ByteArray, isH265: Boolean) {
        releaseCodec()
        val mime = if (isH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = if (isH265) {
            MediaFormat.createVideoFormat(mime, MAX_WIDTH_H265, MAX_HEIGHT_H265)
        } else {
            MediaFormat.createVideoFormat(mime, MAX_WIDTH_H264, MAX_HEIGHT_H264)
        }
        if (isH265) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config))
        } else {
            val nals = NalUnits.split(config, false)
            nals.getOrNull(0)?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(config.copyOfRange(it.start, it.end))) }
            nals.getOrNull(1)?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(config.copyOfRange(it.start, it.end))) }
        }
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // real-time
        val decoder = MediaCodec.createDecoderByType(mime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            decoder.codecInfo.getCapabilitiesForType(mime)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        ) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val output = targetSurface?.takeIf { it.isValid } ?: placeholderSurface()
        decoder.configure(format, output, null, 0)
        decoder.start()
        Log.i(TAG, "configured ${decoder.name} for $mime")
        codec = decoder
        codecSurface = output
        codecConfig = config
        codecIsH265 = isH265
    }

    private fun switchOutput(surface: Surface?) {
        val decoder = codec ?: return
        val target = surface?.takeIf { it.isValid } ?: placeholderSurface()
        if (target === codecSurface) return
        try {
            decoder.setOutputSurface(target)
            codecSurface = target
            if (target !== placeholder) {
                firstFrameReported = false
                // Show the picture decoded while nothing was on screen.
                if (heldOutputIndex >= 0) {
                    decoder.releaseOutputBuffer(heldOutputIndex, true)
                    heldOutputIndex = -1
                    reportFirstFrame()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "setOutputSurface failed, waiting for the next keyframe", e)
            releaseCodec()
        }
    }

    private fun drainOutput(info: MediaCodec.BufferInfo) {
        val decoder = codec ?: return
        try {
            while (true) {
                val index = decoder.dequeueOutputBuffer(info, 0)
                when {
                    index >= 0 -> onOutputBuffer(decoder, index)
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onOutputFormat(decoder.outputFormat)
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "video output failure", e)
            releaseCodec()
        }
    }

    private fun onOutputBuffer(decoder: MediaCodec, index: Int) {
        if (codecSurface === placeholder) {
            // Off screen: keep only the newest picture so it can be shown when a surface arrives.
            if (heldOutputIndex >= 0) decoder.releaseOutputBuffer(heldOutputIndex, false)
            heldOutputIndex = index
            return
        }
        decoder.releaseOutputBuffer(index, true)
        reportFirstFrame()
    }

    private fun onOutputFormat(format: MediaFormat) {
        val w = if (format.containsKey(KEY_CROP_RIGHT) && format.containsKey(KEY_CROP_LEFT)) {
            format.getInteger(KEY_CROP_RIGHT) - format.getInteger(KEY_CROP_LEFT) + 1
        } else {
            format.getInteger(MediaFormat.KEY_WIDTH)
        }
        val h = if (format.containsKey(KEY_CROP_BOTTOM) && format.containsKey(KEY_CROP_TOP)) {
            format.getInteger(KEY_CROP_BOTTOM) - format.getInteger(KEY_CROP_TOP) + 1
        } else {
            format.getInteger(MediaFormat.KEY_HEIGHT)
        }
        Log.i(TAG, "output format ${w}x$h")
        if (w != width || h != height) {
            width = w
            height = h
            if (firstFrameReported) listener.onVideoSizeChanged(w, h)
        }
    }

    private fun reportFirstFrame() {
        if (firstFrameReported || width == 0 || height == 0) return
        firstFrameReported = true
        listener.onFirstFrameRendered(width, height)
    }

    private fun placeholderSurface(): Surface = placeholder ?: run {
        val texture = SurfaceTexture(false) // detached: never attached to a GL context
        placeholderTexture = texture
        Surface(texture).also { placeholder = it }
    }

    private fun releaseCodec() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        codecSurface = null
        codecConfig = null
        heldOutputIndex = -1
        awaitingConfig = true
        width = 0
        height = 0
        firstFrameReported = false
    }

    private companion object {
        const val TAG = "AirCastVideo"
        // H.264 mirroring is 1080p; H.265 is used for 4K (sized up front so 4K buffers are allocated).
        const val MAX_WIDTH_H264 = 1920
        const val MAX_HEIGHT_H264 = 1080
        const val MAX_WIDTH_H265 = 3840
        const val MAX_HEIGHT_H265 = 2160
        const val MAX_INPUT_SIZE = 4 * 1024 * 1024
        const val MAX_PENDING_BYTES = 32L * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val INPUT_ATTEMPTS = 20
        const val DRAIN_INTERVAL_MS = 5L
        const val IDLE_WAIT_MS = 200L
        const val STOP_TIMEOUT_MS = 2_000L
        const val KEY_CROP_LEFT = "crop-left"
        const val KEY_CROP_RIGHT = "crop-right"
        const val KEY_CROP_TOP = "crop-top"
        const val KEY_CROP_BOTTOM = "crop-bottom"
    }
}

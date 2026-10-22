package com.isaacbenavides.aircast.features.receiver.data.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays AirPlay audio: AAC-LC (ct=4) and AAC-ELD (ct=8, used while mirroring) are decoded with
 * MediaCodec; ALAC (ct=2) arrives already decoded to PCM by the native layer.
 */
internal class AudioPlayer {

    private sealed interface Command {
        class Configure(val compressionType: Int, val lowLatency: Boolean) : Command
        class Encoded(val data: ByteArray, val ptsUs: Long) : Command
        class Pcm(val data: ByteArray) : Command
        data object Flush : Command
        data object Release : Command
        data object Quit : Command
    }

    private val commands = LinkedBlockingQueue<Command>()
    private val queuedPackets = AtomicInteger()
    private var thread: Thread? = null

    @Volatile private var gain = 1f
    @Volatile private var track: AudioTrack? = null

    // Audio-thread state.
    private var decoder: MediaCodec? = null
    private var sampleRate = SAMPLE_RATE
    private var channels = CHANNELS
    private var lowLatency = false
    private var pcmScratch = ByteArray(0)

    fun start() {
        if (thread != null) return
        thread = Thread(::runLoop, "AirCastAudio").also { it.start() }
    }

    fun stop() {
        commands.clear()
        queuedPackets.set(0)
        commands.offer(Command.Quit)
        thread?.join(STOP_TIMEOUT_MS)
        thread = null
    }

    fun configure(compressionType: Int, lowLatency: Boolean) {
        commands.offer(Command.Configure(compressionType, lowLatency))
    }

    fun submitEncoded(data: ByteArray, ptsUs: Long) = enqueue(Command.Encoded(data, ptsUs))

    fun submitPcm(data: ByteArray) = enqueue(Command.Pcm(data))

    fun flush() {
        commands.removeIf { it is Command.Encoded || it is Command.Pcm }
        queuedPackets.set(0)
        commands.offer(Command.Flush)
    }

    /** Stop playback and free the decoder and audio track until the next stream. */
    fun release() {
        commands.removeIf { it is Command.Encoded || it is Command.Pcm }
        queuedPackets.set(0)
        commands.offer(Command.Release)
    }

    fun setGain(value: Float) {
        gain = value.coerceIn(0f, 1f)
        track?.setVolume(gain)
    }

    private fun enqueue(command: Command) {
        // Bound latency: if playback stalls, drop new packets instead of buffering seconds of audio.
        if (queuedPackets.get() >= MAX_QUEUED_PACKETS) return
        queuedPackets.incrementAndGet()
        commands.offer(command)
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val command = commands.poll(POLL_MS, TimeUnit.MILLISECONDS)
            try {
                when (command) {
                    null -> drainDecoder(info)
                    is Command.Configure -> configureStream(command)
                    is Command.Encoded -> {
                        queuedPackets.decrementAndGet()
                        decodeAndPlay(command, info)
                    }
                    is Command.Pcm -> {
                        queuedPackets.decrementAndGet()
                        write(command.data, command.data.size)
                    }
                    Command.Flush -> flushPlayback()
                    Command.Release -> releaseAll()
                    Command.Quit -> break
                }
            } catch (e: Exception) {
                Log.e(TAG, "audio failure", e)
                releaseAll()
            }
        }
        releaseAll()
    }

    private fun configureStream(command: Command.Configure) {
        releaseDecoder()
        lowLatency = command.lowLatency
        when (command.compressionType) {
            CT_AAC_LC -> decoder = createAacDecoder(AAC_LC_CONFIG, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            CT_AAC_ELD -> decoder = createAacDecoder(AAC_ELD_CONFIG, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
        }
        ensureTrack(SAMPLE_RATE, CHANNELS)
    }

    private fun createAacDecoder(config: ByteArray, profile: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(config))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
            setInteger(MediaFormat.KEY_AAC_PROFILE, profile)
        }
        // The platform software decoder (FDK) handles AAC-ELD; vendor DSP decoders often do not.
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_AUDIO_AAC, true) } }
            .map { it.name }
            .sortedBy { PREFERRED_AAC_DECODERS.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .firstOrNull()
        val codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, 0)
        codec.start()
        Log.i(TAG, "AAC decoder ${codec.name} (profile $profile)")
        return codec
    }

    private fun decodeAndPlay(command: Command.Encoded, info: MediaCodec.BufferInfo) {
        val codec = decoder ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) {
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.put(command.data, 0, minOf(command.data.size, buffer.capacity()))
            codec.queueInputBuffer(index, 0, buffer.position(), command.ptsUs, 0)
        } else {
            Log.w(TAG, "AAC decoder input full, packet dropped")
        }
        drainDecoder(info)
    }

    private fun drainDecoder(info: MediaCodec.BufferInfo) {
        val codec = decoder ?: return
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0) {
                        if (pcmScratch.size < info.size) pcmScratch = ByteArray(info.size)
                        output.position(info.offset)
                        output.get(pcmScratch, 0, info.size)
                        write(pcmScratch, info.size)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    ensureTrack(
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    )
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
    }

    private fun write(data: ByteArray, size: Int) {
        val audioTrack = track ?: ensureTrack(SAMPLE_RATE, CHANNELS)
        audioTrack.write(data, 0, size)
    }

    private fun ensureTrack(rate: Int, channelCount: Int): AudioTrack {
        val current = track
        if (current != null && rate == sampleRate && channelCount == channels) return current
        releaseTrack()
        sampleRate = rate
        channels = channelCount
        val mask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (lowLatency) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuffer * if (lowLatency) 2 else 4)
        if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val created = builder.build()
        created.setVolume(gain)
        created.play()
        track = created
        Log.i(TAG, "AudioTrack $rate Hz, $channelCount ch, lowLatency=$lowLatency")
        return created
    }

    private fun flushPlayback() {
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
        decoder?.flush()
    }

    private fun releaseDecoder() {
        decoder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        decoder = null
    }

    private fun releaseTrack() {
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
    }

    private fun releaseAll() {
        releaseDecoder()
        releaseTrack()
    }

    private companion object {
        const val TAG = "AirCastAudio"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
        const val MAX_QUEUED_PACKETS = 250
        const val INPUT_TIMEOUT_US = 10_000L
        const val POLL_MS = 10L
        const val STOP_TIMEOUT_MS = 2_000L

        // MPEG-4 AudioSpecificConfig, as in UxPlay's audio_renderer.c
        val AAC_LC_CONFIG = byteArrayOf(0x12, 0x10)
        val AAC_ELD_CONFIG = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)

        val PREFERRED_AAC_DECODERS = listOf("c2.android.aac.decoder", "OMX.google.aac.decoder")
    }
}

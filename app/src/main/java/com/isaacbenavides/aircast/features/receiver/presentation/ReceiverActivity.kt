package com.isaacbenavides.aircast.features.receiver.presentation

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.isaacbenavides.aircast.R
import com.isaacbenavides.aircast.core.di.ServiceLocator
import com.isaacbenavides.aircast.core.entitlement.Entitlement
import com.isaacbenavides.aircast.databinding.ActivityReceiverBinding
import com.isaacbenavides.aircast.features.receiver.domain.model.FreeSessionTime
import com.isaacbenavides.aircast.features.receiver.domain.model.NowPlaying
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverCapabilities
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverError
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverPhase
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverState
import com.isaacbenavides.aircast.features.receiver.domain.model.StreamPlayback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReceiverActivity : ComponentActivity() {

    private val viewModel: ReceiverViewModel by viewModels { ReceiverViewModel.Factory }
    private lateinit var binding: ActivityReceiverBinding

    /** Opened by the service because mirroring started: close again when it ends. */
    private var autoLaunched = false
    private var previousPhase: ReceiverPhase? = null
    private var previousFreeLimitReached: Boolean? = null
    private var previousClientId: String? = null
    private var shownArtwork: ByteArray? = null
    private var bannerJob: Job? = null
    private var progressJob: Job? = null
    private var playbackBarJob: Job? = null
    private var freeTimerJob: Job? = null
    private var loaderJob: Job? = null
    private var shownPlayback: StreamPlayback? = null
    /** Back on the audio player shows the home screen; the sound keeps playing. */
    private var audioPanelDismissed = false

    /** Back closes an AirPlay video, like Menu on an Apple TV; otherwise the default applies. */
    private val stopStreamOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = viewModel.stopStream()
    }

    private val hideAudioPanelOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            audioPanelDismissed = true
            render(viewModel.state.value)
            binding.proButton.requestFocus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        autoLaunched = intent.getBooleanExtra(EXTRA_AUTO_LAUNCHED, false)
        onBackPressedDispatcher.addCallback(this, stopStreamOnBack)
        onBackPressedDispatcher.addCallback(this, hideAudioPanelOnBack)

        binding.videoSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = viewModel.onSurfaceAvailable(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = viewModel.onSurfaceDestroyed()
        })

        binding.proButton.setOnClickListener { ServiceLocator.navigator.openPro(this) }
        binding.licensesButton.setOnClickListener { ServiceLocator.navigator.openLicenses(this) }
        binding.overlayPermissionButton.setOnClickListener { openOverlaySettings() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                viewModel.entitlement.collect { plan ->
                    binding.proButton.setText(
                        if (plan == Entitlement.PRO) R.string.pro_button_active else R.string.pro_button_get,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Started while visible so TCL's boot manager lets the service go foreground.
        ReceiverService.start(this)
        // Back from Google Play or the settings screen.
        viewModel.refreshEntitlement()
        binding.overlayPermissionButton.isVisible = !Settings.canDrawOverlays(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Only close on mirroring end if the service brought us up from the background,
        // not when the user already had the app open (onNewIntent precedes onRestart/onStart).
        val wasInBackground = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (intent.getBooleanExtra(EXTRA_AUTO_LAUNCHED, false) && wasInBackground) autoLaunched = true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (viewModel.state.value.phase != ReceiverPhase.STREAMING) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> if (event.repeatCount == 0) viewModel.togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> viewModel.setPaused(false)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> viewModel.setPaused(true)
            KeyEvent.KEYCODE_DPAD_LEFT -> viewModel.seekBy(-SEEK_STEP_MS)
            KeyEvent.KEYCODE_DPAD_RIGHT -> viewModel.seekBy(SEEK_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> viewModel.seekBy(-SEEK_LONG_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> viewModel.seekBy(SEEK_LONG_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                viewModel.stopStream()
                return true
            }
            // Only bring up the controls.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> Unit
            else -> return super.onKeyDown(keyCode, event)
        }
        showPlaybackBar()
        return true
    }

    private fun render(state: ReceiverState) {
        val videoPhase = state.phase.showsVideo()
        val showingVideo = videoPhase && state.videoSize != null
        val playingAudio = state.phase == ReceiverPhase.PLAYING_AUDIO
        val streaming = state.phase == ReceiverPhase.STREAMING
        // Shown again for the next audio session.
        if (!playingAudio) audioPanelDismissed = false
        val showAudioPanel = playingAudio && !showingVideo && !audioPanelDismissed

        binding.videoContainer.aspectRatio = state.videoSize?.aspectRatio ?: 0f
        binding.idlePanel.isVisible = !showingVideo && !showAudioPanel
        binding.audioPanel.isVisible = showAudioPanel

        renderIdle(state)
        if (playingAudio) renderNowPlaying(state)
        renderPlayback(state)
        renderLoader(state)
        renderFreeTimer(state, showingVideo)
        if (playingAudio || streaming) startProgressTicker() else stopProgressTicker()
        renderBanner(state)
        stopStreamOnBack.isEnabled = streaming
        hideAudioPanelOnBack.isEnabled = showAudioPanel

        val keepAwake = videoPhase || playingAudio
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // The free session just ran out: say so and offer Pro right away.
        if (state.freeLimitReached && previousFreeLimitReached == false) {
            ServiceLocator.navigator.openPro(this, limitReached = true)
        }
        previousFreeLimitReached = state.freeLimitReached

        val videoEnded = previousPhase?.showsVideo() == true && !videoPhase
        previousPhase = state.phase
        if (videoEnded && autoLaunched) {
            autoLaunched = false
            // Stay (under the Pro screen) to explain why it stopped, with Get Pro ready to press.
            if (state.freeLimitReached) binding.proButton.requestFocus() else finish()
        }
    }

    private fun renderIdle(state: ReceiverState) {
        val name = state.receiverName.ifBlank { getString(R.string.app_name) }
        val client = clientName(state)
        binding.receiverName.text = name
        binding.stepChoose.text = getString(R.string.step_choose, name)
        binding.networkInfo.text = when {
            state.ipAddress == null -> getString(R.string.network_none)
            state.port > 0 -> getString(R.string.network_info, state.ipAddress, state.port)
            else -> state.ipAddress
        }
        // During a free session the plan line shows the time left (renderFreeTimer).
        if (state.freeTime == null) {
            binding.planInfo.setText(
                if (state.capabilities == ReceiverCapabilities.PRO) R.string.plan_pro else R.string.plan_free,
            )
        }
        val (text, dot) = when (state.phase) {
            ReceiverPhase.STARTING -> getString(R.string.status_starting) to R.color.warning
            ReceiverPhase.READY ->
                if (state.freeLimitReached) {
                    getString(R.string.status_free_limit) to R.color.warning
                } else {
                    getString(R.string.status_ready) to R.color.success
                }
            ReceiverPhase.CONNECTED -> getString(R.string.status_connected, client) to R.color.accent
            ReceiverPhase.MIRRORING -> getString(R.string.status_mirroring, client) to R.color.accent
            ReceiverPhase.STREAMING -> getString(R.string.status_streaming, client) to R.color.accent
            ReceiverPhase.PLAYING_AUDIO -> getString(R.string.status_audio, client) to R.color.accent
            ReceiverPhase.ERROR -> errorText(state.error) to R.color.error
        }
        binding.statusPill.text = text
        binding.statusPill.compoundDrawableTintList = colorList(dot)
    }

    private fun renderNowPlaying(state: ReceiverState) {
        val nowPlaying = state.nowPlaying ?: NowPlaying()
        binding.audioSource.text = getString(R.string.audio_source, clientName(state, sentenceStart = true))
        binding.trackTitle.text = nowPlaying.title ?: getString(R.string.track_unknown)
        binding.trackArtist.text = nowPlaying.artist.orEmpty()
        binding.trackAlbum.text = nowPlaying.album.orEmpty()
        if (nowPlaying.artwork !== shownArtwork) {
            shownArtwork = nowPlaying.artwork
            val bitmap = nowPlaying.artwork?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bitmap != null) {
                binding.artwork.setImageBitmap(bitmap)
                binding.artwork.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            } else {
                binding.artwork.setImageResource(R.drawable.ic_brand)
                binding.artwork.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            }
            binding.artwork.clipToOutline = true
        }
    }

    private fun renderPlayback(state: ReceiverState) {
        // Before the first frame the loader says what is going on.
        val playback = state.playback.takeIf { state.phase == ReceiverPhase.STREAMING && state.videoSize != null }
        if (playback == null) {
            playbackBarJob?.cancel()
            binding.playbackBar.isVisible = false
            shownPlayback = null
            return
        }
        binding.playbackIcon.setImageResource(if (playback.paused) R.drawable.ic_play else R.drawable.ic_pause)
        binding.playbackTitle.text = getString(R.string.status_streaming, clientName(state))
        // Every report is a start, pause, seek or buffering change: worth showing the controls.
        if (playback != shownPlayback) {
            shownPlayback = playback
            showPlaybackBar()
        }
        updatePlaybackProgress(playback)
    }

    /**
     * Full-screen loader while an AirPlay video loads: at once before the first frame, and after a
     * moment when it rebuffers, so quick seeks don't flash it.
     */
    private fun renderLoader(state: ReceiverState) {
        val playback = state.playback.takeIf { state.phase == ReceiverPhase.STREAMING }
        if (playback?.buffering != true) {
            loaderJob?.cancel()
            loaderJob = null
            binding.streamLoader.isVisible = false
            return
        }
        binding.streamLoaderText.text = getString(R.string.stream_loading, clientName(state))
        if (binding.streamLoader.isVisible || loaderJob?.isActive == true) return
        if (state.videoSize == null) {
            binding.streamLoader.isVisible = true
        } else {
            loaderJob = lifecycleScope.launch {
                delay(REBUFFER_LOADER_DELAY_MS)
                binding.streamLoader.isVisible = true
            }
        }
    }

    /** Free version: time left in the session, over the video and in the home screen's plan line. */
    private fun renderFreeTimer(state: ReceiverState, showingVideo: Boolean) {
        val freeTime = state.freeTime
        binding.freeTimer.isVisible = freeTime != null && showingVideo
        if (freeTime != null) showFreeTimeLeft(freeTime)
        if (freeTime?.running != true) {
            freeTimerJob?.cancel()
            freeTimerJob = null
            return
        }
        if (freeTimerJob?.isActive == true) return
        freeTimerJob = lifecycleScope.launch {
            while (isActive) {
                val running = viewModel.state.value.freeTime?.takeIf { it.running } ?: break
                val leftMs = showFreeTimeLeft(running)
                // Wake up when the shown second changes.
                delay((leftMs % 1000).takeIf { it > 0 } ?: 1000)
            }
        }
    }

    /** Writes the time left and returns it. */
    private fun showFreeTimeLeft(freeTime: FreeSessionTime): Long {
        val leftMs = freeTime.leftAt(SystemClock.elapsedRealtime())
        // Rounded up, so 0:00 only shows when the time is really over.
        val time = formatTime((leftMs + 999) / 1000 * 1000)
        binding.freeTimer.text = getString(R.string.free_timer, time)
        binding.freeTimer.setTextColor(
            ContextCompat.getColor(this, if (leftMs < FREE_TIMER_WARNING_MS) R.color.warning else R.color.text_primary),
        )
        binding.planInfo.text = getString(R.string.plan_free_remaining, time)
        return leftMs
    }

    /** Shows the controls; they hide on their own only while the video is actually playing. */
    private fun showPlaybackBar() {
        binding.playbackBar.isVisible = true
        playbackBarJob?.cancel()
        val playback = viewModel.state.value.playback
        if (playback == null || playback.paused || playback.buffering) return
        playbackBarJob = lifecycleScope.launch {
            delay(PLAYBACK_BAR_TIMEOUT_MS)
            binding.playbackBar.isVisible = false
        }
    }

    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return
        progressJob = lifecycleScope.launch {
            while (isActive) {
                val state = viewModel.state.value
                updateProgress(state.nowPlaying)
                state.playback?.let(::updatePlaybackProgress)
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updateProgress(nowPlaying: NowPlaying?) {
        val duration = nowPlaying?.durationMs?.takeIf { it > 0 }
        val reported = nowPlaying?.positionMs
        binding.trackProgress.isVisible = duration != null
        binding.trackElapsed.isVisible = duration != null
        binding.trackDuration.isVisible = duration != null
        if (duration == null || reported == null) return
        val elapsed = (reported + SystemClock.elapsedRealtime() - nowPlaying.positionCapturedAtMs).coerceIn(0, duration)
        binding.trackProgress.progress = (elapsed * 1000 / duration).toInt()
        binding.trackElapsed.text = formatTime(elapsed)
        binding.trackDuration.text = formatTime(duration)
    }

    private fun updatePlaybackProgress(playback: StreamPlayback) {
        val duration = playback.durationMs.takeIf { it > 0 }
        val running = !playback.paused && !playback.buffering
        val advanced = if (running) SystemClock.elapsedRealtime() - playback.positionCapturedAtMs else 0
        val elapsed = (playback.positionMs + advanced).let { if (duration != null) it.coerceIn(0, duration) else it }
        binding.playbackProgress.isVisible = duration != null
        binding.playbackDuration.isVisible = duration != null
        binding.playbackElapsed.text = formatTime(elapsed)
        if (duration == null) return
        binding.playbackProgress.progress = (elapsed * 1000 / duration).toInt()
        binding.playbackDuration.text = formatTime(duration)
    }

    private fun renderBanner(state: ReceiverState) {
        val client = state.client
        if (client != null && client.deviceId != previousClientId) {
            binding.connectionBanner.text = getString(R.string.banner_connecting, clientName(state, sentenceStart = true))
            binding.connectionBanner.visibility = View.VISIBLE
            bannerJob?.cancel()
            bannerJob = lifecycleScope.launch {
                delay(BANNER_DURATION_MS)
                binding.connectionBanner.visibility = View.GONE
            }
        }
        previousClientId = client?.deviceId
    }

    private fun openOverlaySettings() {
        val forThisApp = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        runCatching { startActivity(forThisApp) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    /** The name the device gave itself (e.g. "Isaac's iPhone"), or a generic one if it sent none. */
    private fun clientName(state: ReceiverState, sentenceStart: Boolean = false): String =
        state.client?.name?.takeIf { it.isNotBlank() }
            ?: getString(if (sentenceStart) R.string.client_unknown_start else R.string.client_unknown)

    private fun errorText(error: ReceiverError?): String = getString(
        when (error) {
            ReceiverError.NETWORK_DISCOVERY -> R.string.status_error_discovery
            ReceiverError.SERVER -> R.string.status_error_server
            ReceiverError.UNKNOWN, null -> R.string.status_error_unknown
        },
    )

    private fun ReceiverPhase.showsVideo() = this == ReceiverPhase.MIRRORING || this == ReceiverPhase.STREAMING

    private fun colorList(@ColorRes color: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, color))

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = totalSeconds / 60 % 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    companion object {
        const val EXTRA_AUTO_LAUNCHED = "auto_launched"
        private const val BANNER_DURATION_MS = 3_000L
        private const val PROGRESS_TICK_MS = 500L
        private const val PLAYBACK_BAR_TIMEOUT_MS = 4_000L
        private const val SEEK_STEP_MS = 10_000L
        private const val SEEK_LONG_STEP_MS = 30_000L
        private const val FREE_TIMER_WARNING_MS = 60_000L
        private const val REBUFFER_LOADER_DELAY_MS = 500L
    }
}

package com.isaacbenavides.aircast.features.receiver.domain.repository

import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverState
import kotlinx.coroutines.flow.StateFlow

/** Opaque render target supplied by the platform layer (an Android Surface on Android). */
interface RenderSurface

interface AirPlayReceiverRepository {
    val state: StateFlow<ReceiverState>

    /** Starts advertising the receiver and accepting AirPlay clients. Blocking; call off the main thread. */
    fun start()

    /** Disconnects clients and stops advertising. Blocking; call off the main thread. */
    fun stop()

    /** Restarts the server, e.g. after the TV's IP address changed. Blocking. */
    fun restart()

    /**
     * Restarts the server if the user's plan (free or Pro) changed, so clients see the new
     * capabilities. Waits for a moment when nobody is connected: call again on phase changes. Blocking.
     */
    fun applyEntitlement()

    /** Where mirrored video is drawn; null while nothing is on screen (decoding continues off-screen). */
    fun attachRenderSurface(surface: RenderSurface?)

    // AirPlay video controls from the TV remote; ignored unless an AirPlay video is playing.
    // The client follows along on its next status poll.

    fun setStreamPaused(paused: Boolean)

    fun seekStreamBy(deltaMs: Long)

    /** Closes the AirPlay video right away and ends the client's session, like Menu on an Apple TV. Blocking. */
    fun stopStream()
}

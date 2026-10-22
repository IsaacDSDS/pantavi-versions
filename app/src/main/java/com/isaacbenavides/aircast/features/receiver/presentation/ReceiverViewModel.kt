package com.isaacbenavides.aircast.features.receiver.presentation

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.isaacbenavides.aircast.core.di.ServiceLocator
import com.isaacbenavides.aircast.core.entitlement.Entitlement
import com.isaacbenavides.aircast.core.entitlement.EntitlementRepository
import com.isaacbenavides.aircast.core.media.AndroidRenderSurface
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverState
import com.isaacbenavides.aircast.features.receiver.domain.repository.AirPlayReceiverRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ReceiverViewModel(
    private val repository: AirPlayReceiverRepository,
    private val entitlements: EntitlementRepository,
) : ViewModel() {

    val state: StateFlow<ReceiverState> = repository.state

    val entitlement: StateFlow<Entitlement> = entitlements.entitlement

    fun refreshEntitlement() = entitlements.refresh()

    fun onSurfaceAvailable(surface: Surface) = repository.attachRenderSurface(AndroidRenderSurface(surface))

    fun onSurfaceDestroyed() = repository.attachRenderSurface(null)

    fun togglePause() = repository.setStreamPaused(state.value.playback?.paused != true)

    fun setPaused(paused: Boolean) = repository.setStreamPaused(paused)

    fun seekBy(deltaMs: Long) = repository.seekStreamBy(deltaMs)

    /** The player and the screen close as soon as the release is done. */
    fun stopStream() {
        viewModelScope.launch(Dispatchers.IO) { repository.stopStream() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { ReceiverViewModel(ServiceLocator.receiverRepository, ServiceLocator.entitlementRepository) }
        }
    }
}

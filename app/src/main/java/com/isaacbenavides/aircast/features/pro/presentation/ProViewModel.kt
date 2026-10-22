package com.isaacbenavides.aircast.features.pro.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.isaacbenavides.aircast.core.di.ServiceLocator
import com.isaacbenavides.aircast.core.entitlement.Entitlement
import com.isaacbenavides.aircast.core.entitlement.EntitlementRepository
import kotlinx.coroutines.flow.StateFlow

class ProViewModel(private val entitlements: EntitlementRepository) : ViewModel() {

    val entitlement: StateFlow<Entitlement> = entitlements.entitlement

    fun refresh() = entitlements.refresh()

    companion object {
        val Factory = viewModelFactory {
            initializer { ProViewModel(ServiceLocator.entitlementRepository) }
        }
    }
}

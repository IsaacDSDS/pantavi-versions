package com.isaacbenavides.aircast.core.entitlement

import kotlinx.coroutines.flow.StateFlow

/** What the user has unlocked. Pro comes from the separate, paid "Pantavi Pro" key app. */
enum class Entitlement { FREE, PRO }

/** Package of the Pantavi Pro key app on Google Play. */
const val PRO_KEY_PACKAGE = "com.isaacbenavides.pantavi.pro"

interface EntitlementRepository {
    val entitlement: StateFlow<Entitlement>

    /** Checks again, e.g. when the user comes back from the store. */
    fun refresh()
}

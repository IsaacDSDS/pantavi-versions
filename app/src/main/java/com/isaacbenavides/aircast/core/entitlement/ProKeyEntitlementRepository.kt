package com.isaacbenavides.aircast.core.entitlement

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pro is unlocked while the Pantavi Pro key app is installed and signed with the same key as
 * this app (a paid app on Google Play, so this app needs no billing library). Package changes are
 * watched while the process lives; nothing is declared in the manifest, so nothing starts the app.
 */
class ProKeyEntitlementRepository(private val context: Context) : EntitlementRepository {

    private val _entitlement = MutableStateFlow(check())
    override val entitlement: StateFlow<Entitlement> = _entitlement.asStateFlow()

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.data?.schemeSpecificPart == PRO_KEY_PACKAGE) refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, packageChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun refresh() {
        val current = check()
        if (_entitlement.value != current) Log.i(TAG, "entitlement: $current")
        _entitlement.value = current
    }

    private fun check(): Entitlement {
        // Needs <queries> for the key package in the manifest (package visibility).
        val match = context.packageManager.checkSignatures(context.packageName, PRO_KEY_PACKAGE)
        return if (match == PackageManager.SIGNATURE_MATCH) Entitlement.PRO else Entitlement.FREE
    }

    private companion object {
        const val TAG = "AirCastEntitlement"
    }
}

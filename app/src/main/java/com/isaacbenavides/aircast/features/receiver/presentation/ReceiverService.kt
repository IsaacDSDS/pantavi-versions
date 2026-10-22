package com.isaacbenavides.aircast.features.receiver.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.isaacbenavides.aircast.R
import com.isaacbenavides.aircast.core.di.ServiceLocator
import com.isaacbenavides.aircast.features.receiver.domain.model.ReceiverPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Keeps the AirPlay receiver running (and discoverable) while the TV is on, independently of
 * the UI, and brings the mirroring screen to the front when a client starts mirroring.
 */
class ReceiverService : LifecycleService() {

    private val repository by lazy { ServiceLocator.receiverRepository }
    private val networkAddresses by lazy { ServiceLocator.networkAddressProvider }
    private val entitlements by lazy { ServiceLocator.entitlementRepository }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "AirCastService") }
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastAddress: String? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onNetworkChanged()
        override fun onLost(network: Network) = onNetworkChanged()
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        // mDNS needs multicast reception (no-op on Ethernet, required on Wi-Fi).
        multicastLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("aircast-mdns")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        lastAddress = networkAddresses.currentIpv4()
        worker.execute { repository.start() }

        lifecycleScope.launch {
            repository.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == ReceiverPhase.MIRRORING || phase == ReceiverPhase.STREAMING) showMirrorScreen()
            }
        }
        // Free <-> Pro: restart with the new capabilities as soon as nobody is connected.
        lifecycleScope.launch {
            combine(entitlements.entitlement, repository.state.map { it.phase }) { plan, phase -> plan to phase }
                .distinctUntilChanged()
                .collect { worker.execute { repository.applyEntitlement() } }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // TCL firmware (tcl-service.jar, TclAppBootManagerImpl.canStartForegroundGo) only lets
        // third-party apps go foreground while one of their activities is on screen, so every
        // start request coming from a visible activity retries the promotion.
        promoteToForeground()
        // Only runs once the user opens the app: not brought back by the system if it is killed.
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        worker.execute { repository.stop() }
        worker.shutdown()
        multicastLock?.release()
        super.onDestroy()
    }

    private fun onNetworkChanged() {
        val address = networkAddresses.currentIpv4()
        if (address == lastAddress) return
        Log.i(TAG, "network address changed $lastAddress -> $address, restarting receiver")
        lastAddress = address
        if (address != null) worker.execute { repository.restart() }
    }

    private fun showMirrorScreen() {
        // Wake the TV from the screensaver (a dream would otherwise cover the video).
        // Same screen for mirroring and AirPlay video.
        @Suppress("DEPRECATION")
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "aircast:mirror")
            .acquire(WAKE_DURATION_MS)
        // Allowed from the background once the app may draw overlays (granted via adb appops).
        val intent = Intent(this, ReceiverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ReceiverActivity.EXTRA_AUTO_LAUNCHED, true)
        runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "could not open mirror screen", it) }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "AirCastService"
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_DURATION_MS = 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReceiverService::class.java))
        }
    }
}

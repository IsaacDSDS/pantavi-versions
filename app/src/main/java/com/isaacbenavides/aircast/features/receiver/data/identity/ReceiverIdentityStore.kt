package com.isaacbenavides.aircast.features.receiver.data.identity

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import java.io.File
import java.security.SecureRandom

/**
 * Stable identity of this receiver. Apple devices remember receivers by device ID (a MAC address)
 * and Ed25519 public key, so both must survive restarts. Android hides the real MAC, so a
 * locally-administered one is generated once.
 */
class ReceiverIdentityStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("receiver_identity", Context.MODE_PRIVATE)

    fun receiverName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL

    fun macAddress(): String = prefs.getString(KEY_MAC, null) ?: generateMac().also { mac ->
        prefs.edit { putString(KEY_MAC, mac) }
    }

    /** mDNS host label (".local" is appended natively); must be unique on the LAN. */
    fun hostLabel(): String = "AirCast-" + macAddress().replace(":", "").takeLast(6).uppercase()

    fun keyFilePath(): String = File(context.filesDir, "airplay_pairing_key.pem").absolutePath

    private fun generateMac(): String {
        val bytes = ByteArray(6).also { SecureRandom().nextBytes(it) }
        bytes[0] = ((bytes[0].toInt() and 0xFE) or 0x02).toByte() // unicast, locally administered
        return bytes.joinToString(":") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_MAC = "mac_address"
    }
}

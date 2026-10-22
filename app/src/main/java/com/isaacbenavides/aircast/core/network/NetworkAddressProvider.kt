package com.isaacbenavides.aircast.core.network

import java.net.Inet4Address
import java.net.NetworkInterface

class NetworkAddressProvider {

    /** IPv4 address of the active LAN interface, preferring Ethernet over Wi-Fi. */
    fun currentIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { iface ->
                when {
                    iface.name.startsWith("eth") -> 0
                    iface.name.startsWith("wlan") -> 1
                    else -> 2
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

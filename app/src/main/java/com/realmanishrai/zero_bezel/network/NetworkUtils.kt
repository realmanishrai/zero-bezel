package com.realmanishrai.zero_bezel.network

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalWifiIpv4Address(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val wifiAddress = interfaces
            .asSequence()
            .filter { it.isUp && !it.isLoopback && it.name.contains("wlan", ignoreCase = true) }
            .mapNotNull { it.firstIpv4AddressOrNull() }
            .firstOrNull()

        return wifiAddress ?: interfaces
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { it.firstIpv4AddressOrNull() }
            .firstOrNull()
    }

    private fun NetworkInterface.firstIpv4AddressOrNull(): String? {
        return inetAddresses
            .toList()
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}

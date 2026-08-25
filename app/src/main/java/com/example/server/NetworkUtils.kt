package com.example.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun getLocalIpAddress(context: Context? = null): String {
        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = cm.activeNetwork
                if (activeNetwork != null) {
                    val caps = cm.getNetworkCapabilities(activeNetwork)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        val linkProps = cm.getLinkProperties(activeNetwork)
                        if (linkProps != null) {
                            for (linkAddr in linkProps.linkAddresses) {
                                val addr = linkAddr.address
                                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                                    return addr.hostAddress ?: "Unknown"
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback to legacy method
            }
        }

        try {
            // Fallback: search for wlan0 or other active IPv4
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val interfaceList = interfaces.toList()
            
            // Prefer wlan0
            val wlan = interfaceList.find { it.name.contains("wlan", ignoreCase = true) && it.isUp && !it.isLoopback }
            if (wlan != null) {
                val addr = wlan.inetAddresses.toList().find { it is Inet4Address && !it.isLoopbackAddress }
                if (addr != null) return addr.hostAddress ?: "Unknown"
            }

            for (networkInterface in interfaceList) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                // Skip common VPN/Mobile interface names if possible, but we don't know them all
                if (networkInterface.name.contains("tun") || networkInterface.name.contains("ppp") || networkInterface.name.contains("rmnet")) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "Not connected"
    }
}

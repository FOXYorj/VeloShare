package com.example

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

class NsdHelper(private val context: Context) {
    private val TAG = "NsdHelper"
    private val SERVICE_TYPE = "_veloshare._tcp."
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredPeers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, Peer>> = _discoveredPeers.asStateFlow()

    private var localServiceName: String? = null

    init {
        // Automatically prune stale peers in real application
    }

    companion object {
        fun getLocalIpAddress(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val networkInterface = interfaces.nextElement()
                    // Skip virtual or down interfaces
                    if (networkInterface.isLoopback || !networkInterface.isUp) continue
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            val ip = address.hostAddress ?: ""
                            if (ip.isNotEmpty() && !ip.startsWith("127.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e("NsdHelper", "Error getting local IP", ex)
            }
            return "127.0.0.1"
        }
    }

    fun registerService(deviceName: String, port: Int) {
        stopRegistration()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "VS_$deviceName"
            serviceType = SERVICE_TYPE
            setPort(port)
            // Add attributes to TXT record for advanced identification if supported
            setAttribute("os", "Android")
            setAttribute("device_model", android.os.Build.MODEL)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                localServiceName = NsdServiceInfo.serviceName
                Log.d(TAG, "Service registered successfully: $localServiceName")
            }

            override fun onRegistrationFailed(NsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: errorCode $errorCode")
            }

            override fun onServiceUnregistered(NsdServiceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered: ${NsdServiceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(NsdServiceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: errorCode $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error during registerService", e)
        }
    }

    fun discoverServices() {
        stopDiscovery()
        _discoveredPeers.value = emptyMap()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: Error code:$errorCode")
                nsdManager.stopServiceDiscovery(this)
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Service discovery stopped")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName} Typ: ${serviceInfo.serviceType}")
                if (serviceInfo.serviceType != SERVICE_TYPE) {
                    return
                }
                if (serviceInfo.serviceName == localServiceName) {
                    Log.d(TAG, "Skipping our own service: ${serviceInfo.serviceName}")
                    return
                }

                // Resolve service to get IP and Port
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                val peerId = serviceInfo.serviceName
                _discoveredPeers.value = _discoveredPeers.value.toMutableMap().apply {
                    remove(peerId)
                }
            }
        }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service discovery", e)
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed: Error code:$errorCode for ${serviceInfo.serviceName}")
            }

            override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                val host = resolvedServiceInfo.host
                val ip = host?.hostAddress ?: ""
                val port = resolvedServiceInfo.port
                val rawName = resolvedServiceInfo.serviceName
                val cleanName = if (rawName.startsWith("VS_")) rawName.substring(3) else rawName
                
                // Get attributes if available
                val osAttr = try {
                    val bytes = resolvedServiceInfo.attributes["os"]
                    if (bytes != null) String(bytes) else "Android"
                } catch (e: Exception) {
                    "Android"
                }

                Log.d(TAG, "Service resolved: $cleanName ($ip:$port) OS: $osAttr")

                if (ip.isNotEmpty() && ip != "127.0.0.1") {
                    val peer = Peer(
                        id = rawName,
                        name = cleanName,
                        ip = ip,
                        port = port,
                        os = osAttr
                    )
                    _discoveredPeers.value = _discoveredPeers.value.toMutableMap().apply {
                        put(rawName, peer)
                    }
                }
            }
        })
    }

    fun stopDiscovery() {
        discoveryListener?.let {
            try {
                nsdManager.stopServiceDiscovery(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
    }

    fun stopRegistration() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering service", e)
            }
        }
        registrationListener = null
    }

    fun cleanup() {
        stopDiscovery()
        stopRegistration()
    }
}

data class Peer(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    val os: String = "Android",
    val lastSeen: Long = System.currentTimeMillis()
)

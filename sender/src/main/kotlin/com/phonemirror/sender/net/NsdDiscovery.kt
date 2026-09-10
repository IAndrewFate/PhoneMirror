package com.phonemirror.sender.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class NsdDiscovery(private val context: Context) {

    fun discover(): Flow<List<DiscoveredDevice>> = callbackFlow {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("PhoneMirrorSenderMulticast")?.apply {
            setReferenceCounted(true)
            acquire()
        }

        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val discoveredMap = ConcurrentHashMap<String, DiscoveredDevice>()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                trySend(emptyList())
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_phonemirror")) {
                    resolveService(serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                discoveredMap.remove(serviceInfo.serviceName)
                trySend(discoveredMap.values.toList())
            }

            private fun resolveService(serviceInfo: NsdServiceInfo) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        nsdManager?.registerServiceInfoCallback(
                            serviceInfo,
                            context.mainExecutor,
                            object : NsdManager.ServiceInfoCallback {
                                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                                    val host = resolved.host?.hostAddress ?: return
                                    val dev = DiscoveredDevice(resolved.serviceName, host, resolved.port)
                                    discoveredMap[resolved.serviceName] = dev
                                    trySend(discoveredMap.values.toList())
                                }
                                override fun onServiceLost() {
                                    discoveredMap.remove(serviceInfo.serviceName)
                                    trySend(discoveredMap.values.toList())
                                }
                                override fun onServiceInfoCallbackUnregistered() {}
                            }
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        nsdManager?.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    val host = resolved.host?.hostAddress ?: return
                                    val dev = DiscoveredDevice(resolved.serviceName, host, resolved.port)
                                    discoveredMap[resolved.serviceName] = dev
                                    trySend(discoveredMap.values.toList())
                                }
                            }
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        try {
            nsdManager?.discoverServices("_phonemirror._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (_: Exception) {
            trySend(emptyList())
        }

        awaitClose {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {}
            try {
                multicastLock?.release()
            } catch (_: Exception) {}
        }
    }
}
package com.phonemirror.receiver.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build

object ReceiverWifiLockHelper {

    /**
     * Determines the appropriate WifiLock mode based on Android SDK level:
     * - SDK >= 29 (Android 10+): WIFI_MODE_FULL_LOW_LATENCY (value = 4)
     * - SDK < 29: WIFI_MODE_FULL_HIGH_PERF (value = 3)
     */
    fun getWifiLockMode(sdkInt: Int = Build.VERSION.SDK_INT): Int {
        return if (sdkInt >= 29) { // Build.VERSION_CODES.Q
            4 // WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            3 // WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
    }

    fun acquireWifiLock(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): WifiManager.WifiLock? {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = getWifiLockMode(sdkInt)
            wm?.createWifiLock(mode, "PhoneMirrorReceiver:StreamingLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }
    }
}

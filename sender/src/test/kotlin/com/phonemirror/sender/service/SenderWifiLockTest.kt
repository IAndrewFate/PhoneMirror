package com.phonemirror.sender.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SenderWifiLockTest {

    @Test
    fun testWifiLockModeMatrix() {
        // API 29+ -> WIFI_MODE_FULL_LOW_LATENCY = 4
        assertThat(SenderWifiLockHelper.getWifiLockMode(29)).isEqualTo(4)
        assertThat(SenderWifiLockHelper.getWifiLockMode(30)).isEqualTo(4)
        assertThat(SenderWifiLockHelper.getWifiLockMode(33)).isEqualTo(4)
        assertThat(SenderWifiLockHelper.getWifiLockMode(34)).isEqualTo(4)
        assertThat(SenderWifiLockHelper.getWifiLockMode(35)).isEqualTo(4)

        // API < 29 -> WIFI_MODE_FULL_HIGH_PERF = 3
        assertThat(SenderWifiLockHelper.getWifiLockMode(26)).isEqualTo(3)
        assertThat(SenderWifiLockHelper.getWifiLockMode(27)).isEqualTo(3)
        assertThat(SenderWifiLockHelper.getWifiLockMode(28)).isEqualTo(3)
    }
}

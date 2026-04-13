package com.foreverjukebox.app.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDisconnectAutoPausePolicyTest {

    @Test
    fun bluetoothOutputDeviceTypesAreRecognized() {
        assertTrue(isBluetoothOutputDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(isBluetoothOutputDeviceType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(isBluetoothOutputDeviceType(AudioDeviceInfo.TYPE_HEARING_AID))
        assertTrue(isBluetoothOutputDeviceType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertTrue(isBluetoothOutputDeviceType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertTrue(isBluetoothOutputDeviceType(AudioDeviceInfo.TYPE_BLE_BROADCAST))
        assertFalse(isBluetoothOutputDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertFalse(isBluetoothOutputDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }

    @Test
    fun recentBluetoothDisconnectRequiresTimestampWithinWindow() {
        assertFalse(
            hasRecentBluetoothDisconnect(
                nowElapsedMs = 1_000L,
                disconnectElapsedMs = null,
                windowMs = 3_000L
            )
        )
        assertFalse(
            hasRecentBluetoothDisconnect(
                nowElapsedMs = 10_000L,
                disconnectElapsedMs = 6_500L,
                windowMs = 3_000L
            )
        )
        assertFalse(
            hasRecentBluetoothDisconnect(
                nowElapsedMs = 4_000L,
                disconnectElapsedMs = 5_000L,
                windowMs = 3_000L
            )
        )
        assertTrue(
            hasRecentBluetoothDisconnect(
                nowElapsedMs = 10_000L,
                disconnectElapsedMs = 7_500L,
                windowMs = 3_000L
            )
        )
    }

    @Test
    fun autoPauseOnlyTriggersForLocalRunningPlaybackWithRecentDisconnect() {
        assertTrue(
            shouldAutoPauseForBluetoothDisconnect(
                isLocalPlayback = true,
                isPlaybackRunning = true,
                hasRecentBluetoothDisconnect = true
            )
        )
        assertFalse(
            shouldAutoPauseForBluetoothDisconnect(
                isLocalPlayback = false,
                isPlaybackRunning = true,
                hasRecentBluetoothDisconnect = true
            )
        )
        assertFalse(
            shouldAutoPauseForBluetoothDisconnect(
                isLocalPlayback = true,
                isPlaybackRunning = false,
                hasRecentBluetoothDisconnect = true
            )
        )
        assertFalse(
            shouldAutoPauseForBluetoothDisconnect(
                isLocalPlayback = true,
                isPlaybackRunning = true,
                hasRecentBluetoothDisconnect = false
            )
        )
    }
}

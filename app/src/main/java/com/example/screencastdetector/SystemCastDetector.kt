package com.example.screencastdetector

import android.content.Context
import android.hardware.display.DisplayManager

/**
 * Reads OEM/system cast indicators that do not create a visible secondary display.
 */
object SystemCastDetector {
    data class DebugState(
        val wifiDisplaySettingOn: Boolean?,
        val wifiDisplayStatusActive: Boolean,
        val castSettingKeysChecked: List<String>,
        val systemCastActive: Boolean,
    )

    @Volatile
    private var lastSystemCastActive = false

    fun refreshState(context: Context) {
        lastSystemCastActive = probe(context).systemCastActive
    }

    fun isActive(): Boolean = lastSystemCastActive

    fun getDebugState(context: Context): DebugState = probe(context)

    private fun probe(context: Context): DebugState {
        val wifiDisplayStatusActive = isWifiDisplayStatusActive(context)

        // Only trust live WiFi display status; Global settings keys are often stale on OEM builds.
        return DebugState(
            wifiDisplaySettingOn = null,
            wifiDisplayStatusActive = wifiDisplayStatusActive,
            castSettingKeysChecked = emptyList(),
            systemCastActive = wifiDisplayStatusActive,
        )
    }

    private fun isWifiDisplayStatusActive(context: Context): Boolean {
        return try {
            val manager =
                context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val method = DisplayManager::class.java.getMethod("getWifiDisplayStatus")
            val status = method.invoke(manager) ?: return false
            val activeState = status.javaClass.getMethod("getActiveDisplayState")
                .invoke(status) as Int
            activeState == 2 // WifiDisplayStatus.ACTIVE_DISPLAY_STATE_ON
        } catch (_: ReflectiveOperationException) {
            false
        }
    }
}

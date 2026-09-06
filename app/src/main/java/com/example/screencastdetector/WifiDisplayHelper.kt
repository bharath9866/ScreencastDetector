package com.example.screencastdetector

import android.content.Context
import android.hardware.display.DisplayManager

/** WifiDisplayStatus.ACTIVE_DISPLAY_STATE_ON */
private const val WIFI_DISPLAY_STATE_ON = 2

internal object WifiDisplayHelper {
    fun isWifiDisplayActive(context: Context): Boolean {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return isWifiDisplayActive(manager)
    }

    fun isWifiDisplayActive(manager: DisplayManager): Boolean {
        return try {
            val method = DisplayManager::class.java.getMethod("getWifiDisplayStatus")
            val status = method.invoke(manager) ?: return false
            val activeState = status.javaClass.getMethod("getActiveDisplayState")
                .invoke(status) as Int
            activeState == WIFI_DISPLAY_STATE_ON
        } catch (_: ReflectiveOperationException) {
            false
        }
    }
}

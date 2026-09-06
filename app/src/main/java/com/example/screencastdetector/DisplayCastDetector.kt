package com.example.screencastdetector

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/** Display type constants (API 30+). Defined locally for compile compatibility. */
private const val DISPLAY_TYPE_EXTERNAL = 2
private const val DISPLAY_TYPE_WIFI = 3
private const val DISPLAY_TYPE_VIRTUAL = 5

/**
 * Detects visible cast threats: HDMI / WiFi display, presentation displays,
 * and non-private virtual displays (screen recorders, in-app share).
 */
object DisplayCastDetector {
    data class DebugState(
        val displayCount: Int,
        val presentationCount: Int,
        val wifiDisplayActive: Boolean,
        val externalDisplayActive: Boolean,
        val virtualDisplayActive: Boolean,
    )

    @Volatile
    private var externalDisplayActive = false

    @Volatile
    private var virtualDisplayActive = false

    private var displayListener: DisplayManager.DisplayListener? = null
    private var displayManager: DisplayManager? = null
    private var monitoringActivity: Activity? = null
    private var monitoringStarted = false
    private val trackedNonDefaultDisplayIds = mutableSetOf<Int>()

    fun getDebugState(context: Context): DebugState {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return DebugState(
            displayCount = manager.displays.size,
            presentationCount = getPresentationDisplays(manager).size,
            wifiDisplayActive = WifiDisplayHelper.isWifiDisplayActive(manager),
            externalDisplayActive = externalDisplayActive,
            virtualDisplayActive = virtualDisplayActive,
        )
    }

    fun refreshState(context: Context) {
        refreshDisplayState(context)
    }

    fun startMonitoring(activity: Activity) {
        if (monitoringStarted && monitoringActivity == activity) return
        stopMonitoringInternal()

        monitoringActivity = activity
        monitoringStarted = true
        refreshDisplayState(activity)
        registerDisplayListener(activity)
    }

    fun stopMonitoring() {
        stopMonitoringInternal()
    }

    private fun stopMonitoringInternal() {
        monitoringActivity = null
        monitoringStarted = false
        unregisterDisplayListener()
    }

    private fun registerDisplayListener(context: Context) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager = manager

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) {
                    trackedNonDefaultDisplayIds.add(displayId)
                }
                refreshDisplayState(context)
            }

            override fun onDisplayRemoved(displayId: Int) {
                trackedNonDefaultDisplayIds.remove(displayId)
                refreshDisplayState(context)
            }

            override fun onDisplayChanged(displayId: Int) {
                refreshDisplayState(context)
            }
        }
        displayListener = listener
        manager.registerDisplayListener(listener, null)
    }

    private fun unregisterDisplayListener() {
        displayManager?.unregisterDisplayListener(displayListener ?: return)
        displayManager = null
        displayListener = null
        trackedNonDefaultDisplayIds.clear()
        externalDisplayActive = false
        virtualDisplayActive = false
    }

    private fun getPresentationDisplays(manager: DisplayManager): Array<Display> {
        return try {
            manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        } catch (_: Exception) {
            emptyArray()
        }
    }

    private fun getDisplayType(display: Display): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1
        return try {
            Display::class.java.getMethod("getType").invoke(display) as Int
        } catch (_: ReflectiveOperationException) {
            -1
        }
    }

    private fun isExternalThreatDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY || display.state == Display.STATE_OFF) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (getDisplayType(display)) {
                DISPLAY_TYPE_EXTERNAL, DISPLAY_TYPE_WIFI -> true
                else -> false
            }
        }

        return true
    }

    private fun isVirtualThreatDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY || display.state == Display.STATE_OFF) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (getDisplayType(display) != DISPLAY_TYPE_VIRTUAL) return false
            if ((display.flags and Display.FLAG_PRIVATE) != 0) return false
            return true
        }

        return false
    }

    private fun isPresentationThreatDisplay(display: Display): Boolean {
        return display.displayId != Display.DEFAULT_DISPLAY &&
            display.state != Display.STATE_OFF
    }

    private fun isLegacyThreatDisplay(display: Display): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            display.displayId != Display.DEFAULT_DISPLAY &&
            display.state != Display.STATE_OFF
    }

    private fun refreshDisplayState(context: Context) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        var hasExternal = WifiDisplayHelper.isWifiDisplayActive(manager)
        var hasVirtual = false

        for (display in manager.displays) {
            if (isExternalThreatDisplay(display)) hasExternal = true
            if (isVirtualThreatDisplay(display)) hasVirtual = true
            if (isLegacyThreatDisplay(display)) hasExternal = true
        }

        for (displayId in trackedNonDefaultDisplayIds) {
            val display = manager.getDisplay(displayId) ?: continue
            if (display.state == Display.STATE_OFF) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (isVirtualThreatDisplay(display) || isExternalThreatDisplay(display)) {
                    hasVirtual = true
                }
            } else {
                hasVirtual = true
            }
        }

        for (displayId in 0..15) {
            if (displayId == Display.DEFAULT_DISPLAY) continue
            val display = manager.getDisplay(displayId) ?: continue
            if (display.state == Display.STATE_OFF) continue
            val name = display.name?.toString().orEmpty()
            if (name.contains("GlideX", ignoreCase = true) ||
                name.contains("VirtualDisplay", ignoreCase = true)
            ) {
                hasVirtual = true
            }
        }

        for (display in getPresentationDisplays(manager)) {
            if (!isPresentationThreatDisplay(display)) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                when (getDisplayType(display)) {
                    DISPLAY_TYPE_EXTERNAL, DISPLAY_TYPE_WIFI -> hasExternal = true
                    DISPLAY_TYPE_VIRTUAL -> hasVirtual = true
                    else -> hasVirtual = true
                }
            } else {
                hasVirtual = true
            }
        }

        externalDisplayActive = hasExternal
        virtualDisplayActive = hasVirtual
    }
}

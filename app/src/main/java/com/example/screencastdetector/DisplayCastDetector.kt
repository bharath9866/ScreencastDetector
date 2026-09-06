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

    private fun displayOwnerPackage(display: Display): String? {
        return DisplayOwnerParser.parseOwnerPackage(display)
    }

    private fun isKnownVirtualCaptureName(displayName: String): Boolean {
        return displayName.contains("GlideX", ignoreCase = true) ||
            displayName.contains("VirtualDisplay", ignoreCase = true) ||
            CaptureConfirmation.isConferencingCaptureDisplay(displayName)
    }

    private fun contributesExternalThreat(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY || display.state == Display.STATE_OFF) {
            return false
        }

        val displayName = display.name?.toString().orEmpty()
        if (CaptureConfirmation.isConferencingCaptureDisplay(displayName)) return false
        if (isKnownVirtualCaptureName(displayName)) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (getDisplayType(display)) {
                DISPLAY_TYPE_EXTERNAL, DISPLAY_TYPE_WIFI -> true
                else -> false
            }
        }

        // API 29: non-default displays that are not known screen-capture virtual displays.
        return true
    }

    private fun contributesVirtualThreat(context: Context, display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY || display.state == Display.STATE_OFF) {
            return false
        }

        val displayName = display.name?.toString().orEmpty()

        if (CaptureConfirmation.isConferencingCaptureDisplay(displayName)) {
            // Meet/GlideX screen share is detected via CastNotificationListener on API 29.
            return false
        }

        if (GeminiLivePackages.isGeminiCaptureDisplay(displayName, displayOwnerPackage(display))) {
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (getDisplayType(display) != DISPLAY_TYPE_VIRTUAL) return false
            if ((display.flags and Display.FLAG_PRIVATE) != 0) return false
            return true
        }

        return isKnownVirtualCaptureName(displayName)
    }

    private fun applyDisplayThreat(
        context: Context,
        display: Display,
        external: BooleanArray,
        virtual: BooleanArray,
    ) {
        if (contributesExternalThreat(display)) external[0] = true
        if (contributesVirtualThreat(context, display)) virtual[0] = true
    }

    private fun refreshDisplayState(context: Context) {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        val hasExternal = booleanArrayOf(WifiDisplayHelper.isWifiDisplayActive(manager))
        val hasVirtual = booleanArrayOf(false)

        for (display in manager.displays) {
            applyDisplayThreat(context, display, hasExternal, hasVirtual)
        }

        for (displayId in trackedNonDefaultDisplayIds.toList()) {
            val display = manager.getDisplay(displayId)
            if (display == null || display.state == Display.STATE_OFF) {
                trackedNonDefaultDisplayIds.remove(displayId)
                continue
            }
            applyDisplayThreat(context, display, hasExternal, hasVirtual)
        }

        for (displayId in 0..15) {
            if (displayId == Display.DEFAULT_DISPLAY) continue
            val display = manager.getDisplay(displayId) ?: continue
            applyDisplayThreat(context, display, hasExternal, hasVirtual)
        }

        for (display in getPresentationDisplays(manager)) {
            applyDisplayThreat(context, display, hasExternal, hasVirtual)
        }

        externalDisplayActive = hasExternal[0]
        virtualDisplayActive = hasVirtual[0]
    }
}

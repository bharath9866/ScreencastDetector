package com.example.screencastdetector

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import java.util.concurrent.CopyOnWriteArrayList

/** Display type constants (API 30+). Defined locally for compile compatibility. */
private const val DISPLAY_TYPE_EXTERNAL = 2
private const val DISPLAY_TYPE_WIFI = 3
private const val DISPLAY_TYPE_VIRTUAL = 5

/** WifiDisplayStatus.ACTIVE_DISPLAY_STATE_ON */
private const val WIFI_DISPLAY_STATE_ON = 2

/**
 * Display threat detection for proctoring: external cast/HDMI and virtual
 * displays from MediaProjection / screen recorders / in-app share.
 */
object DisplayCastDetector {
    data class DisplayThreatState(
        val castingActive: Boolean,
        val externalDisplayActive: Boolean,
        val virtualDisplayActive: Boolean,
        val screenRecordingActive: Boolean = false,
        val detectedCondition: String?,
    )

    data class DebugState(
        val displayCount: Int,
        val presentationCount: Int,
        val wifiDisplayActive: Boolean,
        val externalDisplayActive: Boolean,
        val virtualDisplayActive: Boolean,
    )

    /** When true, virtual displays from the app's own capture are ignored. */
    @Volatile
    var ownAppVirtualDisplayAllowed: Boolean = false

    @Volatile
    private var externalDisplayActive = false

    @Volatile
    private var virtualDisplayActive = false

    @Volatile
    private var detectedCondition: String? = null

    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    private var displayListener: DisplayManager.DisplayListener? = null
    private var displayManager: DisplayManager? = null
    private var monitoringActivity: Activity? = null
    private var monitoringStarted = false
    private val trackedNonDefaultDisplayIds = mutableSetOf<Int>()

    fun isMonitoring(): Boolean = monitoringStarted

    fun getDebugState(context: Context): DebugState {
        val manager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val presentationDisplays = getPresentationDisplays(manager)
        return DebugState(
            displayCount = manager.displays.size,
            presentationCount = presentationDisplays.size,
            wifiDisplayActive = isWifiDisplayActive(manager),
            externalDisplayActive = externalDisplayActive,
            virtualDisplayActive = virtualDisplayActive,
        )
    }

    fun refreshState(context: Context) {
        refreshDisplayState(context)
    }

    fun isActive(): Boolean = isScreenSharingActive()

    fun isScreenSharingActive(): Boolean {
        return externalDisplayActive || virtualDisplayActive
    }

    fun isExternalDisplayActive(): Boolean = externalDisplayActive

    fun isVirtualDisplayActive(): Boolean = virtualDisplayActive

    fun getDetectedCondition(): String? = detectedCondition

    fun getDisplayThreatState(): DisplayThreatState {
        return DisplayThreatState(
            castingActive = isScreenSharingActive(),
            externalDisplayActive = externalDisplayActive,
            virtualDisplayActive = virtualDisplayActive,
            detectedCondition = detectedCondition,
        )
    }

    fun addChangeListener(listener: () -> Unit): () -> Unit {
        changeListeners.add(listener)
        return { changeListeners.remove(listener) }
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
        val manager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
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
        val manager = displayManager
        val listener = displayListener
        if (manager != null && listener != null) {
            manager.unregisterDisplayListener(listener)
        }
        displayManager = null
        displayListener = null
        trackedNonDefaultDisplayIds.clear()
        externalDisplayActive = false
        virtualDisplayActive = false
        detectedCondition = null
    }

    private fun getPresentationDisplays(manager: DisplayManager): Array<Display> {
        return try {
            manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        } catch (_: Exception) {
            emptyArray()
        }
    }

    private fun isWifiDisplayActive(manager: DisplayManager): Boolean {
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

    private fun getDisplayType(display: Display): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1
        return try {
            Display::class.java.getMethod("getType").invoke(display) as Int
        } catch (_: ReflectiveOperationException) {
            -1
        }
    }

    private fun isExternalThreatDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        if (display.state == Display.STATE_OFF) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return when (getDisplayType(display)) {
                DISPLAY_TYPE_EXTERNAL, DISPLAY_TYPE_WIFI -> true
                else -> false
            }
        }

        return display.displayId != Display.DEFAULT_DISPLAY
    }

    private fun isVirtualThreatDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        if (display.state == Display.STATE_OFF) return false

        if (ownAppVirtualDisplayAllowed) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (getDisplayType(display) != DISPLAY_TYPE_VIRTUAL) return false
            if ((display.flags and Display.FLAG_PRIVATE) != 0) return false
            return true
        }

        return false
    }

    private fun isPresentationThreatDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY) return false
        if (display.state == Display.STATE_OFF) return false
        if (ownAppVirtualDisplayAllowed) return false
        return true
    }

    private fun isLegacyThreatDisplay(display: Display): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return false
        return display.displayId != Display.DEFAULT_DISPLAY &&
            display.state != Display.STATE_OFF
    }

    private fun refreshDisplayState(context: Context) {
        val manager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        var hasExternal = isWifiDisplayActive(manager)
        var hasVirtual = false

        for (display in manager.displays) {
            if (isExternalThreatDisplay(display)) {
                hasExternal = true
            }
            if (isVirtualThreatDisplay(display)) {
                hasVirtual = true
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && isLegacyThreatDisplay(display)) {
                hasExternal = true
            }
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
            if (isPresentationThreatDisplay(display)) {
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
        }

        val condition = when {
            hasExternal -> "Screen casting detected"
            hasVirtual -> "Screen sharing detected"
            else -> null
        }

        val changed = externalDisplayActive != hasExternal ||
            virtualDisplayActive != hasVirtual ||
            detectedCondition != condition

        if (changed) {
            externalDisplayActive = hasExternal
            virtualDisplayActive = hasVirtual
            detectedCondition = condition
            notifyChangeListeners()
        }
    }

    private fun notifyChangeListeners() {
        for (listener in changeListeners) {
            listener.invoke()
        }
    }
}

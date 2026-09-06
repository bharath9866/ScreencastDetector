package com.example.screencastdetector

import android.app.Activity
import android.content.Context

/**
 * Facade that runs all screencast detectors and returns a single combined result.
 *
 * Detectors fall into two groups:
 * - **Monitored** — [DisplayCastDetector] and [ScreenRecordingDetector] register listeners/poll loops.
 * - **On-demand** — probed fresh on each [probe] call.
 */
object ScreencastProbe {
    data class ProbeResult(
        val detected: Boolean,
        val reason: String?,
        val display: DisplayCastDetector.DebugState,
        val hiddenDisplay: HiddenDisplayDetector.DebugState,
        val mediaProjectionService: MediaProjectionServiceProbe.DebugState,
        val notificationListener: CastNotificationListener.DebugState,
        val recording: ScreenRecordingDetector.DebugState,
        val mediaRouter: MediaRouterCastDetector.DebugState,
    )

    fun probe(context: Context): ProbeResult {
        refreshMonitoredDetectors(context)

        val display = DisplayCastDetector.getDebugState(context)
        val hiddenDisplay = HiddenDisplayDetector.probe(context)
        val mediaProjectionService = MediaProjectionServiceProbe.probe(context)
        val notificationListener = CastNotificationListener.getDebugState(context)
        val recording = ScreenRecordingDetector.getDebugState(context)
        val mediaRouter = MediaRouterCastDetector.probe(context)

        val screenSharingActive = recording.screenRecordingActive ||
            hiddenDisplay.hiddenVirtualDisplayActive ||
            mediaProjectionService.active ||
            notificationListener.mirroringActive ||
            mediaRouter.castRouteActive

        val reason = resolveReason(
            notificationListener = notificationListener,
            mediaProjectionService = mediaProjectionService,
            hiddenDisplay = hiddenDisplay,
            display = display,
            recording = recording,
            mediaRouter = mediaRouter,
        )

        val detected = display.externalDisplayActive ||
            display.virtualDisplayActive ||
            screenSharingActive

        return ProbeResult(
            detected = detected,
            reason = reason,
            display = display,
            hiddenDisplay = hiddenDisplay,
            mediaProjectionService = mediaProjectionService,
            notificationListener = notificationListener,
            recording = recording,
            mediaRouter = mediaRouter,
        )
    }

    fun startMonitoring(activity: Activity) {
        DisplayCastDetector.startMonitoring(activity)
        ScreenRecordingDetector.startMonitoring(activity)
    }

    fun stopMonitoring() {
        ScreenRecordingDetector.stopMonitoring()
        DisplayCastDetector.stopMonitoring()
    }

    fun formatDebugText(context: Context, result: ProbeResult): String {
        return DebugReportFormatter.format(context, result)
    }

    private fun refreshMonitoredDetectors(context: Context) {
        DisplayCastDetector.refreshState(context)
        ScreenRecordingDetector.refreshState(context)
    }

    private fun resolveReason(
        notificationListener: CastNotificationListener.DebugState,
        mediaProjectionService: MediaProjectionServiceProbe.DebugState,
        hiddenDisplay: HiddenDisplayDetector.DebugState,
        display: DisplayCastDetector.DebugState,
        recording: ScreenRecordingDetector.DebugState,
        mediaRouter: MediaRouterCastDetector.DebugState,
    ): String? = when {
        notificationListener.mirroringActive -> "GlideX mirroring notification"
        mediaProjectionService.active ->
            "MediaProjection service (${mediaProjectionService.activePackage})"
        hiddenDisplay.hiddenVirtualDisplayActive -> formatHiddenDisplayReason(hiddenDisplay)
        display.externalDisplayActive -> "External display"
        display.virtualDisplayActive -> "Virtual display"
        recording.screenRecordingActive -> formatRecordingReason(recording)
        mediaRouter.castRouteActive -> "MediaRouter cast route"
        else -> null
    }

    private fun formatHiddenDisplayReason(hiddenDisplay: HiddenDisplayDetector.DebugState): String {
        val owner = hiddenDisplay.virtualDisplayOwners.firstOrNull()
        return if (owner != null) "Private virtual display ($owner)" else "Private virtual display"
    }

    private fun formatRecordingReason(recording: ScreenRecordingDetector.DebugState): String {
        return when (recording.detectionTrigger) {
            "appops_package_capture" -> "Screen sharing (GlideX AppOps)"
            "asus_overlay" -> "Screen sharing (ASUS overlay)"
            "mirroring_package_active_ops" -> "Screen sharing (active AppOps)"
            "capture_service" -> "Screen sharing (capture service)"
            "mirroring_overlay" -> "Screen sharing (overlay)"
            "system_ui_projection" -> "Screen sharing (system UI)"
            "media_projection_info" -> "Screen sharing (MediaProjection)"
            "mirroring_foreground_service" -> "Screen sharing (foreground service)"
            else -> "Screen sharing"
        }
    }
}

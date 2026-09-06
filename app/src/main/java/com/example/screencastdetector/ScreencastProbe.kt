package com.example.screencastdetector

import android.app.Activity
import android.content.Context
import android.util.Log

/**
 * Facade that runs all screencast detectors and returns a single combined result.
 */
object ScreencastProbe {
    private const val LOG_TAG = "ScreencastDetector"

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
        val mediaProjectionService = CaptureConfirmation.gateMediaProjectionService(
            context,
            MediaProjectionServiceProbe.probe(context),
        )
        val notificationListener = CastNotificationListener.getDebugState(context)
        val recording = ScreenRecordingDetector.getDebugState(context)
        val mediaRouter = MediaRouterCastDetector.probe(context)

        val hiddenDisplayActive = CaptureConfirmation.isHiddenDisplayThreat(context, hiddenDisplay)
        val screenSharingActive = recording.screenRecordingActive ||
            hiddenDisplayActive ||
            mediaProjectionService.active ||
            notificationListener.mirroringActive ||
            mediaRouter.castRouteActive

        val reason = resolveReason(
            notificationListener = notificationListener,
            mediaProjectionService = mediaProjectionService,
            hiddenDisplayActive = hiddenDisplayActive,
            hiddenDisplay = hiddenDisplay,
            display = display,
            recording = recording,
            mediaRouter = mediaRouter,
        )

        val detected = display.externalDisplayActive ||
            display.virtualDisplayActive ||
            screenSharingActive

        logProbeSummary(
            detected = detected,
            reason = reason,
            display = display,
            hiddenDisplayActive = hiddenDisplayActive,
            mediaProjectionService = mediaProjectionService,
            notificationListener = notificationListener,
            recording = recording,
            mediaRouter = mediaRouter,
        )

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

    private fun logProbeSummary(
        detected: Boolean,
        reason: String?,
        display: DisplayCastDetector.DebugState,
        hiddenDisplayActive: Boolean,
        mediaProjectionService: MediaProjectionServiceProbe.DebugState,
        notificationListener: CastNotificationListener.DebugState,
        recording: ScreenRecordingDetector.DebugState,
        mediaRouter: MediaRouterCastDetector.DebugState,
    ) {
        Log.i(
            LOG_TAG,
            "Probe detected=$detected reason=${reason ?: "(none)"} " +
                "displayExt=${display.externalDisplayActive} displayVirt=${display.virtualDisplayActive} " +
                "hidden=$hiddenDisplayActive mediaProj=${mediaProjectionService.active} " +
                "notification=${notificationListener.mirroringActive} " +
                "recording=${recording.screenRecordingActive} trigger=${recording.detectionTrigger} " +
                "mediaRouter=${mediaRouter.castRouteActive}",
        )
    }

    private fun resolveReason(
        notificationListener: CastNotificationListener.DebugState,
        mediaProjectionService: MediaProjectionServiceProbe.DebugState,
        hiddenDisplayActive: Boolean,
        hiddenDisplay: HiddenDisplayDetector.DebugState,
        display: DisplayCastDetector.DebugState,
        recording: ScreenRecordingDetector.DebugState,
        mediaRouter: MediaRouterCastDetector.DebugState,
    ): String? = when {
        notificationListener.mirroringActive -> formatNotificationReason(notificationListener)
        mediaProjectionService.active && GeminiLivePackages.isGeminiPackage(mediaProjectionService.activePackage) ->
            "Gemini Live screen sharing (MediaProjection)"
        mediaProjectionService.active ->
            "MediaProjection service (${mediaProjectionService.activePackage})"
        hiddenDisplayActive -> formatHiddenDisplayReason(hiddenDisplay)
        display.externalDisplayActive -> "External display"
        display.virtualDisplayActive -> "Virtual display"
        recording.screenRecordingActive -> formatRecordingReason(recording)
        mediaRouter.castRouteActive -> "MediaRouter cast route"
        else -> null
    }

    private fun formatNotificationReason(
        notificationListener: CastNotificationListener.DebugState,
    ): String {
        return when {
            "gemini" in notificationListener.activeSources -> "Gemini Live screen sharing"
            "meet" in notificationListener.activeSources -> "Meet screen sharing"
            "glidex" in notificationListener.activeSources -> "GlideX mirroring"
            else -> "Screen sharing notification"
        }
    }

    private fun formatHiddenDisplayReason(hiddenDisplay: HiddenDisplayDetector.DebugState): String {
        if (CaptureConfirmation.isGeminiScreenSharingDisplayThreat(hiddenDisplay)) {
            return "Gemini Live virtual display (ScreenRecorder)"
        }
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

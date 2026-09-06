package com.example.screencastdetector

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Builds the monospace debug panel shown in [MainActivity]. */
internal object DebugReportFormatter {
    private val CONFERENCING_DEBUG_PACKAGES = listOf(
        "com.google.android.apps.meetings",
        "com.google.android.apps.tachyon",
        "com.google.meet",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard",
    )

    fun format(context: Context, result: ScreencastProbe.ProbeResult): String {
        val display = result.display
        val hiddenDisplay = result.hiddenDisplay
        val mediaProjectionService = result.mediaProjectionService
        val notificationListener = result.notificationListener
        val recording = result.recording
        val mediaRouter = result.mediaRouter
        val breakdown = recording.heuristicBreakdown

        return buildString {
            appendHeader(context, recording.lastProbeAtMs)
            appendDisplaySection(display)
            appendHiddenDisplaySection(hiddenDisplay)
            appendMediaProjectionServiceSection(mediaProjectionService)
            appendNotificationListenerSection(notificationListener)
            appendMediaRouterSection(mediaRouter)
            appendRecordingSection(recording)
            appendHeuristicBreakdown(breakdown)
            appendCaptureConfirmation(context)
            appendCombinedSection(result)
        }
    }

    private fun StringBuilder.appendHeader(context: Context, lastProbeAtMs: Long) {
        appendLine("App version: ${appVersion(context)}")
        appendLine("API level: ${Build.VERSION.SDK_INT}")
        appendLine("lastProbeAt: $lastProbeAtMs")
        appendLine()
    }

    private fun StringBuilder.appendDisplaySection(display: DisplayCastDetector.DebugState) {
        appendLine("--- Display ---")
        appendLine("displayCount: ${display.displayCount}")
        appendLine("presentationCount: ${display.presentationCount}")
        appendLine("wifiDisplayActive: ${display.wifiDisplayActive}")
        appendLine("externalDisplayActive: ${display.externalDisplayActive}")
        appendLine("virtualDisplayActive: ${display.virtualDisplayActive}")
        appendLine()
    }

    private fun StringBuilder.appendHiddenDisplaySection(hiddenDisplay: HiddenDisplayDetector.DebugState) {
        appendLine("--- Hidden virtual display (GlideX) ---")
        appendLine("supported: ${hiddenDisplay.supported}")
        appendLine("globalDisplayIds: ${joinOrNone(hiddenDisplay.globalDisplayIds)}")
        appendLine("virtualDisplayNames: ${joinOrNone(hiddenDisplay.virtualDisplayNames)}")
        appendLine("virtualDisplayOwners: ${joinOrNone(hiddenDisplay.virtualDisplayOwners)}")
        appendLine("hiddenVirtualDisplayActive: ${hiddenDisplay.hiddenVirtualDisplayActive}")
        appendLine()
    }

    private fun StringBuilder.appendMediaProjectionServiceSection(
        mediaProjectionService: MediaProjectionServiceProbe.DebugState,
    ) {
        appendLine("--- MediaProjection service ---")
        appendLine("supported: ${mediaProjectionService.supported}")
        appendLine("activePackage: ${mediaProjectionService.activePackage ?: "(none)"}")
        appendLine("active: ${mediaProjectionService.active}")
        appendLine()
    }

    private fun StringBuilder.appendNotificationListenerSection(
        notificationListener: CastNotificationListener.DebugState,
    ) {
        appendLine("--- Notification listener (GlideX / Meet / Gemini) ---")
        appendLine("accessEnabled: ${notificationListener.accessEnabled}")
        appendLine("serviceConnected: ${notificationListener.serviceConnected}")
        appendLine("activeMirroringNotifications: ${notificationListener.activeMirroringNotifications}")
        appendLine("activeSources: ${joinOrNone(notificationListener.activeSources)}")
        appendLine("mirroringActive: ${notificationListener.mirroringActive}")
        appendLine()
    }

    private fun StringBuilder.appendMediaRouterSection(mediaRouter: MediaRouterCastDetector.DebugState) {
        appendLine("--- MediaRouter ---")
        appendLine("supported: ${mediaRouter.supported}")
        appendLine("routeCount: ${mediaRouter.routeCount}")
        appendLine("selectedRoute: ${mediaRouter.selectedRouteName ?: "(none)"}")
        appendLine("selectedRouteIsDefault: ${mediaRouter.selectedRouteIsDefault}")
        appendLine("castRouteActive: ${mediaRouter.castRouteActive}")
        appendLine()
    }

    private fun StringBuilder.appendRecordingSection(recording: ScreenRecordingDetector.DebugState) {
        appendLine("--- Screen recording / PC mirror ---")
        appendLine("appOpsActiveCheckSupported: ${recording.appOpsActiveCheckSupported}")
        appendLine("appOpsWatcherSupported: ${recording.appOpsWatcherSupported}")
        appendLine("internalAppOpsActiveSupported: ${recording.internalAppOpsActiveSupported}")
        appendLine("packagesForOpsSupported: ${recording.packagesForOpsSupported}")
        appendLine("appOpsPackageProbeSupported: ${recording.appOpsPackageProbeSupported}")
        appendLine("appOpsProbedPackages: ${formatAppOpsProbed(recording.appOpsProbedPackages)}")
        appendLine("discoveredMirroringPackages: ${joinOrNone(recording.discoveredMirroringPackages)}")
        appendLine("runningProjectMediaPackages: ${joinOrNone(recording.runningProjectMediaPackages)}")
        appendLine("runningMirroringOverlayPackages: ${joinOrNone(recording.runningMirroringOverlayPackages)}")
        appendLine("runningDynamicOverlayPackages: ${joinOrNone(recording.runningDynamicOverlayPackages)}")
        appendLine("runningAsusOverlayPackages: ${joinOrNone(recording.runningAsusOverlayPackages)}")
        appendLine("activeMediaProjectionPackage: ${recording.activeMediaProjectionPackage ?: "(none)"}")
        appendLine("activeMirroringPackageOps: ${recording.activeMirroringPackageOps ?: "(none)"}")
        appendLine("visibleProcessCount: ${recording.visibleProcessCount}")
        appendLine("detectionTrigger: ${recording.detectionTrigger ?: "(none)"}")
        appendLine("heuristicMatch: ${recording.heuristicMatch}")
        appendLine("screenRecordingActive: ${recording.screenRecordingActive}")
        appendLine()
    }

    private fun StringBuilder.appendHeuristicBreakdown(breakdown: ScreenRecordingDetector.HeuristicBreakdown) {
        appendLine("--- Heuristic breakdown ---")
        appendLine("projectMediaRunning: ${breakdown.projectMediaRunning}")
        appendLine("overlayRunning: ${breakdown.overlayRunning}")
        appendLine("asusOverlayRunning: ${breakdown.asusOverlayRunning}")
        appendLine("dynamicAsusOverlay: ${breakdown.dynamicAsusOverlay}")
        appendLine("mediaProjectionInfo: ${breakdown.mediaProjectionInfo}")
        appendLine("mirroringPackageActiveOps: ${breakdown.mirroringPackageActiveOps}")
        appendLine("knownPackageProjection: ${breakdown.knownPackageProjection}")
        appendLine("systemUiProjection: ${breakdown.systemUiProjection}")
        appendLine("mirroringAppOpsCombo: ${breakdown.mirroringAppOpsCombo}")
        appendLine("captureServiceMatch: ${breakdown.captureServiceMatch}")
        appendLine("foregroundServiceMatch: ${breakdown.foregroundServiceMatch}")
        appendLine("processMatch: ${breakdown.processMatch}")
        appendLine("serviceMatch: ${breakdown.serviceMatch}")
        appendLine("keywordProcessMatch: ${breakdown.keywordProcessMatch}")
        appendLine()
    }

    private fun StringBuilder.appendCaptureConfirmation(context: Context) {
        appendLine("--- Capture confirmation ---")
        for (packageName in CONFERENCING_DEBUG_PACKAGES) {
            if (!MirroringPackageRegistry.isMirroringPackageInstalled(context, packageName)) continue
            appendLine(
                "$packageName projectMediaActive: " +
                    CaptureConfirmation.isProjectMediaOpActive(context, packageName),
            )
            appendLine(
                "$packageName mediaProjectionActive: " +
                    CaptureConfirmation.hasActiveMediaProjectionForPackage(context, packageName),
            )
        }
        appendLine(
            "anyMediaProjectionActive: ${CaptureConfirmation.hasAnyActiveMediaProjection(context)}",
        )
        appendLine()
    }

    private fun StringBuilder.appendCombinedSection(result: ScreencastProbe.ProbeResult) {
        appendLine("--- Combined ---")
        appendLine("detected: ${result.detected}")
        appendLine("reason: ${result.reason ?: "(none)"}")
    }

    private fun formatAppOpsProbed(
        packages: List<AppOpsPackageProbe.PackageOpsState>,
    ): String {
        if (packages.isEmpty()) return "(none)"
        return packages.joinToString { entry ->
            "${entry.packageName}=[${entry.runningOps.joinToString()}]"
        }
    }

    private fun joinOrNone(values: List<*>): String {
        return if (values.isEmpty()) "(none)" else values.joinToString(", ")
    }

    private fun appVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }
}

package com.example.screencastdetector

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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
        val systemCast: SystemCastDetector.DebugState,
    )

    fun probe(context: Context): ProbeResult {
        refresh(context)
        val display = DisplayCastDetector.getDebugState(context)
        val hiddenDisplay = HiddenDisplayDetector.getDebugState(context)
        val mediaProjectionService = MediaProjectionServiceProbe.probe(context)
        val notificationListener = CastNotificationListener.getDebugState(context)
        val recording = ScreenRecordingDetector.getDebugState(context)
        val mediaRouter = MediaRouterCastDetector.getDebugState(context)
        val systemCast = SystemCastDetector.getDebugState(context)

        val screenSharingActive = recording.screenRecordingActive ||
            hiddenDisplay.hiddenVirtualDisplayActive ||
            mediaProjectionService.active ||
            notificationListener.mirroringActive ||
            mediaRouter.castRouteActive ||
            systemCast.systemCastActive

        val reason = when {
            notificationListener.mirroringActive -> "GlideX mirroring notification"
            mediaProjectionService.active -> "MediaProjection service (${mediaProjectionService.activePackage})"
            hiddenDisplay.hiddenVirtualDisplayActive -> formatHiddenDisplayReason(hiddenDisplay)
            display.externalDisplayActive -> "External display"
            display.virtualDisplayActive -> "Virtual display"
            recording.screenRecordingActive -> formatRecordingReason(recording)
            mediaRouter.castRouteActive -> "MediaRouter cast route"
            systemCast.systemCastActive -> "System cast setting"
            else -> null
        }

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
            systemCast = systemCast,
        )
    }

    fun refresh(context: Context) {
        DisplayCastDetector.refreshState(context)
        HiddenDisplayDetector.refreshState(context)
        ScreenRecordingDetector.refreshState(context)
        MediaRouterCastDetector.refreshState(context)
        SystemCastDetector.refreshState(context)
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
        val display = result.display
        val hiddenDisplay = result.hiddenDisplay
        val mediaProjectionService = result.mediaProjectionService
        val notificationListener = result.notificationListener
        val recording = result.recording
        val mediaRouter = result.mediaRouter
        val systemCast = result.systemCast
        val breakdown = recording.heuristicBreakdown

        return buildString {
            appendLine("App version: ${getAppVersion(context)}")
            appendLine("API level: ${Build.VERSION.SDK_INT}")
            appendLine("lastProbeAt: ${recording.lastProbeAtMs}")
            appendLine()
            appendLine("--- Display ---")
            appendLine("displayCount: ${display.displayCount}")
            appendLine("presentationCount: ${display.presentationCount}")
            appendLine("wifiDisplayActive: ${display.wifiDisplayActive}")
            appendLine("externalDisplayActive: ${display.externalDisplayActive}")
            appendLine("virtualDisplayActive: ${display.virtualDisplayActive}")
            appendLine()
            appendLine("--- Hidden virtual display (GlideX) ---")
            appendLine("supported: ${hiddenDisplay.supported}")
            appendLine("globalDisplayIds: ${hiddenDisplay.globalDisplayIds.joinToString(", ").ifEmpty { "(none)" }}")
            appendLine("virtualDisplayNames: ${hiddenDisplay.virtualDisplayNames.joinToString(", ").ifEmpty { "(none)" }}")
            appendLine("virtualDisplayOwners: ${hiddenDisplay.virtualDisplayOwners.joinToString(", ").ifEmpty { "(none)" }}")
            appendLine("hiddenVirtualDisplayActive: ${hiddenDisplay.hiddenVirtualDisplayActive}")
            appendLine()
            appendLine("--- MediaProjection service ---")
            appendLine("supported: ${mediaProjectionService.supported}")
            appendLine("activePackage: ${mediaProjectionService.activePackage ?: "(none)"}")
            appendLine("active: ${mediaProjectionService.active}")
            appendLine()
            appendLine("--- Notification listener (GlideX) ---")
            appendLine("accessEnabled: ${notificationListener.accessEnabled}")
            appendLine("serviceConnected: ${notificationListener.serviceConnected}")
            appendLine("activeMirroringNotifications: ${notificationListener.activeMirroringNotifications}")
            appendLine("mirroringActive: ${notificationListener.mirroringActive}")
            appendLine()
            appendLine("--- System cast ---")
            appendLine("wifiDisplayStatusActive: ${systemCast.wifiDisplayStatusActive}")
            appendLine("wifiDisplaySettingOn: ${systemCast.wifiDisplaySettingOn ?: "(not found)"}")
            appendLine("systemCastActive: ${systemCast.systemCastActive}")
            appendLine()
            appendLine("--- MediaRouter ---")
            appendLine("supported: ${mediaRouter.supported}")
            appendLine("routeCount: ${mediaRouter.routeCount}")
            appendLine("selectedRoute: ${mediaRouter.selectedRouteName ?: "(none)"}")
            appendLine("selectedRouteIsDefault: ${mediaRouter.selectedRouteIsDefault}")
            appendLine("castRouteActive: ${mediaRouter.castRouteActive}")
            appendLine()
            appendLine("--- Screen recording / PC mirror ---")
            appendLine("appOpsActiveCheckSupported: ${recording.appOpsActiveCheckSupported}")
            appendLine("appOpsWatcherSupported: ${recording.appOpsWatcherSupported}")
            appendLine("internalAppOpsActiveSupported: ${recording.internalAppOpsActiveSupported}")
            appendLine("packagesForOpsSupported: ${recording.packagesForOpsSupported}")
            appendLine("appOpsPackageProbeSupported: ${recording.appOpsPackageProbeSupported}")
            appendLine(
                "appOpsProbedPackages: ${
                    if (recording.appOpsProbedPackages.isEmpty()) {
                        "(none)"
                    } else {
                        recording.appOpsProbedPackages.joinToString { entry ->
                            "${entry.packageName}=[${entry.runningOps.joinToString()}]"
                        }
                    }
                }",
            )
            appendLine(
                "discoveredMirroringPackages: ${
                    formatPackageList(recording.discoveredMirroringPackages)
                }",
            )
            appendLine(
                "runningProjectMediaPackages: ${
                    formatPackageList(recording.runningProjectMediaPackages)
                }",
            )
            appendLine(
                "runningMirroringOverlayPackages: ${
                    formatPackageList(recording.runningMirroringOverlayPackages)
                }",
            )
            appendLine(
                "runningDynamicOverlayPackages: ${
                    formatPackageList(recording.runningDynamicOverlayPackages)
                }",
            )
            appendLine(
                "runningAsusOverlayPackages: ${
                    formatPackageList(recording.runningAsusOverlayPackages)
                }",
            )
            appendLine(
                "activeMediaProjectionPackage: ${
                    recording.activeMediaProjectionPackage ?: "(none)"
                }",
            )
            appendLine(
                "activeMirroringPackageOps: ${
                    recording.activeMirroringPackageOps ?: "(none)"
                }",
            )
            appendLine("visibleProcessCount: ${recording.visibleProcessCount}")
            appendLine("detectionTrigger: ${recording.detectionTrigger ?: "(none)"}")
            appendLine("heuristicMatch: ${recording.heuristicMatch}")
            appendLine("screenRecordingActive: ${recording.screenRecordingActive}")
            appendLine()
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
            appendLine("--- Combined ---")
            appendLine("detected: ${result.detected}")
            appendLine("reason: ${result.reason ?: "(none)"}")
        }
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

    private fun formatPackageList(packages: List<String>): String {
        return if (packages.isEmpty()) "(none)" else packages.joinToString(", ")
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }
}

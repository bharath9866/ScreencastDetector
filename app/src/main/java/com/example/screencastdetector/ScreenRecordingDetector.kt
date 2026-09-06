package com.example.screencastdetector

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.view.WindowManager
import java.util.concurrent.Executor
import java.util.function.Consumer

/**
 * Detects MediaProjection-based mirroring (GlideX, ASUS Glide, Link to Windows, Meet, etc.)
 * that does not register a visible secondary display.
 */
object ScreenRecordingDetector {
    private const val API_SCREEN_RECORDING_CALLBACK = 35
    private const val SCREEN_RECORDING_STATE_VISIBLE = 1
    private const val LOG_TAG = "ScreencastDetector"

    private val PROJECTION_PROCESS_KEYWORDS = listOf(
        "glidex",
        "glide",
        "mirror",
        "cast",
        "projection",
        "appmanager",
        "mobiledesktop",
        "screenrecord",
        "screencap",
        "share",
        "pccontrol",
    )

    private val MIRRORING_SERVICE_KEYWORDS = listOf(
        "mediaprojection",
        "screencapture",
        "screenrecord",
        "virtualdisplay",
    )

    data class HeuristicBreakdown(
        val projectMediaRunning: Boolean,
        val overlayRunning: Boolean,
        val dynamicAsusOverlay: Boolean,
        val asusOverlayRunning: Boolean,
        val mediaProjectionInfo: Boolean,
        val knownPackageProjection: Boolean,
        val mirroringPackageActiveOps: Boolean,
        val systemUiProjection: Boolean,
        val mirroringAppOpsCombo: Boolean,
        val captureServiceMatch: Boolean,
        val processMatch: Boolean,
        val serviceMatch: Boolean,
        val keywordProcessMatch: Boolean,
        val foregroundServiceMatch: Boolean,
    )

    data class DebugState(
        val appOpsActiveCheckSupported: Boolean,
        val appOpsWatcherSupported: Boolean,
        val internalAppOpsActiveSupported: Boolean,
        val packagesForOpsSupported: Boolean,
        val appOpsPackageProbeSupported: Boolean,
        val appOpsProbedPackages: List<AppOpsPackageProbe.PackageOpsState>,
        val discoveredMirroringPackages: List<String>,
        val runningProjectMediaPackages: List<String>,
        val runningMirroringOverlayPackages: List<String>,
        val runningDynamicOverlayPackages: List<String>,
        val runningAsusOverlayPackages: List<String>,
        val activeMediaProjectionPackage: String?,
        val activeMirroringPackageOps: String?,
        val visibleProcessCount: Int,
        val heuristicMatch: Boolean,
        val heuristicBreakdown: HeuristicBreakdown,
        val detectionTrigger: String?,
        val screenRecordingActive: Boolean,
        val lastProbeAtMs: Long,
    )

    @Volatile
    private var recordingActive = false

    @Volatile
    private var lastHeuristicMatch = false

    @Volatile
    private var lastDetectionTrigger: String? = null

    @Volatile
    private var lastHeuristicBreakdown = emptyBreakdown()

    @Volatile
    private var lastDiscoveredMirroringPackages: List<String> = emptyList()

    private var monitoringActivity: Activity? = null
    private var monitoringStarted = false
    private var screenRecordingCallback: Consumer<Int>? = null
    private var appOpsWatcher: AppOpsManager.OnOpActiveChangedListener? = null
    private var pollHandler: android.os.Handler? = null
    private var pollRunnable: Runnable? = null

    private const val POLL_INTERVAL_MS = 1_000L

    @Volatile
    private var lastRunningProjectMediaPackages: List<String> = emptyList()

    @Volatile
    private var lastRunningMirroringOverlayPackages: List<String> = emptyList()

    @Volatile
    private var lastRunningDynamicOverlayPackages: List<String> = emptyList()

    @Volatile
    private var lastRunningAsusOverlayPackages: List<String> = emptyList()

    @Volatile
    private var lastActiveMirroringPackageOps: String? = null

    @Volatile
    private var lastProbeAtMs: Long = 0L

    @Volatile
    private var lastAppOpsProbe: AppOpsPackageProbe.DebugState? = null

    @Volatile
    private var lastActiveMediaProjectionPackage: String? = null

    private var appOpsActiveCheckSupported: Boolean? = null
    private var appOpsWatcherSupported: Boolean? = null
    private var internalAppOpsActiveSupported: Boolean? = null
    private var packagesForOpsSupported: Boolean? = null
    private var projectMediaOpCode: Int? = null

    fun getDebugState(context: Context): DebugState {
        val processes =
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .runningAppProcesses
        val appOpsProbe = lastAppOpsProbe
        return DebugState(
            appOpsActiveCheckSupported = isAppOpsActiveCheckSupported(),
            appOpsWatcherSupported = isAppOpsWatcherSupported(),
            internalAppOpsActiveSupported = isInternalAppOpsActiveSupported(),
            packagesForOpsSupported = isPackagesForOpsSupported(),
            appOpsPackageProbeSupported = appOpsProbe?.supported ?: false,
            appOpsProbedPackages = appOpsProbe?.probedPackages ?: emptyList(),
            discoveredMirroringPackages = lastDiscoveredMirroringPackages,
            runningProjectMediaPackages = lastRunningProjectMediaPackages,
            runningMirroringOverlayPackages = lastRunningMirroringOverlayPackages,
            runningDynamicOverlayPackages = lastRunningDynamicOverlayPackages,
            runningAsusOverlayPackages = lastRunningAsusOverlayPackages,
            activeMediaProjectionPackage = lastActiveMediaProjectionPackage,
            activeMirroringPackageOps = lastActiveMirroringPackageOps,
            visibleProcessCount = processes?.size ?: 0,
            heuristicMatch = lastHeuristicMatch,
            heuristicBreakdown = lastHeuristicBreakdown,
            detectionTrigger = lastDetectionTrigger,
            screenRecordingActive = recordingActive,
            lastProbeAtMs = lastProbeAtMs,
        )
    }

    fun refreshState(context: Context) {
        refreshHeuristicState(context)
    }

    fun startMonitoring(activity: Activity) {
        if (monitoringStarted && monitoringActivity == activity) return
        stopMonitoringInternal()

        monitoringActivity = activity
        monitoringStarted = true
        MirroringPackageRegistry.invalidateCache()

        refreshHeuristicState(activity.applicationContext)
        startApi35Callback(activity)
        startAppOpsWatcher(activity.applicationContext)
        startPolling(activity.applicationContext)
    }

    fun stopMonitoring() {
        stopMonitoringInternal()
    }

    private fun stopMonitoringInternal() {
        monitoringStarted = false
        stopPolling()
        stopAppOpsWatcher()
        stopApi35Callback()
        monitoringActivity = null

        recordingActive = false
        lastHeuristicMatch = false
        lastDetectionTrigger = null
        lastHeuristicBreakdown = emptyBreakdown()
    }

    private fun startApi35Callback(activity: Activity) {
        if (Build.VERSION.SDK_INT < API_SCREEN_RECORDING_CALLBACK) return

        try {
            val appContext = activity.applicationContext
            val callback = Consumer<Int> { state ->
                val visible = state == visibleRecordingState()
                if (visible) {
                    updateRecordingState(active = true, trigger = "api35_callback")
                } else {
                    refreshHeuristicState(appContext)
                }
            }
            screenRecordingCallback = callback

            val windowManager = activity.windowManager
            val initialState = invokeAddScreenRecordingCallback(
                windowManager,
                activity.mainExecutor,
                callback,
            )
            if (initialState != null) {
                callback.accept(initialState)
            }
        } catch (_: ReflectiveOperationException) {
            screenRecordingCallback = null
        }
    }

    private fun stopApi35Callback() {
        val activity = monitoringActivity ?: return
        val callback = screenRecordingCallback ?: return
        try {
            invokeRemoveScreenRecordingCallback(activity.windowManager, callback)
        } catch (_: ReflectiveOperationException) {
            // Ignore cleanup failures.
        }
        screenRecordingCallback = null
    }

    private fun invokeAddScreenRecordingCallback(
        windowManager: WindowManager,
        executor: Executor,
        callback: Consumer<Int>,
    ): Int? {
        val method = WindowManager::class.java.getMethod(
            "addScreenRecordingCallback",
            Executor::class.java,
            Consumer::class.java,
        )
        return method.invoke(windowManager, executor, callback) as Int
    }

    private fun invokeRemoveScreenRecordingCallback(
        windowManager: WindowManager,
        callback: Consumer<Int>,
    ) {
        WindowManager::class.java.getMethod(
            "removeScreenRecordingCallback",
            Consumer::class.java,
        ).invoke(windowManager, callback)
    }

    private fun visibleRecordingState(): Int {
        return try {
            WindowManager::class.java.getField("SCREEN_RECORDING_STATE_VISIBLE").getInt(null)
        } catch (_: ReflectiveOperationException) {
            SCREEN_RECORDING_STATE_VISIBLE
        }
    }

    fun isAppOpsActiveCheckSupported(): Boolean {
        appOpsActiveCheckSupported?.let { return it }
        val supported = try {
            AppOpsManager::class.java.getMethod(
                "isOpActive",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            true
        } catch (_: ReflectiveOperationException) {
            false
        }
        appOpsActiveCheckSupported = supported
        return supported
    }

    fun isAppOpsWatcherSupported(): Boolean {
        appOpsWatcherSupported?.let { return it }
        val supported = try {
            AppOpsManager::class.java.getMethod(
                "startWatchingActive",
                Array<String>::class.java,
                Executor::class.java,
                AppOpsManager.OnOpActiveChangedListener::class.java,
            )
            AppOpsManager::class.java.getMethod(
                "stopWatchingActive",
                AppOpsManager.OnOpActiveChangedListener::class.java,
            )
            true
        } catch (_: ReflectiveOperationException) {
            false
        }
        appOpsWatcherSupported = supported
        return supported
    }

    fun isInternalAppOpsActiveSupported(): Boolean {
        internalAppOpsActiveSupported?.let { return it }
        val supported = try {
            val appOpsClass = AppOpsManager::class.java
            appOpsClass.getDeclaredField("mService")
            appOpsClass.getMethod("strOpToOp", String::class.java)
            true
        } catch (_: ReflectiveOperationException) {
            false
        }
        internalAppOpsActiveSupported = supported
        return supported
    }

    fun isPackagesForOpsSupported(): Boolean {
        packagesForOpsSupported?.let { return it }
        val supported = try {
            AppOpsManager::class.java.getMethod(
                "getPackagesForOps",
                Array<String>::class.java,
            )
            true
        } catch (_: ReflectiveOperationException) {
            false
        }
        packagesForOpsSupported = supported
        return supported
    }

    private fun startAppOpsWatcher(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!isAppOpsWatcherSupported()) return

        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return
        val watchedOps = arrayOf(
            AppOpsOps.PROJECT_MEDIA,
            AppOpsOps.SYSTEM_ALERT_WINDOW,
            AppOpsOps.START_FOREGROUND,
        )
        val watcher = AppOpsManager.OnOpActiveChangedListener { op, _, packageName, _ ->
            if (packageName == context.packageName) return@OnOpActiveChangedListener
            if (op == AppOpsOps.PROJECT_MEDIA) {
                refreshHeuristicState(context)
                return@OnOpActiveChangedListener
            }
            if (MirroringPackageRegistry.matchesMirroringPackage(packageName, context)) {
                refreshHeuristicState(context)
            }
        }
        appOpsWatcher = watcher
        try {
            AppOpsManager::class.java.getMethod(
                "startWatchingActive",
                Array<String>::class.java,
                Executor::class.java,
                AppOpsManager.OnOpActiveChangedListener::class.java,
            ).invoke(appOps, watchedOps, context.mainExecutor, watcher)
        } catch (_: ReflectiveOperationException) {
            appOpsWatcher = null
        }
    }

    private fun stopAppOpsWatcher() {
        val activity = monitoringActivity ?: return
        val watcher = appOpsWatcher ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAppOpsWatcherSupported()) {
            val appOps = activity.getSystemService(AppOpsManager::class.java) ?: return
            try {
                AppOpsManager::class.java.getMethod(
                    "stopWatchingActive",
                    AppOpsManager.OnOpActiveChangedListener::class.java,
                ).invoke(appOps, watcher)
            } catch (_: ReflectiveOperationException) {
                // Ignore cleanup failures on OEM builds without AppOps active APIs.
            }
        }
        appOpsWatcher = null
    }

    private fun startPolling(context: Context) {
        stopPolling()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                if (monitoringStarted) {
                    refreshHeuristicState(context)
                    handler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        pollHandler = handler
        pollRunnable = runnable
        handler.post(runnable)
    }

    private fun stopPolling() {
        pollRunnable?.let { pollHandler?.removeCallbacks(it) }
        pollHandler = null
        pollRunnable = null
    }

    private fun refreshHeuristicState(context: Context) {
        lastProbeAtMs = System.currentTimeMillis()
        val mirroringApps = MirroringPackageRegistry.mirroringAppPackages(context)
        lastDiscoveredMirroringPackages = MirroringPackageRegistry.discoverMirroringPackages(context)

        val runningPackages = CaptureConfirmation.filterProjectMediaPackages(
            context,
            findRunningPackagesForOp(context, AppOpsOps.PROJECT_MEDIA),
        )
        lastRunningProjectMediaPackages = runningPackages

        val allOverlayPackages = findRunningPackagesForOp(context, AppOpsOps.SYSTEM_ALERT_WINDOW)
        val overlayPackages = allOverlayPackages.filter { mirroringApps.contains(it) }
        lastRunningMirroringOverlayPackages = overlayPackages

        val oemOverlayPackages = overlayPackages.filter { packageName ->
            MirroringPackageRegistry.supportsOverlayCaptureHeuristic(packageName)
        }

        val dynamicOverlayPackages = allOverlayPackages.filter { packageName ->
            MirroringPackageRegistry.looksLikeMirroringPackage(packageName) &&
                !mirroringApps.contains(packageName)
        }
        lastRunningDynamicOverlayPackages = dynamicOverlayPackages

        val asusOverlayPackages = allOverlayPackages.filter { packageName ->
            packageName.contains("asus", ignoreCase = true)
        }
        lastRunningAsusOverlayPackages = asusOverlayPackages

        val mediaProjectionPackage = detectActiveMediaProjectionPackage(context)
        lastActiveMediaProjectionPackage = mediaProjectionPackage

        val activeMirroringPackageOps = detectMirroringPackageActiveOps(context, mirroringApps)
        lastActiveMirroringPackageOps = activeMirroringPackageOps

        val appOpsProbe = AppOpsPackageProbe.probe(context, mirroringApps)
        lastAppOpsProbe = appOpsProbe

        val appOpsCaptureActive = appOpsProbe.activeMirroringCapture &&
            (appOpsProbe.activePackage == null ||
                CaptureConfirmation.isLiveCaptureForPackage(context, appOpsProbe.activePackage))

        val anyActiveProjectMedia = runningPackages.isNotEmpty() || appOpsCaptureActive
        val visibleProcessProjection = detectActiveProjectMediaInVisibleProcesses(context)
        val projectionActive = detectKnownMirroringProjection(context, mirroringApps)
        val systemUiProjection = detectSystemUiProjection(context)
        val mirroringAppOpsActive = detectKnownMirroringViaActiveAppOps(
            context,
            oemOverlayPackages,
            mirroringApps,
        )
        val mediaProjectionActive = mediaProjectionPackage != null
        val mirroringPackageActiveOps = activeMirroringPackageOps != null || appOpsCaptureActive
        val captureServiceMatch = detectActiveCaptureServices(context, mirroringApps)
        val processMatch = detectMirroringProcess(context)
        val serviceMatch = detectMirroringServices(context)
        val keywordProcessMatch = detectProjectionProcessHeuristic(context)
        val foregroundServiceMatch = detectMirroringForegroundServices(context, mirroringApps)

        val breakdown = HeuristicBreakdown(
            projectMediaRunning = anyActiveProjectMedia,
            overlayRunning = oemOverlayPackages.isNotEmpty(),
            dynamicAsusOverlay = dynamicOverlayPackages.isNotEmpty(),
            asusOverlayRunning = asusOverlayPackages.isNotEmpty(),
            mediaProjectionInfo = mediaProjectionActive,
            knownPackageProjection = projectionActive,
            mirroringPackageActiveOps = mirroringPackageActiveOps,
            systemUiProjection = systemUiProjection,
            mirroringAppOpsCombo = mirroringAppOpsActive,
            captureServiceMatch = captureServiceMatch,
            processMatch = processMatch,
            serviceMatch = serviceMatch,
            keywordProcessMatch = keywordProcessMatch,
            foregroundServiceMatch = foregroundServiceMatch,
        )
        lastHeuristicBreakdown = breakdown

        // Only fire on capture-specific signals. Weak "app is running" heuristics caused
        // false positives when Meet/Zoom calls stay open after screen share ends.
        val trigger = when {
            runningPackages.isNotEmpty() -> "project_media_running"
            mediaProjectionActive -> "media_projection_info"
            projectionActive -> "known_package_projection"
            systemUiProjection -> "system_ui_projection"
            visibleProcessProjection -> "visible_process_projection"
            appOpsCaptureActive -> "appops_package_capture"
            captureServiceMatch -> "capture_service"
            oemOverlayPackages.isNotEmpty() -> "mirroring_overlay"
            asusOverlayPackages.isNotEmpty() -> "asus_overlay"
            dynamicOverlayPackages.isNotEmpty() &&
                dynamicOverlayPackages.any { MirroringPackageRegistry.supportsOverlayCaptureHeuristic(it) } ->
                "dynamic_asus_overlay"
            mirroringPackageActiveOps -> "mirroring_package_active_ops"
            mirroringAppOpsActive -> "mirroring_appops_combo"
            else -> null
        }

        val active = trigger != null
        lastHeuristicMatch = active
        lastDetectionTrigger = trigger

        if (active != recordingActive) {
            if (active) {
                Log.i(
                    LOG_TAG,
                    "Screen sharing ON trigger=$trigger packages=$runningPackages " +
                        "overlay=$overlayPackages asusOverlay=$asusOverlayPackages " +
                        "mediaProjection=$mediaProjectionPackage activeOps=$activeMirroringPackageOps " +
                        "appOpsProbe=${appOpsProbe.activePackage} breakdown=$breakdown",
                )
            } else {
                Log.i(LOG_TAG, "Screen sharing OFF")
            }
        }
        updateRecordingState(active, trigger)
    }

    private fun findRunningPackagesForOp(context: Context, opName: String): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()

        val fromPublic = findRunningPackagesForOpViaPublicApi(context, opName)
        if (fromPublic.isNotEmpty()) return fromPublic

        return findRunningPackagesForOpViaInternalService(context, opName)
    }

    private fun findRunningPackagesForOpViaPublicApi(
        context: Context,
        opName: String,
    ): List<String> {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return emptyList()
        val ownPackage = context.packageName
        if (!isPackagesForOpsSupported()) return emptyList()

        return try {
            val method = AppOpsManager::class.java.getMethod(
                "getPackagesForOps",
                Array<String>::class.java,
            )
            val packages = method.invoke(appOps, arrayOf(opName)) as? List<*>
                ?: return emptyList()
            extractActivePackagesFromPackageOpsList(packages, ownPackage, opName)
        } catch (_: ReflectiveOperationException) {
            emptyList()
        }
    }

    private fun findRunningPackagesForOpViaInternalService(
        context: Context,
        opName: String,
    ): List<String> {
        if (!isInternalAppOpsActiveSupported()) return emptyList()
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return emptyList()
        val ownPackage = context.packageName

        return try {
            val opCode = resolveAppOpsOpCode(appOps, opName)
            val serviceField = AppOpsManager::class.java.getDeclaredField("mService")
            serviceField.isAccessible = true
            val service = serviceField.get(appOps) ?: return emptyList()

            val method = service.javaClass.getMethod(
                "getPackagesForOps",
                IntArray::class.java,
            )
            val packages = method.invoke(service, intArrayOf(opCode)) as? List<*>
                ?: return emptyList()
            extractActivePackagesFromPackageOpsList(packages, ownPackage, opName)
        } catch (_: ReflectiveOperationException) {
            emptyList()
        }
    }

    /**
     * GlideX / ASUS Glide often set PROJECT_MEDIA to "ignore", so active capture
     * is only visible through overlay / foreground-service AppOps while mirroring.
     */
    private fun detectKnownMirroringViaActiveAppOps(
        context: Context,
        oemOverlayPackages: List<String>,
        mirroringApps: List<String>,
    ): Boolean {
        if (oemOverlayPackages.isNotEmpty()) return true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        val oemMirroringApps = mirroringApps.filter { packageName ->
            MirroringPackageRegistry.supportsOverlayCaptureHeuristic(packageName)
        }
        val foregroundPackages = findRunningPackagesForOp(context, AppOpsOps.START_FOREGROUND)
            .filter { oemMirroringApps.contains(it) }
        if (foregroundPackages.isEmpty()) return false

        val projectMediaPackages = findRunningPackagesForOp(context, AppOpsOps.PROJECT_MEDIA).toSet()
        val overlayOpsPackages = findRunningPackagesForOp(context, AppOpsOps.SYSTEM_ALERT_WINDOW).toSet()

        return foregroundPackages.any { packageName ->
            packageName in projectMediaPackages || packageName in overlayOpsPackages
        }
    }

    private fun detectSystemUiProjection(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false

        for (packageName in MirroringPackageRegistry.projectionHostPackages()) {
            if (!MirroringPackageRegistry.isMirroringPackageInstalled(context, packageName)) continue
            try {
                val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
                if (isProjectMediaOpActive(appOps, uid, packageName)) {
                    return true
                }
            } catch (_: Exception) {
                continue
            }
        }
        return false
    }

    private fun detectMirroringForegroundServices(
        context: Context,
        mirroringApps: List<String>,
    ): Boolean {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val services = activityManager.getRunningServices(100) ?: return false

            services.any { service ->
                val packageName = service.service.packageName ?: return@any false
                if (!mirroringApps.contains(packageName)) return@any false

                val className = service.service.className?.lowercase() ?: return@any false
                service.foreground &&
                    MIRRORING_SERVICE_KEYWORDS.any { keyword -> className.contains(keyword) }
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun detectMirroringPackageActiveOps(
        context: Context,
        mirroringApps: List<String>,
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
        val packageManager = context.packageManager
        val packagesToCheck =
            (mirroringApps + MirroringPackageRegistry.projectionHostPackages()).distinct()

        for (packageName in packagesToCheck) {
            if (!MirroringPackageRegistry.isMirroringPackageInstalled(context, packageName)) continue
            try {
                val uid = packageManager.getApplicationInfo(packageName, 0).uid
                if (isProjectMediaOpActive(appOps, uid, packageName)) return packageName
                if (MirroringPackageRegistry.supportsOverlayCaptureHeuristic(packageName) &&
                    isOpActiveViaInternal(appOps, AppOpsOps.SYSTEM_ALERT_WINDOW, uid, packageName)
                ) {
                    return packageName
                }
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private val CAPTURE_SERVICE_KEYWORDS = listOf(
        "mediaprojection",
        "screencapture",
        "screencast",
        "screenrecord",
        "virtualdisplay",
    )

    private fun detectActiveCaptureServices(
        context: Context,
        mirroringApps: List<String>,
    ): Boolean {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val services = activityManager.getRunningServices(100) ?: return false

            services.any { service ->
                val packageName = service.service.packageName ?: return@any false
                val className = service.service.className?.lowercase() ?: return@any false
                val relevantPackage = mirroringApps.contains(packageName) ||
                    packageName.contains("asus", ignoreCase = true) ||
                    packageName == "com.android.systemui"
                relevantPackage &&
                    CAPTURE_SERVICE_KEYWORDS.any { keyword -> className.contains(keyword) } &&
                    CaptureConfirmation.isLiveCaptureForPackage(context, packageName)
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun detectActiveMediaProjectionPackage(context: Context): String? {
        return ActiveProjectionProbe.getActivePackage(context)
    }

    private fun extractActivePackagesFromPackageOpsList(
        packages: List<*>,
        ownPackage: String,
        opName: String,
    ): List<String> {
        return packages.mapNotNull { pkgOps ->
            val pkgOpsClass = pkgOps?.javaClass ?: return@mapNotNull null
            val packageName = pkgOpsClass.getMethod("getPackageName").invoke(pkgOps) as? String
                ?: return@mapNotNull null
            if (packageName == ownPackage) return@mapNotNull null
            val ops = pkgOpsClass.getMethod("getOps").invoke(pkgOps) as? List<*>
                ?: return@mapNotNull null
            val active = ops.any { opEntry ->
                isOpEntryActive(opEntry, opName)
            }
            if (active) packageName else null
        }
    }

    private fun isOpEntryActive(opEntry: Any?, opName: String): Boolean {
        if (opEntry == null) return false
        return try {
            val opEntryClass = opEntry.javaClass
            opEntryClass.getMethod("isRunning").invoke(opEntry) as? Boolean == true
        } catch (_: Exception) {
            false
        }
    }

    private fun detectActiveProjectMediaInVisibleProcesses(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val ownUid = android.os.Process.myUid()
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses ?: return false

        for (process in processes) {
            if (process.uid == ownUid) continue
            val packages = process.pkgList?.toList() ?: listOf(process.processName)
            for (pkg in packages) {
                if (pkg == context.packageName) continue
                if (isProjectMediaOpActive(appOps, process.uid, pkg)) {
                    return true
                }
            }
        }
        return false
    }

    private fun detectProjectionProcessHeuristic(context: Context): Boolean {
        val ownPackage = context.packageName
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses ?: return false

        return processes.any { process ->
            if (process.processName == ownPackage ||
                process.processName.startsWith("$ownPackage:")
            ) {
                return@any false
            }
            val name = process.processName.lowercase()
            val hasKeyword = PROJECTION_PROCESS_KEYWORDS.any { keyword -> name.contains(keyword) }
            hasKeyword && isRelevantProcessImportance(process.importance)
        }
    }

    private fun detectMirroringProcess(context: Context): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = activityManager.runningAppProcesses ?: return false

        return processes.any { process ->
            val isMirroringPackage =
                MirroringPackageRegistry.matchesMirroringPackage(process.processName, context)
            isMirroringPackage && isMirroringProcessImportance(process.importance)
        }
    }

    private fun detectMirroringServices(context: Context): Boolean {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val services = activityManager.getRunningServices(50) ?: return false

            services.any { service ->
                val packageName = service.service.packageName ?: return@any false
                if (!MirroringPackageRegistry.matchesMirroringPackage(packageName, context)) {
                    return@any false
                }
                val className = service.service.className?.lowercase() ?: return@any false
                MIRRORING_SERVICE_KEYWORDS.any { keyword -> className.contains(keyword) }
            }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isRelevantProcessImportance(importance: Int): Boolean {
        return importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private fun isMirroringProcessImportance(importance: Int): Boolean {
        return importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE
    }

    private fun detectKnownMirroringProjection(
        context: Context,
        candidatePackages: List<String>,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val packageManager = context.packageManager

        for (packageName in candidatePackages) {
            try {
                val uid = packageManager.getApplicationInfo(packageName, 0).uid
                if (isProjectMediaOpActive(appOps, uid, packageName)) {
                    return true
                }
            } catch (_: Exception) {
                continue
            }
        }

        return false
    }

    private fun isProjectMediaOpActive(
        appOps: AppOpsManager,
        uid: Int,
        packageName: String,
    ): Boolean {
        if (isAppOpsActiveCheckSupported()) {
            return try {
                val method = AppOpsManager::class.java.getMethod(
                    "isOpActive",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
                method.invoke(appOps, AppOpsOps.PROJECT_MEDIA, uid, packageName) as Boolean
            } catch (_: ReflectiveOperationException) {
                false
            }
        }

        return isProjectMediaOpActiveViaInternalService(appOps, uid, packageName)
    }

    private fun isOpActiveViaInternal(
        appOps: AppOpsManager,
        opName: String,
        uid: Int,
        packageName: String,
    ): Boolean {
        if (!isInternalAppOpsActiveSupported()) return false

        return try {
            val opCode = resolveAppOpsOpCode(appOps, opName)
            val serviceField = AppOpsManager::class.java.getDeclaredField("mService")
            serviceField.isAccessible = true
            val service = serviceField.get(appOps) ?: return false

            val isOperationActive = service.javaClass.getMethod(
                "isOperationActive",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            isOperationActive.invoke(service, opCode, uid, packageName) as Boolean
        } catch (_: ReflectiveOperationException) {
            false
        }
    }

    private fun isProjectMediaOpActiveViaInternalService(
        appOps: AppOpsManager,
        uid: Int,
        packageName: String,
    ): Boolean {
        return isOpActiveViaInternal(appOps, AppOpsOps.PROJECT_MEDIA, uid, packageName)
    }

    private fun resolveAppOpsOpCode(appOps: AppOpsManager, opName: String): Int {
        if (opName == AppOpsOps.PROJECT_MEDIA) {
            projectMediaOpCode?.let { return it }
        }
        val code = try {
            AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
                .invoke(appOps, opName) as Int
        } catch (_: ReflectiveOperationException) {
            when (opName) {
                AppOpsOps.PROJECT_MEDIA -> 46
                AppOpsOps.SYSTEM_ALERT_WINDOW -> 24
                AppOpsOps.START_FOREGROUND -> 76
                AppOpsOps.WAKE_LOCK -> 40
                else -> -1
            }
        }
        if (opName == AppOpsOps.PROJECT_MEDIA) {
            projectMediaOpCode = code
        }
        return code
    }

    private fun emptyBreakdown(): HeuristicBreakdown {
        return HeuristicBreakdown(
            projectMediaRunning = false,
            overlayRunning = false,
            dynamicAsusOverlay = false,
            asusOverlayRunning = false,
            mediaProjectionInfo = false,
            knownPackageProjection = false,
            mirroringPackageActiveOps = false,
            systemUiProjection = false,
            mirroringAppOpsCombo = false,
            captureServiceMatch = false,
            processMatch = false,
            serviceMatch = false,
            keywordProcessMatch = false,
            foregroundServiceMatch = false,
        )
    }

    private fun updateRecordingState(active: Boolean, trigger: String?) {
        if (recordingActive == active && (!active || lastDetectionTrigger == trigger)) return
        recordingActive = active
        lastDetectionTrigger = if (active) trigger else null
    }
}

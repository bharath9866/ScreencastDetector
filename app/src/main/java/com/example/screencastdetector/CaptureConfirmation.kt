package com.example.screencastdetector

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Confirms that a package is actively capturing the screen, not just running a call/meeting.
 *
 * On API 29 Meet (tachyon) sets PROJECT_MEDIA to ignore and may leave orphaned
 * HangoutsScreenCapture virtual displays after share ends. For conferencing apps we require
 * an active MediaProjection session and/or PROJECT_MEDIA op.
 */
internal object CaptureConfirmation {
    private val CONFERENCING_PACKAGES = setOf(
        "com.google.android.apps.meetings",
        "com.google.android.apps.tachyon",
        "com.google.meet",
        "us.zoom.videomeetings",
        "com.microsoft.teams",
        "com.discord",
    )

    private val CONFERENCING_CAPTURE_DISPLAY_NAMES = listOf(
        "screencapture",
        "hangoutsscreencapture",
        "meetscreencapture",
    )

    fun requiresStrictCaptureConfirmation(packageName: String): Boolean {
        return packageName in CONFERENCING_PACKAGES
    }

    fun isConferencingCaptureDisplay(displayName: String?): Boolean {
        val name = displayName?.lowercase().orEmpty()
        return CONFERENCING_CAPTURE_DISPLAY_NAMES.any { keyword -> name.contains(keyword) }
    }

    fun parseOwnerFromDisplayUniqueId(uniqueId: String?): String? {
        if (uniqueId == null || !uniqueId.startsWith("virtual:")) return null
        return uniqueId.split(',').getOrNull(0)?.removePrefix("virtual:")
    }

    /** True when [packageName] is allowed to contribute to a detection right now. */
    fun isLiveCaptureForPackage(context: Context, packageName: String): Boolean {
        if (!requiresStrictCaptureConfirmation(packageName)) return true
        return isProjectMediaOpActive(context, packageName) ||
            hasActiveMediaProjectionForPackage(context, packageName)
    }

    fun filterProjectMediaPackages(context: Context, packages: List<String>): List<String> {
        return packages.filter { packageName -> isLiveCaptureForPackage(context, packageName) }
    }

    fun hasActiveProjectMediaAmongMirroringApps(context: Context): Boolean {
        for (packageName in MirroringPackageRegistry.mirroringAppPackages(context)) {
            if (isLiveCaptureForPackage(context, packageName)) return true
        }
        return false
    }

    fun isHiddenDisplayThreat(context: Context, hiddenDisplay: HiddenDisplayDetector.DebugState): Boolean {
        if (!hiddenDisplay.hiddenVirtualDisplayActive) return false

        if (isGeminiScreenSharingDisplayThreat(hiddenDisplay)) {
            return true
        }

        // Orphaned Meet capture displays remain after share ends; notification listener is authoritative.
        if (hiddenDisplay.virtualDisplayNames.isNotEmpty() &&
            hiddenDisplay.virtualDisplayNames.all { isConferencingCaptureDisplay(it) }
        ) {
            return false
        }

        if (hiddenDisplay.virtualDisplayOwners.isEmpty()) {
            if (hiddenDisplay.virtualDisplayNames.any { isConferencingCaptureDisplay(it) }) {
                return false
            }
            return true
        }
        return hiddenDisplay.virtualDisplayOwners.any { owner ->
            isLiveCaptureForPackage(context, owner)
        }
    }

    fun isConferencingOwnedDisplayThreat(
        context: Context,
        ownerPackage: String?,
        displayName: String?,
    ): Boolean {
        val owner = ownerPackage ?: inferConferencingOwner(displayName) ?: return false
        if (!requiresStrictCaptureConfirmation(owner)) return true
        if (!isConferencingCaptureDisplay(displayName)) return true
        return hasActiveMediaProjectionForPackage(context, owner)
    }

    private fun inferConferencingOwner(displayName: String?): String? {
        val name = displayName?.lowercase().orEmpty()
        if (name.contains("hangouts") || name.contains("meet")) {
            return "com.google.android.apps.tachyon"
        }
        return null
    }

    fun gateMediaProjectionService(
        context: Context,
        state: MediaProjectionServiceProbe.DebugState,
    ): MediaProjectionServiceProbe.DebugState {
        if (!state.active || state.activePackage == null) return state
        if (GeminiLivePackages.isGeminiPackage(state.activePackage)) {
            // Mic-only Live does not hold TYPE_SCREEN_CAPTURE; when it does, treat as screen share.
            return state
        }
        if (isLiveCaptureForPackage(context, state.activePackage)) return state
        return state.copy(active = false, activePackage = null)
    }

    fun isGeminiScreenSharingDisplayThreat(hiddenDisplay: HiddenDisplayDetector.DebugState): Boolean {
        if (hiddenDisplay.virtualDisplayNames.any { name ->
                GeminiLivePackages.isGeminiCaptureDisplay(name, null)
            }
        ) {
            return hiddenDisplay.virtualDisplayOwners.any { GeminiLivePackages.isGeminiPackage(it) } ||
                hiddenDisplay.virtualDisplayOwners.isEmpty()
        }
        return false
    }

    fun hasActiveMediaProjectionForPackage(context: Context, packageName: String): Boolean {
        return ActiveProjectionProbe.hasActiveProjectionForPackage(context, packageName)
    }

    fun hasAnyActiveMediaProjection(context: Context): Boolean {
        return ActiveProjectionProbe.hasAnyActiveProjection(context)
    }

    fun isProjectMediaOpActive(context: Context, packageName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return try {
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            isProjectMediaOpActive(appOps, uid, packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun isProjectMediaOpActive(
        appOps: AppOpsManager,
        uid: Int,
        packageName: String,
    ): Boolean {
        if (isAppOpsActiveCheckSupported()) {
            return try {
                AppOpsManager::class.java.getMethod(
                    "isOpActive",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                ).invoke(appOps, AppOpsOps.PROJECT_MEDIA, uid, packageName) as Boolean
            } catch (_: ReflectiveOperationException) {
                false
            }
        }
        return isProjectMediaOpActiveViaInternalService(appOps, uid, packageName)
    }

    private fun isProjectMediaOpActiveViaInternalService(
        appOps: AppOpsManager,
        uid: Int,
        packageName: String,
    ): Boolean {
        return try {
            val opCode = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
                .invoke(appOps, AppOpsOps.PROJECT_MEDIA) as Int
            val serviceField = AppOpsManager::class.java.getDeclaredField("mService")
            serviceField.isAccessible = true
            val service = serviceField.get(appOps) ?: return false
            service.javaClass.getMethod(
                "isOperationActive",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            ).invoke(service, opCode, uid, packageName) as Boolean
        } catch (_: ReflectiveOperationException) {
            false
        }
    }

    private fun isAppOpsActiveCheckSupported(): Boolean {
        return try {
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
    }
}

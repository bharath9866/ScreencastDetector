package com.example.screencastdetector

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Queries active AppOps for specific mirroring packages via IAppOpsService.getOpsForPackage.
 * This works on OEM builds where getPackagesForOps returns empty to third-party apps.
 */
object AppOpsPackageProbe {
    private const val LOG_TAG = "ScreencastDetector"

    data class PackageOpsState(
        val packageName: String,
        val runningOps: List<String>,
    )

    data class DebugState(
        val supported: Boolean,
        val probedPackages: List<PackageOpsState>,
        val activeMirroringCapture: Boolean,
        val activePackage: String?,
    )

    fun probe(context: Context, packageNames: List<String>): DebugState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return emptyState(supported = false)
        }

        val probed = packageNames.mapNotNull { packageName ->
            probePackage(context, packageName)?.let { ops ->
                PackageOpsState(packageName, ops)
            }
        }

        val activeEntry = probed.firstOrNull { entry ->
            isCaptureActive(context, entry.packageName, entry.runningOps)
        }
        val state = DebugState(
            supported = isGetOpsForPackageSupported(context),
            probedPackages = probed,
            activeMirroringCapture = activeEntry != null,
            activePackage = activeEntry?.packageName,
        )

        Log.i(
            LOG_TAG,
            "AppOpsPackageProbe active=${state.activeMirroringCapture} " +
                "package=${state.activePackage} probed=${formatProbed(probed)}",
        )
        return state
    }

    private fun formatProbed(probed: List<PackageOpsState>): String {
        return probed.joinToString { entry ->
            "${entry.packageName}=[${entry.runningOps.joinToString()}]"
        }
    }

    /**
     * PROJECT_MEDIA always indicates capture. Overlay + foreground is only treated as capture
     * for OEM mirroring apps (GlideX) where PROJECT_MEDIA is often hidden from third-party apps.
     */
    private fun isCaptureActive(
        context: Context,
        packageName: String,
        runningOps: List<String>,
    ): Boolean {
        if (AppOpsOps.PROJECT_MEDIA in runningOps) {
            return CaptureConfirmation.isLiveCaptureForPackage(context, packageName)
        }
        if (!MirroringPackageRegistry.supportsOverlayCaptureHeuristic(packageName)) return false
        val hasOverlay = AppOpsOps.SYSTEM_ALERT_WINDOW in runningOps
        val hasForeground = AppOpsOps.START_FOREGROUND in runningOps
        return hasOverlay && hasForeground
    }

    private fun probePackage(context: Context, packageName: String): List<String>? {
        if (!MirroringPackageRegistry.isMirroringPackageInstalled(context, packageName)) return null

        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            val opNames = arrayOf(
                AppOpsOps.PROJECT_MEDIA,
                AppOpsOps.SYSTEM_ALERT_WINDOW,
                AppOpsOps.START_FOREGROUND,
                AppOpsOps.WAKE_LOCK,
            )
            val entries = getOpsForPackage(appOps, uid, packageName, opNames) ?: return emptyList()
            if (packageName == "com.asus.glidex") {
                Log.i(LOG_TAG, "GlideX raw AppOps entries count=${entries.size} detail=$entries")
            }
            entries.mapNotNull { entry -> entry.first.takeIf { entry.second } }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun getOpsForPackage(
        appOps: AppOpsManager,
        uid: Int,
        packageName: String,
        opNames: Array<String>,
    ): List<Pair<String, Boolean>>? {
        val fromManager = getOpsForPackageViaManager(appOps, packageName, opNames)
        if (fromManager != null) return fromManager

        val service = getAppOpsService(appOps) ?: return null
        return getOpsForPackageViaService(service, uid, packageName, opNames)
    }

    private fun getOpsForPackageViaManager(
        appOps: AppOpsManager,
        packageName: String,
        opNames: Array<String>,
    ): List<Pair<String, Boolean>>? {
        return try {
            val method = AppOpsManager::class.java.getMethod(
                "getOpsForPackage",
                String::class.java,
                Array<String>::class.java,
            )
            val entries = method.invoke(appOps, packageName, opNames) as? List<*>
                ?: return emptyList()
            parseOpEntries(entries)
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun getOpsForPackageViaService(
        service: Any,
        uid: Int,
        packageName: String,
        opNames: Array<String>,
    ): List<Pair<String, Boolean>>? {
        return try {
            val method = service.javaClass.getMethod(
                "getOpsForPackage",
                Int::class.javaPrimitiveType,
                String::class.java,
                Array<String>::class.java,
            )
            val entries = method.invoke(service, uid, packageName, opNames) as? List<*>
                ?: return emptyList()
            parseOpEntries(entries)
        } catch (_: ReflectiveOperationException) {
            try {
                val method = service.javaClass.getMethod(
                    "getOpsForPackage",
                    String::class.java,
                    Array<String>::class.java,
                )
                val entries = method.invoke(service, packageName, opNames) as? List<*>
                    ?: return emptyList()
                parseOpEntries(entries)
            } catch (_: ReflectiveOperationException) {
                null
            }
        }
    }

    private fun parseOpEntries(entries: List<*>): List<Pair<String, Boolean>> {
        return entries.mapNotNull { entry ->
            val entryClass = entry?.javaClass ?: return@mapNotNull null
            val opName = try {
                entryClass.getMethod("getOpStr").invoke(entry) as? String
            } catch (_: ReflectiveOperationException) {
                try {
                    entryClass.getMethod("getOp").invoke(entry)?.toString()
                } catch (_: ReflectiveOperationException) {
                    null
                }
            } ?: return@mapNotNull null
            opName to isOpEntryRunning(entry)
        }
    }

    private fun isOpEntryRunning(entry: Any): Boolean {
        val entryClass = entry.javaClass
        val isRunning = try {
            entryClass.getMethod("isRunning").invoke(entry) as? Boolean == true
        } catch (_: ReflectiveOperationException) {
            false
        }
        if (isRunning) return true

        return try {
            val field = entryClass.getDeclaredField("mRunning")
            field.isAccessible = true
            field.getBoolean(entry)
        } catch (_: ReflectiveOperationException) {
            false
        }
    }

    private fun getAppOpsService(appOps: AppOpsManager): Any? {
        return try {
            val serviceField = AppOpsManager::class.java.getDeclaredField("mService")
            serviceField.isAccessible = true
            serviceField.get(appOps)
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun isGetOpsForPackageSupported(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val service = getAppOpsService(appOps) ?: return false
        return try {
            service.javaClass.getMethod(
                "getOpsForPackage",
                Int::class.javaPrimitiveType,
                String::class.java,
                Array<String>::class.java,
            )
            true
        } catch (_: ReflectiveOperationException) {
            false
        }
    }

    private fun emptyState(supported: Boolean): DebugState {
        return DebugState(
            supported = supported,
            probedPackages = emptyList(),
            activeMirroringCapture = false,
            activePackage = null,
        )
    }
}

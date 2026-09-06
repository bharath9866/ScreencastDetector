package com.example.screencastdetector

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log

/** Reads the active MediaProjection owner via hidden service APIs (required on API 29). */
internal object ActiveProjectionProbe {
    private const val LOG_TAG = "ScreencastDetector"

    fun getActivePackage(context: Context): String? {
        val ownPackage = context.packageName
        for (probe in listOf(
            { probeViaManagerService(context) },
            { probeViaPublicManagerApi(context) },
            { probeViaServiceManagerBinder(context) },
        )) {
            val packageName = try {
                probe()
            } catch (e: ReflectiveOperationException) {
                Log.i(LOG_TAG, "ActiveProjectionProbe failed: ${e.message}")
                null
            } catch (_: SecurityException) {
                null
            }
            if (packageName != null && packageName != ownPackage) {
                return packageName
            }
        }
        return null
    }

    fun hasActiveProjectionForPackage(context: Context, packageName: String): Boolean {
        return getActivePackage(context) == packageName
    }

    fun hasAnyActiveProjection(context: Context): Boolean {
        return getActivePackage(context) != null
    }

    private fun probeViaPublicManagerApi(context: Context): String? {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return null
        val info = projectionManager.javaClass.getMethod("getActiveProjectionInfo")
            .invoke(projectionManager) ?: return null
        return extractPackageName(info)
    }

    private fun probeViaManagerService(context: Context): String? {
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                ?: return null
        val serviceField = MediaProjectionManager::class.java.getDeclaredField("mService")
        serviceField.isAccessible = true
        val service = serviceField.get(projectionManager) ?: return null
        val info = service.javaClass.getMethod("getActiveProjectionInfo").invoke(service) ?: return null
        return extractPackageName(info)
    }

    private fun probeViaServiceManagerBinder(context: Context): String? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java)
            .invoke(null, "media_projection") as? IBinder ?: return null

        val stubClass = Class.forName("android.media.projection.IMediaProjectionManager\$Stub")
        val asInterface = stubClass.declaredMethods.firstOrNull { method ->
            method.name == "asInterface" && method.parameterTypes.size == 1
        } ?: return null
        val projectionService = asInterface.invoke(null, binder) ?: return null
        val info = projectionService.javaClass.getMethod("getActiveProjectionInfo").invoke(projectionService)
            ?: return null
        return extractPackageName(info)
    }

    private fun extractPackageName(info: Any): String? {
        return info.javaClass.getMethod("getPackageName").invoke(info) as? String
    }
}

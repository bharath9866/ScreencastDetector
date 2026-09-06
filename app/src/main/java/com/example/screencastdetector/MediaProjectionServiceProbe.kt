package com.example.screencastdetector

import android.content.Context
import android.os.IBinder
import android.util.Log

/**
 * Reads active MediaProjection sessions via IMediaProjectionManager service binder.
 * dumpsys media_projection exposes this to the shell; some builds allow read via binder.
 */
object MediaProjectionServiceProbe {
    private const val LOG_TAG = "ScreencastDetector"

    data class DebugState(
        val supported: Boolean,
        val activePackage: String?,
        val active: Boolean,
    )

    fun probe(context: Context): DebugState {
        val ownPackage = context.packageName
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "media_projection") ?: return inactive(supported = false)

            val stubClass = Class.forName("android.media.projection.IMediaProjectionManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val projectionService = asInterface.invoke(null, binder) ?: return inactive(supported = false)

            val info = try {
                projectionService.javaClass.getMethod("getActiveProjectionInfo")
                    .invoke(projectionService)
            } catch (_: SecurityException) {
                null
            }

            if (info == null) {
                Log.i(LOG_TAG, "MediaProjectionServiceProbe: getActiveProjectionInfo returned null")
                return inactive(supported = true)
            }

            val packageName = info.javaClass.getMethod("getPackageName").invoke(info) as? String
            val active = packageName != null && packageName != ownPackage
            Log.i(
                LOG_TAG,
                "MediaProjectionServiceProbe: package=$packageName active=$active",
            )
            DebugState(
                supported = true,
                activePackage = if (active) packageName else null,
                active = active,
            )
        } catch (e: ReflectiveOperationException) {
            Log.i(LOG_TAG, "MediaProjectionServiceProbe unsupported: ${e.message}")
            inactive(supported = false)
        }
    }

    private fun inactive(supported: Boolean): DebugState {
        return DebugState(
            supported = supported,
            activePackage = null,
            active = false,
        )
    }
}

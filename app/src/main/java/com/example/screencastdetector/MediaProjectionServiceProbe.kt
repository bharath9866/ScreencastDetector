package com.example.screencastdetector

import android.content.Context
import android.util.Log

/**
 * Reads active MediaProjection sessions via [ActiveProjectionProbe].
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
            val packageName = ActiveProjectionProbe.getActivePackage(context)
            if (packageName == null) {
                Log.i(LOG_TAG, "MediaProjectionServiceProbe: no active projection")
                return inactive(supported = true)
            }
            val active = packageName != ownPackage
            Log.i(LOG_TAG, "MediaProjectionServiceProbe: package=$packageName active=$active")
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

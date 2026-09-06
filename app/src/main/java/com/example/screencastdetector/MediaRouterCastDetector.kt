package com.example.screencastdetector

import android.content.Context
import android.media.MediaRouter
import android.os.Build

/**
 * Detects active cast/mirror routes via [MediaRouter] when the system exposes a
 * non-default live-video route (common on some OEM screencast implementations).
 */
object MediaRouterCastDetector {
    data class DebugState(
        val supported: Boolean,
        val routeCount: Int,
        val selectedRouteName: String?,
        val selectedRouteIsDefault: Boolean,
        val castRouteActive: Boolean,
    )

    fun probe(context: Context): DebugState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return inactiveState(supported = false)
        }

        return try {
            val router = context.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
            val routes = invokeGetRoutes(router)
            val selectedRoute = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
            val isDefault = invokeRouteIsDefault(selectedRoute)
            val playbackType = invokeRoutePlaybackType(selectedRoute)
            val routeName = invokeRouteName(selectedRoute)?.lowercase().orEmpty()
            val looksLikeCastRoute = routeName.contains("cast") ||
                routeName.contains("mirror") ||
                routeName.contains("glide") ||
                routeName.contains("display")
            val castActive = !isDefault &&
                playbackType == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE &&
                routes.size > 1 &&
                looksLikeCastRoute

            DebugState(
                supported = true,
                routeCount = routes.size,
                selectedRouteName = invokeRouteName(selectedRoute),
                selectedRouteIsDefault = isDefault,
                castRouteActive = castActive,
            )
        } catch (_: Exception) {
            inactiveState(supported = false)
        }
    }

    private fun inactiveState(supported: Boolean): DebugState {
        return DebugState(
            supported = supported,
            routeCount = 0,
            selectedRouteName = null,
            selectedRouteIsDefault = true,
            castRouteActive = false,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeGetRoutes(router: MediaRouter): List<MediaRouter.RouteInfo> {
        return MediaRouter::class.java.getMethod("getRoutes").invoke(router) as List<MediaRouter.RouteInfo>
    }

    private fun invokeRouteIsDefault(route: MediaRouter.RouteInfo): Boolean {
        return MediaRouter.RouteInfo::class.java.getMethod("isDefault").invoke(route) as Boolean
    }

    private fun invokeRoutePlaybackType(route: MediaRouter.RouteInfo): Int {
        return MediaRouter.RouteInfo::class.java.getMethod("getPlaybackType").invoke(route) as Int
    }

    private fun invokeRouteName(route: MediaRouter.RouteInfo): String? {
        return try {
            MediaRouter.RouteInfo::class.java.getMethod("getName").invoke(route)?.toString()
        } catch (_: ReflectiveOperationException) {
            null
        }
    }
}

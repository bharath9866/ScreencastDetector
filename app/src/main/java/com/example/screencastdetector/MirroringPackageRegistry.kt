package com.example.screencastdetector

import android.content.Context
import android.content.pm.PackageManager

/**
 * Known mirroring packages plus runtime discovery of OEM-specific cast/mirror apps
 * (e.g. ASUS Glide vs GlideX naming differences across builds).
 */
object MirroringPackageRegistry {
    private val STATIC_MIRRORING_PACKAGES = listOf(
        "com.asus.glidex",
        "com.asus.glide",
        "com.asus.pccontrol",
        "com.asus.linkmaster",
        "com.asus.screenrecorder",
        "com.asus.dm",
        "com.samsung.android.smartmirroring",
        "com.samsung.android.galaxycontinuity",
        "com.google.android.apps.meetings",
        "us.zoom.videomeetings",
        "com.microsoft.teams",
        "com.microsoft.appmanager",
        "com.discord",
        "com.lenovo.levoice.caption",
        "com.lenovo.ota",
        "com.lenovo.motorola",
        "com.motorola.mobiledesktop",
    )

    /** Packages that may hold MediaProjection on behalf of a cast tile (OEM builds). */
    private val PROJECTION_HOST_PACKAGES = listOf(
        "com.android.systemui",
        "com.asus.systemui",
    )

    private val PACKAGE_KEYWORDS = listOf(
        "glide",
        "glidex",
        "cast",
        "mirror",
        "projection",
        "pccontrol",
        "mobiledesktop",
        "screenrecord",
        "screencap",
        "linkmaster",
    )

    @Volatile
    private var cachedDiscoveredPackages: List<String>? = null

    fun projectionHostPackages(): List<String> = PROJECTION_HOST_PACKAGES

    fun mirroringAppPackages(context: Context): List<String> {
        return (STATIC_MIRRORING_PACKAGES + discoverMirroringPackages(context))
            .distinct()
            .sorted()
    }

    fun allCandidatePackages(context: Context): List<String> {
        return (mirroringAppPackages(context) + PROJECTION_HOST_PACKAGES)
            .distinct()
            .sorted()
    }

    fun discoverMirroringPackages(context: Context): List<String> {
        cachedDiscoveredPackages?.let { return it }

        val discovered = try {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .map { it.packageName }
                .filter { packageName -> looksLikeMirroringPackage(packageName) }
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }

        cachedDiscoveredPackages = discovered
        return discovered
    }

    fun invalidateCache() {
        cachedDiscoveredPackages = null
    }

    fun matchesMirroringPackage(processOrPackageName: String, context: Context): Boolean {
        val name = processOrPackageName.substringBefore(':')
        return allCandidatePackages(context).any { packageName ->
            name == packageName || processOrPackageName.startsWith("$packageName:")
        }
    }

    fun looksLikeMirroringPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        if (STATIC_MIRRORING_PACKAGES.contains(packageName)) return true
        if (PROJECTION_HOST_PACKAGES.contains(packageName)) return true

        val hasVendorHint = lower.contains("asus") ||
            lower.contains("samsung") ||
            lower.contains("lenovo") ||
            lower.contains("motorola") ||
            lower.contains("microsoft")

        if (!hasVendorHint) return false
        return PACKAGE_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }

    fun isMirroringPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}

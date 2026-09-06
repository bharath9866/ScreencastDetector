package com.example.screencastdetector

import android.content.Context
import android.util.Log
import android.view.Display

/**
 * Detects private virtual displays (e.g. GlideXVirtualDisplay) via [DisplayManagerGlobal]
 * reflection. These are hidden from [android.hardware.display.DisplayManager.getDisplays]
 * because of [Display.FLAG_PRIVATE], but remain visible to the global display registry.
 */
object HiddenDisplayDetector {
    private const val LOG_TAG = "ScreencastDetector"
    private const val DISPLAY_TYPE_VIRTUAL = 5

    data class DebugState(
        val supported: Boolean,
        val globalDisplayIds: List<Int>,
        val virtualDisplayNames: List<String>,
        val virtualDisplayOwners: List<String>,
        val hiddenVirtualDisplayActive: Boolean,
    )

    fun probe(context: Context): DebugState {
        return try {
            val global = getDisplayManagerGlobal()
            val fromGlobal = global?.let { probeViaDisplayManagerGlobal(it, context) }
            if (fromGlobal != null) return fromGlobal

            val manager =
                context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            probeViaDisplayManager(manager, context)
        } catch (_: Exception) {
            unsupported()
        }
    }

    private fun probeViaDisplayManager(
        manager: android.hardware.display.DisplayManager,
        context: Context,
    ): DebugState {
        val names = mutableListOf<String>()
        val owners = mutableListOf<String>()
        val ids = mutableListOf<Int>()
        var hasVirtual = false
        val ownPackage = context.packageName

        val allCategories = listOf(
            android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION,
            "android.hardware.display.category.ALL_INCLUDING_DISABLED",
            "android.hardware.display.category.REAR",
        )
        for (category in allCategories) {
            try {
                for (display in manager.getDisplays(category)) {
                    collectHiddenDisplay(display, ownPackage, ids, names, owners)?.let {
                        hasVirtual = true
                    }
                }
            } catch (_: Exception) {
                // Category not supported on this build.
            }
        }

        for (displayId in 0..15) {
            val display = manager.getDisplay(displayId) ?: continue
            collectHiddenDisplay(display, ownPackage, ids, names, owners)?.let { hasVirtual = true }
        }

        return buildState(ids, names, owners, hasVirtual)
    }

    private fun probeViaDisplayManagerGlobal(global: Any, context: Context): DebugState? {
        val ids = getDisplayIds(global)
        if (ids.size <= 1 && ids.singleOrNull() == Display.DEFAULT_DISPLAY) {
            return null
        }

        val names = mutableListOf<String>()
        val owners = mutableListOf<String>()
        var hasVirtual = false
        val ownPackage = context.packageName

        for (id in ids) {
            if (id == Display.DEFAULT_DISPLAY) continue
            val info = getDisplayInfo(global, id) ?: continue
            val type = getDisplayInfoInt(info, "type")
            val state = getDisplayInfoInt(info, "state")
            val owner = getDisplayInfoString(info, "ownerPackageName")
            val name = getDisplayInfoString(info, "name")
            val uniqueId = getDisplayInfoString(info, "uniqueId")

            if (type != DISPLAY_TYPE_VIRTUAL || state == Display.STATE_OFF) continue

            val ownerPackage = owner ?: parseOwnerFromUniqueId(uniqueId)
            if (ownerPackage == ownPackage) continue

            hasVirtual = true
            name?.let { names.add(it) }
            ownerPackage?.let { owners.add(it) }
        }

        return buildState(ids.toList(), names, owners, hasVirtual)
    }

    private fun buildState(
        ids: List<Int>,
        names: List<String>,
        owners: List<String>,
        hasVirtual: Boolean,
    ): DebugState {
        return DebugState(
            supported = true,
            globalDisplayIds = ids,
            virtualDisplayNames = names,
            virtualDisplayOwners = owners.distinct(),
            hiddenVirtualDisplayActive = hasVirtual,
        ).also { logProbe(it) }
    }

    private fun logProbe(state: DebugState) {
        Log.i(
            LOG_TAG,
            "HiddenDisplay probe ids=${state.globalDisplayIds} " +
                "names=${state.virtualDisplayNames} owners=${state.virtualDisplayOwners} " +
                "active=${state.hiddenVirtualDisplayActive}",
        )
    }

    private fun unsupported(): DebugState {
        return DebugState(
            supported = false,
            globalDisplayIds = emptyList(),
            virtualDisplayNames = emptyList(),
            virtualDisplayOwners = emptyList(),
            hiddenVirtualDisplayActive = false,
        )
    }

    private fun collectHiddenDisplay(
        display: Display,
        ownPackage: String,
        ids: MutableList<Int>,
        names: MutableList<String>,
        owners: MutableList<String>,
    ): Boolean? {
        ids.add(display.displayId)
        if (display.displayId == Display.DEFAULT_DISPLAY || display.state == Display.STATE_OFF) {
            return null
        }

        val name = display.name?.toString()
        val owner = parseOwnerFromDisplayName(name) ?: parseOwnerFromUniqueId(display.toString())
        if (owner == ownPackage) return null

        name?.let { names.add(it) }
        owner?.let { owners.add(it) }
        return true
    }

    private fun parseOwnerFromDisplayName(name: String?): String? {
        if (name?.contains("GlideX", ignoreCase = true) == true) return "com.asus.glidex"
        return null
    }

    private fun getDisplayManagerGlobal(): Any? {
        val clazz = Class.forName("android.hardware.display.DisplayManagerGlobal")
        return clazz.getMethod("getInstance").invoke(null)
    }

    private fun getDisplayIds(global: Any): IntArray {
        return global.javaClass.getMethod("getDisplayIds").invoke(global) as IntArray
    }

    private fun getDisplayInfo(global: Any, displayId: Int): Any? {
        return try {
            global.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(global, displayId)
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    private fun getDisplayInfoInt(info: Any, fieldName: String): Int {
        return try {
            info.javaClass.getField(fieldName).getInt(info)
        } catch (_: ReflectiveOperationException) {
            -1
        }
    }

    private fun getDisplayInfoString(info: Any, fieldName: String): String? {
        return try {
            info.javaClass.getField(fieldName).get(info) as? String
        } catch (_: ReflectiveOperationException) {
            null
        }
    }

    /** e.g. virtual:com.asus.glidex,10154,GlideXVirtualDisplay,0 */
    private fun parseOwnerFromUniqueId(uniqueId: String?): String? {
        if (uniqueId == null || !uniqueId.startsWith("virtual:")) return null
        return uniqueId.split(',').getOrNull(0)?.removePrefix("virtual:")
    }
}

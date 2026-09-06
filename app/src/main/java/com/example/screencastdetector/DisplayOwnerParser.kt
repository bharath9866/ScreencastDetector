package com.example.screencastdetector

import android.view.Display

internal object DisplayOwnerParser {
    private val VIRTUAL_UNIQUE_ID = Regex("""virtual:([^,]+),""")

    fun parseOwnerPackage(display: Display): String? {
        val fromUniqueId = VIRTUAL_UNIQUE_ID.find(display.toString())?.groupValues?.getOrNull(1)
        if (fromUniqueId != null) return fromUniqueId

        val name = display.name?.toString().orEmpty()
        if (name.contains("GlideX", ignoreCase = true)) return "com.asus.glidex"
        if (name.contains("HangoutsScreenCapture", ignoreCase = true)) {
            return "com.google.android.apps.tachyon"
        }
        if (name.contains("ScreenRecorder", ignoreCase = true)) {
            return "com.google.android.googlequicksearchbox"
        }
        return null
    }

    fun looksLikeCaptureVirtualDisplay(display: Display): Boolean {
        if (display.displayId == Display.DEFAULT_DISPLAY || display.state == Display.STATE_OFF) {
            return false
        }

        val name = display.name?.toString().orEmpty().lowercase()
        val text = display.toString().lowercase()
        if (text.contains("virtual:")) return true
        return name.contains("virtualdisplay") ||
            name.contains("screencapture") ||
            name.contains("hangouts") ||
            name.contains("glidex") ||
            name.contains("screenrecorder") ||
            name.contains("screenrecord") ||
            name.contains("mediaprojection")
    }
}

package com.example.screencastdetector

/** Google Gemini Live runs inside the Google app (and optionally the standalone Gemini app). */
internal object GeminiLivePackages {
    val PACKAGES = setOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.bard",
    )

    private const val LIVE_CHANNEL = "convmode_notification_channel_id"
    private const val SCREEN_RECORDER_DISPLAY = "screenrecorder"

    fun isGeminiPackage(packageName: String?): Boolean {
        return packageName != null && packageName in PACKAGES
    }

    fun isGeminiCaptureDisplay(displayName: String?, ownerPackage: String?): Boolean {
        val name = displayName?.lowercase().orEmpty()
        if (!name.contains(SCREEN_RECORDER_DISPLAY)) return false
        return ownerPackage == null || isGeminiPackage(ownerPackage)
    }

    fun isGeminiLiveChannel(channelId: String?): Boolean {
        return channelId == LIVE_CHANNEL
    }
}

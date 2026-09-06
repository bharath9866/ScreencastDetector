package com.example.screencastdetector

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Detects active screen sharing via persistent notifications:
 * - GlideX: "Stop mirroring" notification
 * - Google Meet (tachyon): "You're sharing your screen" / "Stop sharing" notification
 * - Gemini Live (Google app): "Stop sharing screen" on Live with Gemini notification
 *
 * Required on API 29 where MediaProjection and orphaned virtual displays are unreliable.
 */
class CastNotificationListener : NotificationListenerService() {
    data class DebugState(
        val accessEnabled: Boolean,
        val serviceConnected: Boolean,
        val activeMirroringNotifications: Int,
        val mirroringActive: Boolean,
        val activeSources: List<String>,
    )

    companion object {
        private const val LOG_TAG = "ScreencastDetector"
        private const val GLIDEX_PACKAGE = "com.asus.glidex"
        private const val GLIDEX_MIRROR_CHANNEL = "GlideX_High_Notification_Channel_Id"
        private val MEET_PACKAGES = setOf(
            "com.google.android.apps.tachyon",
            "com.google.android.apps.meetings",
            "com.google.meet",
        )
        private val GEMINI_PACKAGES = GeminiLivePackages.PACKAGES

        @Volatile
        private var instance: CastNotificationListener? = null

        private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val component = ComponentName(context, CastNotificationListener::class.java)
            return flat.split(':').any { it.contains(component.flattenToString()) }
        }

        fun openNotificationAccessSettings(context: Context) {
            context.startActivity(
                android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        fun getDebugState(context: Context): DebugState {
            val listener = instance
            val notifications = listener?.activeMirroringNotifications().orEmpty()
            return DebugState(
                accessEnabled = isNotificationAccessEnabled(context),
                serviceConnected = listener != null,
                activeMirroringNotifications = notifications.size,
                mirroringActive = notifications.isNotEmpty(),
                activeSources = notifications.mapNotNull { listener?.sourceLabelFor(it) }.distinct(),
            )
        }

        fun addChangeListener(listener: () -> Unit): () -> Unit {
            changeListeners.add(listener)
            return { changeListeners.remove(listener) }
        }

        private fun notifyChangeListeners() {
            for (listener in changeListeners) {
                listener.invoke()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(LOG_TAG, "CastNotificationListener connected")
        refreshMirroringState()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && isScreenSharingNotification(sbn)) {
            Log.i(
                LOG_TAG,
                "Screen sharing notification posted pkg=${sbn.packageName} id=${sbn.id}",
            )
            notifyChangeListeners()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && isScreenSharingNotification(sbn)) {
            Log.i(
                LOG_TAG,
                "Screen sharing notification removed pkg=${sbn.packageName} id=${sbn.id}",
            )
            notifyChangeListeners()
        }
    }

    private fun refreshMirroringState() {
        val active = activeMirroringNotifications().isNotEmpty()
        Log.i(LOG_TAG, "CastNotificationListener refresh active=$active")
        notifyChangeListeners()
    }

    private fun activeMirroringNotifications(): List<StatusBarNotification> {
        return try {
            activeNotifications?.filter { isScreenSharingNotification(it) }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun isScreenSharingNotification(sbn: StatusBarNotification): Boolean {
        return when (sbn.packageName) {
            GLIDEX_PACKAGE -> isGlideXMirroringNotification(sbn)
            in MEET_PACKAGES -> isMeetScreenSharingNotification(sbn)
            in GEMINI_PACKAGES -> isGeminiScreenSharingNotification(sbn)
            else -> false
        }
    }

    private fun sourceLabelFor(sbn: StatusBarNotification): String? {
        return when (sbn.packageName) {
            GLIDEX_PACKAGE -> "glidex"
            in MEET_PACKAGES -> "meet"
            in GEMINI_PACKAGES -> "gemini"
            else -> null
        }
    }

    private fun isGlideXMirroringNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.channelId == GLIDEX_MIRROR_CHANNEL) return true
        val actions = sbn.notification.actions ?: return false
        return actions.any { action ->
            action.title?.toString()?.contains("mirroring", ignoreCase = true) == true
        }
    }

    private fun isMeetScreenSharingNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras

        val ticker = notification.tickerText?.toString()?.lowercase().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lowercase().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.lowercase().orEmpty()

        if (ticker.contains("screen sharing") ||
            text.contains("sharing your screen") ||
            bigText.contains("sharing your screen")
        ) {
            return true
        }

        val actions = notification.actions ?: return false
        return actions.any { action ->
            action.title?.toString()?.contains("stop sharing", ignoreCase = true) == true
        }
    }

    private fun isGeminiScreenSharingNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras
        val actions = notification.actions.orEmpty()

        if (actions.any { action ->
                val title = action.title?.toString()?.lowercase().orEmpty()
                title.contains("stop sharing") && title.contains("screen")
            }
        ) {
            return true
        }

        val ticker = notification.tickerText?.toString()?.lowercase().orEmpty()
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lowercase().orEmpty()
        val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.lowercase().orEmpty()
        val combined = listOf(ticker, title, text, bigText).joinToString(" ")

        if (combined.contains("screen sharing") ||
            combined.contains("sharing your screen") ||
            combined.contains("sharing screen")
        ) {
            return true
        }

        // Mic-only Live shows "Live with Gemini" without a screen-share action — do not match.
        return false
    }
}

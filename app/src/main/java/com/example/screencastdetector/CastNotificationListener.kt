package com.example.screencastdetector

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Detects active GlideX mirroring via the "Stop mirroring" notification
 * (channel GlideX_High_Notification_Channel_Id), which only appears while casting.
 */
class CastNotificationListener : NotificationListenerService() {
    data class DebugState(
        val accessEnabled: Boolean,
        val serviceConnected: Boolean,
        val activeMirroringNotifications: Int,
        val mirroringActive: Boolean,
    )

    companion object {
        private const val LOG_TAG = "ScreencastDetector"
        private const val GLIDEX_PACKAGE = "com.asus.glidex"
        private const val GLIDEX_MIRROR_CHANNEL = "GlideX_High_Notification_Channel_Id"

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
        if (sbn != null && isMirroringNotification(sbn)) {
            Log.i(LOG_TAG, "GlideX mirroring notification posted id=${sbn.id}")
            notifyChangeListeners()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && isMirroringNotification(sbn)) {
            Log.i(LOG_TAG, "GlideX mirroring notification removed id=${sbn.id}")
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
            activeNotifications?.filter { isMirroringNotification(it) }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun isMirroringNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != GLIDEX_PACKAGE) return false

        val channelId = sbn.notification.channelId
        if (channelId == GLIDEX_MIRROR_CHANNEL) return true

        val actions = sbn.notification.actions ?: return false
        return actions.any { action ->
            action.title?.toString()?.contains("mirroring", ignoreCase = true) == true
        }
    }
}

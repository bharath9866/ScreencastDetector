package com.example.screencastdetector

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var statusBanner: TextView
    private lateinit var statusReason: TextView
    private lateinit var debugPanel: TextView
    private lateinit var refreshButton: Button
    private lateinit var notificationAccessButton: Button
    private lateinit var notificationAccessHint: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateUi()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private var notificationListenerUnregister: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusBanner = findViewById(R.id.statusBanner)
        statusReason = findViewById(R.id.statusReason)
        debugPanel = findViewById(R.id.debugPanel)
        refreshButton = findViewById(R.id.refreshButton)
        notificationAccessButton = findViewById(R.id.notificationAccessButton)
        notificationAccessHint = findViewById(R.id.notificationAccessHint)

        refreshButton.setOnClickListener { updateUi() }
        notificationAccessButton.setOnClickListener {
            CastNotificationListener.openNotificationAccessSettings(this)
        }

        ScreencastProbe.startMonitoring(this)
    }

    override fun onResume() {
        super.onResume()
        notificationListenerUnregister?.invoke()
        notificationListenerUnregister = CastNotificationListener.addChangeListener {
            handler.post { updateUi() }
        }
        updateNotificationAccessUi()
        updateUi()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        notificationListenerUnregister?.invoke()
        notificationListenerUnregister = null
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        ScreencastProbe.stopMonitoring()
        super.onDestroy()
    }

    private fun updateNotificationAccessUi() {
        val enabled = CastNotificationListener.isNotificationAccessEnabled(this)
        val visibility = if (enabled) View.GONE else View.VISIBLE
        notificationAccessButton.visibility = visibility
        notificationAccessHint.visibility = visibility
    }

    private fun updateUi() {
        updateNotificationAccessUi()
        val result = ScreencastProbe.probe(applicationContext)

        if (result.detected) {
            statusBanner.text = getString(R.string.status_detected)
            statusBanner.setTextColor(
                ContextCompat.getColor(this, R.color.status_detected),
            )
            statusReason.visibility = View.VISIBLE
            statusReason.text = result.reason
        } else {
            statusBanner.text = getString(R.string.status_not_detected)
            statusBanner.setTextColor(
                ContextCompat.getColor(this, R.color.status_not_detected),
            )
            statusReason.visibility = View.GONE
        }

        debugPanel.text = ScreencastProbe.formatDebugText(applicationContext, result)
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1_000L
    }
}

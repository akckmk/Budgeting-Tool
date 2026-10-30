package com.budgetingtool.app.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.budgetingtool.app.MainActivity
import com.budgetingtool.app.R
import com.budgetingtool.app.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps household sharing alive while the app is in the background.
 *
 * Android freezes background apps within seconds, which silently stops the phone-to-phone
 * connection. A foreground service (with its required, low-priority notification) is the
 * supported way to keep a local connection open. It does no work of its own: the sync engine
 * in [SyncManager] runs in the app process this service keeps awake.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build("Sharing is on", "Waiting for the other phone"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return
        }
        val sync = container.sync
        sync.connectKnown()
        scope.launch {
            sync.state
                .map { s ->
                    when {
                        !s.configured -> null
                        s.peers.isNotEmpty() -> "Live with ${s.peers.joinToString { it.name }}" to "Changes sync instantly between your phones"
                        else -> "Sharing is on" to "Will sync when the other phone is on the same Wi-Fi"
                    }
                }
                .distinctUntilChanged()
                .collect { text ->
                    if (text == null) {
                        stopSelf()
                    } else {
                        runCatching { NotificationManagerCompat.from(this@SyncService).notify(NOTIFICATION_ID, build(text.first, text.second)) }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun build(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_budget)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        .build()

    companion object {
        private const val CHANNEL = "sharing"
        private const val NOTIFICATION_ID = 4732

        private fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Sharing with spouse", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows while your phones are kept in sync"
                    setShowBadge(false)
                },
            )
        }

        /** Must be called while the app is in the foreground (Android blocks background starts). */
        fun start(context: Context) {
            val c = context.applicationContext
            if (c.container.prefs.householdCode == null || !c.container.prefs.backgroundSync) return
            runCatching { ContextCompat.startForegroundService(c, Intent(c, SyncService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.applicationContext.stopService(Intent(context.applicationContext, SyncService::class.java)) }
        }
    }
}

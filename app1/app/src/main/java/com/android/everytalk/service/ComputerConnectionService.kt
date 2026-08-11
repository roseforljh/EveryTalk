package com.android.everytalk.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.android.everytalk.R
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

private const val CHANNEL_ID = "computer_connection"
private const val NOTIFICATION_ID = 7301
private const val ACTION_START = "com.android.everytalk.computer.START"
private const val ACTION_STOP = "com.android.everytalk.computer.STOP"

/** 维护需要前台存活的本地 SSH 活动，不保存服务器身份或命令。 */
object ComputerConnectionServiceController {
    private val activeTokens = ConcurrentHashMap<String, Unit>()
    private val stopListeners = CopyOnWriteArraySet<() -> Unit>()

    fun acquire(context: Context): Closeable {
        val appContext = context.applicationContext
        val tokenId = UUID.randomUUID().toString()
        activeTokens[tokenId] = Unit
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, ComputerConnectionService::class.java).setAction(ACTION_START),
        )
        return object : Closeable {
            private val closed = AtomicBoolean(false)

            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                activeTokens.remove(tokenId)
                if (activeTokens.isEmpty()) appContext.stopService(
                    Intent(appContext, ComputerConnectionService::class.java),
                )
            }
        }
    }

    fun addStopListener(listener: () -> Unit): Closeable {
        stopListeners += listener
        return Closeable { stopListeners -= listener }
    }

    internal fun stopAll() {
        activeTokens.clear()
        stopListeners.forEach { listener -> runCatching(listener) }
    }
}

class ComputerConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ComputerConnectionServiceController.stopAll()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.computer_connection_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.computer_connection_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.computer_connection_notification_title))
        .setContentText(getString(R.string.computer_connection_notification_text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .addAction(
            0,
            getString(R.string.computer_connection_stop),
            PendingIntent.getService(
                this,
                7302,
                Intent(this, ComputerConnectionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()
}

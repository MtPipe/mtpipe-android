package org.mtpipe.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.mtpipe.MainActivity
import org.mtpipe.R

class MtpipeService : Service() {

    private var proxy: MtpipeProxy? = null
    private val binder = LocalBinder()

    val isProxyRunning: Boolean
        get() = proxy?.isRunning == true

    val currentStatus: String
        get() = if (isProxyRunning) "Running" else "Disconnected"

    val currentClientCount: Int
        get() = proxy?.clientCount ?: 0

    private fun formatStatus(status: String): String {
        return if (status.startsWith("LISTENING:")) {
            val port = status.removePrefix("LISTENING:")
            getString(R.string.listening_on, port)
        } else {
            status
        }
    }

    var onStatusChanged: ((String) -> Unit)? = null
    var onClientCountChanged: ((Int) -> Unit)? = null
    var onProxyStopped: (() -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): MtpipeService = this@MtpipeService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val server = intent.getStringExtra(EXTRA_SERVER) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val secret = intent.getStringExtra(EXTRA_SECRET) ?: return START_NOT_STICKY
                val listenPort = intent.getIntExtra(EXTRA_LISTEN_PORT, 19796)

                try {
                    startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.starting_proxy)))
                } catch (e: Exception) {
                    Log.e("MtpipeService", "Cannot start foreground: ${e.message}")
                    onStatusChanged?.invoke(getString(R.string.error_cannot_start))
                    stopSelf()
                    return START_NOT_STICKY
                }

                proxy = MtpipeProxy(server, port, secret, listenPort).apply {
                    onStatusChanged = { status ->
                        val formatted = formatStatus(status)
                        this@MtpipeService.onStatusChanged?.invoke(formatted)
                        updateNotification(formatted)
                    }
                    onClientCountChanged = { count ->
                        this@MtpipeService.onClientCountChanged?.invoke(count)
                    }
                    onFatalError = { error ->
                        this@MtpipeService.onStatusChanged?.invoke(error)
                        updateNotification(error)
                        proxy?.stop()
                        proxy = null
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        onProxyStopped?.invoke()
                    }
                    start()
                }
            }
            ACTION_STOP -> {
                proxy?.stop()
                proxy = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                onProxyStopped?.invoke()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        proxy?.stop()
        proxy = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MtpipeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val ACTION_START = "org.mtpipe.proxy.START"
        const val ACTION_STOP = "org.mtpipe.proxy.STOP"
        const val EXTRA_SERVER = "server"
        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_LISTEN_PORT = "listen_port"
        private const val CHANNEL_ID = "mtpipe_proxy"
        private const val NOTIFICATION_ID = 1
    }
}

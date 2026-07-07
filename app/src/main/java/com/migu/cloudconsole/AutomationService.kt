package com.migu.cloudconsole

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AutomationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val channelId = "migu-automation"
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(1001, buildNotification("准备中", "后台保活服务已启动"))
        acquireRuntimeLocks()
        engine().setServiceActive(true)
        scope.launch {
            engine().state.collectLatest {
                val notification = buildNotification(
                    it.status,
                    "阶段: ${it.phase.name.lowercase()} | 模型: ${it.model.displayName}",
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager.notify(1001, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        acquireRuntimeLocks()
        engine().startBackgroundMode()
        return START_STICKY
    }

    override fun onDestroy() {
        engine().setServiceActive(false)
        releaseRuntimeLocks()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, AutomationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restartIntent)
        } else {
            applicationContext.startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun engine(): AutomationEngine {
        return (application as MiguApplication).automationEngine
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            channelId,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, content: String): Notification {
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .build()
    }

    private fun acquireRuntimeLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        cpuWakeLock = cpuWakeLock ?: powerManager
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "migu-cloudconsole:automation")
            ?.apply { setReferenceCounted(false) }
        cpuWakeLock?.let {
            if (!it.isHeld) {
                it.acquire()
            }
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiLock ?: wifiManager
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "migu-cloudconsole:wifi")
            ?.apply { setReferenceCounted(false) }
        wifiLock?.let {
            if (!it.isHeld) {
                it.acquire()
            }
        }
    }

    private fun releaseRuntimeLocks() {
        wifiLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wifiLock = null

        cpuWakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        cpuWakeLock = null
    }
}

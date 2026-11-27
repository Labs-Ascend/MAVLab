package com.ascend.mavlab.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.ascend.mavlab.MainActivity
import com.ascend.mavlab.R
import com.ascend.mavlab.core.common.AppRuntime
import com.ascend.mavlab.simulation.engine.ControlAuthority
import com.ascend.mavlab.simulation.engine.FlightMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the foreground simulation runtime.
 */
class SimulationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var runtimeActive = false
    private var appVisible = true
    private var backgroundedAtMs: Long? = null
    private var retentionJob: Job? = null
    private var lastNotificationDecision: BackgroundRuntimeDecision? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backgroundPolicy = BackgroundRuntimePolicy()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(BackgroundRuntimeDecision.KEEP_APP_VISIBLE))
        acquireLocks()
        AppRuntime.start(applicationContext)
        runtimeActive = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_APP_VISIBLE -> enterVisibleMode()
            ACTION_APP_BACKGROUNDED -> enterBackgroundMode()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        retentionJob?.cancel()
        retentionJob = null
        serviceScope.cancel()
        stopRuntime()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing MAVLab from recents is an explicit close and always wins over
        // GCS/mission retention.
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    private fun enterVisibleMode() {
        appVisible = true
        backgroundedAtMs = null
        retentionJob?.cancel()
        retentionJob = null
        AppRuntime.setAppVisible(true)
        updateNotification(BackgroundRuntimeDecision.KEEP_APP_VISIBLE)
    }

    private fun enterBackgroundMode() {
        if (!runtimeActive) return
        appVisible = false
        backgroundedAtMs = System.currentTimeMillis()
        AppRuntime.setAppVisible(false)
        startRetentionMonitor()
    }

    private fun startRetentionMonitor() {
        retentionJob?.cancel()
        retentionJob = serviceScope.launch {
            while (isActive && !appVisible) {
                val state = AppRuntime.state.value
                val mission = AppRuntime.missionProgress.value
                val decision = backgroundPolicy.decide(
                    snapshot = BackgroundRuntimeSnapshot(
                        appVisible = appVisible,
                        backgroundedAtMs = backgroundedAtMs,
                        gcsConnected = AppRuntime.mavlinkIdentityStatus.value.gcsConnected,
                        armed = state.armed,
                        missionActive = state.controlAuthority == ControlAuthority.GCS_MISSION &&
                            state.mode == FlightMode.AUTO &&
                            mission.loaded &&
                            !mission.complete,
                    ),
                    nowMs = System.currentTimeMillis(),
                )
                if (decision == BackgroundRuntimeDecision.STOP_IDLE) {
                    stopSelf()
                    return@launch
                }
                updateNotification(decision)
                delay(RetentionPollIntervalMs)
            }
        }
    }

    private fun stopRuntime() {
        if (!runtimeActive) return
        runtimeActive = false
        AppRuntime.stop()
        releaseLocks()
    }

    // Held for the whole simulation session: Wi-Fi power save and Doze otherwise
    // stall UDP telemetry with the screen off, tripping the GCS heartbeat timeout.
    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAVLab:Simulation").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(wifiMode, "MAVLab:MavlinkUdp").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.simulation_service_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(decision: BackgroundRuntimeDecision) {
        if (lastNotificationDecision == decision) return
        lastNotificationDecision = decision
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(decision))
    }

    private fun notification(decision: BackgroundRuntimeDecision): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_SERVICE_REQUEST_CODE,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val message = when (decision) {
            BackgroundRuntimeDecision.KEEP_GCS_HANDOFF -> getString(R.string.simulation_service_waiting_for_gcs)
            BackgroundRuntimeDecision.KEEP_GCS_CONNECTED -> getString(R.string.simulation_service_gcs_connected)
            BackgroundRuntimeDecision.KEEP_ACTIVE_FLIGHT -> getString(R.string.simulation_service_active_flight)
            else -> getString(R.string.simulation_service_text)
        }
        return builder
            .setContentTitle(getString(R.string.simulation_service_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_launcher_foreground),
                    getString(R.string.simulation_service_stop),
                    stopPendingIntent,
                ).build(),
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        // Visible UI may start a service normally, avoiding the foreground-start
        // deadline while initial Compose layout occupies the main thread.
        fun startFromVisibleUi(context: Context) {
            context.startService(appVisibleIntent(context))
        }

        fun appVisibleIntent(context: Context): Intent =
            Intent(context, SimulationService::class.java).setAction(ACTION_APP_VISIBLE)

        fun appBackgroundedIntent(context: Context): Intent =
            Intent(context, SimulationService::class.java).setAction(ACTION_APP_BACKGROUNDED)

        fun stopIntent(context: Context): Intent =
            Intent(context, SimulationService::class.java).setAction(ACTION_STOP)

        private const val ACTION_APP_VISIBLE = "com.ascend.mavlab.action.APP_VISIBLE"
        private const val ACTION_APP_BACKGROUNDED = "com.ascend.mavlab.action.APP_BACKGROUNDED"
        private const val ACTION_STOP = "com.ascend.mavlab.action.STOP_SIMULATION"
        const val CHANNEL_ID = "mavlab_simulation"
        const val NOTIFICATION_ID = 1001
        private const val OPEN_APP_REQUEST_CODE = 1001
        private const val STOP_SERVICE_REQUEST_CODE = 1002
        private const val RetentionPollIntervalMs = 1_000L
    }
}

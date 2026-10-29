package com.example.speedcam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * خدمة أمامية (Foreground Service) بتخلّي الكاميرا والقياس يكمّلوا والشاشة مقفولة أو التطبيق في الخلفية.
 * بتشتغل لما يكون "التشغيل المستمر في الخلفية" مفعّل والقياس شغال، وبتقف لما أي واحد منهم يقف.
 * الشغل الفعلي كله في Core والمحرك؛ الخدمة هنا بتحافظ على العملية حيّة وبتعرض إشعار فيه زرار إيقاف.
 */
class RecordingService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        RecordingNotifications.ensureChannels(this)
        val core = Core.get(this)
        try {
            val n = RecordingNotifications.running(this, statusText(core))
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(RecordingNotifications.ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(RecordingNotifications.ID_RUN, n)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return
        }
        running = true
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "speedcam:record").apply {
                setReferenceCounted(false)
                acquire(WAKE_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "wakelock failed", t)
        }
        core.host.setService(true)
        job = core.scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(5000)
                try { wake?.acquire(WAKE_MS) } catch (_: Throwable) { }
                notifyStatus(core)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) Core.get(this).setRunning(false)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        job?.cancel()
        try { Core.get(this).host.setService(false) } catch (_: Throwable) { }
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Throwable) { }
        super.onDestroy()
    }

    private fun statusText(core: Core): String {
        val ov = core.engine.overlay.value
        val h = core.health.value
        val bat = if (h.batteryPct >= 0) " | بطارية ${h.batteryPct}%" else ""
        val temp = if (h.tempC > 0f) " | ${h.tempC.toInt()}°" else ""
        val warn = if (h.message.isNotEmpty()) "\n⚠ ${h.message}" else ""
        return "تم عدّ ${ov.sessionCount} | FPS ${ov.fps}$temp$bat$warn"
    }

    @SuppressLint("MissingPermission")
    private fun notifyStatus(core: Core) {
        try {
            NotificationManagerCompat.from(this).notify(RecordingNotifications.ID_RUN, RecordingNotifications.running(this, statusText(core)))
        } catch (_: Throwable) { }
    }

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_STOP = "com.example.speedcam.STOP"
        private const val WAKE_MS = 10 * 60 * 1000L

        @Volatile var running = false
            private set
    }
}

/** الإشعارات: إشعار الخدمة الدائم + تنبيهات الحرارة والبطارية. */
object RecordingNotifications {
    private const val CH_RUN = "speedcam_run"
    private const val CH_ALERT = "speedcam_alert"
    const val ID_RUN = 41
    private const val ID_ALERT = 42

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_RUN, "القياس في الخلفية", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "تنبيهات الحرارة والبطارية", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags())

    fun running(ctx: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            ctx, 1, Intent(ctx, RecordingService::class.java).setAction(RecordingService.ACTION_STOP), flags()
        )
        return NotificationCompat.Builder(ctx, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat_speedcam)
            .setContentTitle("رادار السرعة شغّال في الخلفية")
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .addAction(R.drawable.ic_stat_speedcam, "إيقاف القياس", stop)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun alert(ctx: Context, title: String, text: String, id: Int = ID_ALERT) {
        try {
            ensureChannels(ctx)
            val n = NotificationCompat.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_speedcam)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx))
                .build()
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: Throwable) { }
    }
}

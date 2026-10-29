package com.example.speedcam

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** حالة الجهاز: حرارة البطارية ونسبتها. level: 0 = تمام، 1 = تحذير، 2 = حرج (القياس بيتوقف). */
data class Health(
    val tempC: Float = 0f,
    val batteryPct: Int = -1,
    val charging: Boolean = false,
    val level: Int = 0,
    val message: String = ""
)

/**
 * كل حاجة بتفضل عايشة طول ما العملية شغالة، حتى لو الشاشة اتقفلت أو الـ Activity اتقفلت:
 * الإعدادات، المحرك، الكاميرا، قاعدة البيانات، النسخ الاحتياطي، تنظيف السجلات القديمة، ومراقبة الحرارة والبطارية.
 * (MainViewModel والخدمة الخلفية الاتنين بيستخدموا نفس النسخة.)
 */
class Core private constructor(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val store = SettingsStore(app)
    val settings = MutableStateFlow(store.load())
    val dao = AppDatabase.get(app).dao()
    val plateReader = PlateReader(app)
    val engine = SpeedEngine(app, scope, dao, plateReader) { settings.value }
    val host = CameraHost(app, engine) { settings.value }
    val health = MutableStateFlow(Health())

    @Volatile private var lastAlertMs = 0L

    init {
        DirectionMode.homo = Geo.active(settings.value)
        if (settings.value.readPlates) plateReader.ensureData()

        // إعدادات الكاميرا (الدقة، FPS، التعريض) + وضع الاتجاه + تشغيل/إيقاف الخدمة الخلفية
        scope.launch(Dispatchers.Main) {
            settings.collect { s ->
                DirectionMode.homo = Geo.active(s)
                host.onSettings(s)
            }
        }
        scope.launch(Dispatchers.Main) {
            combine(settings.map { it.bgService }.distinctUntilChanged(), engine.runningFlow) { bg, run -> bg && run }
                .distinctUntilChanged()
                .collect { want -> syncService(want) }
        }

        // نسخة احتياطية تلقائية: كل 10 دقايق (لو فيه قياسات جديدة) في ملف يومي داخل مجلد التنزيلات
        scope.launch(Dispatchers.IO) {
            var lastCount = -1
            while (true) {
                delay(10 * 60 * 1000L)
                try {
                    val list = dao.getAll()
                    if (list.isNotEmpty() && list.size != lastCount) {
                        if (CsvExporter.backupToDownloads(app, list, settings.value.axisHorizontal)) lastCount = list.size
                    }
                } catch (_: Throwable) { }
            }
        }

        // حد أقصى لعدد السجلات: كل نص دقيقة نشوف لو عدّينا الحد
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(30_000L)
                try { trimOnce() } catch (t: Throwable) { Log.e(TAG, "trim failed", t) }
            }
        }

        // مراقبة الحرارة والبطارية كل 15 ثانية
        scope.launch(Dispatchers.Default) {
            while (true) {
                try { checkHealth() } catch (t: Throwable) { Log.e(TAG, "health failed", t) }
                delay(15_000L)
            }
        }
    }

    fun update(f: (AppSettings) -> AppSettings) {
        settings.update(f)
        store.save(settings.value)
    }

    /** بدء/إيقاف القياس. عند البدء بنصفّر التتبع. */
    fun setRunning(on: Boolean) {
        engine.running = on
        if (on) engine.reset()
    }

    /** بعد ما إذن الكاميرا يتمنح: نشغّل الكاميرا والخدمة (لو مطلوبة). */
    fun onPermission() {
        host.refresh()
        syncService(settings.value.bgService && engine.running)
    }

    private fun syncService(want: Boolean) {
        val i = Intent(app, RecordingService::class.java)
        if (want) {
            if (RecordingService.running) return
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            try {
                ContextCompat.startForegroundService(app, i)
            } catch (t: Throwable) {
                Log.e(TAG, "start service failed", t)
            }
        } else if (RecordingService.running) {
            try { app.stopService(i) } catch (_: Throwable) { }
        }
    }

    // =====================  حد أقصى للسجلات  =====================

    /** لو عدد السجلات عدّى الحد: نؤرشف الأقدم في ملف CSV في التنزيلات، وبعدين نمسحه (ونمسح لقطاته). بنمسح لحد 90% من الحد عشان ما يتكررش كل شوية. */
    private suspend fun trimOnce() {
        val max = settings.value.maxRecords
        if (max <= 0) return
        val n = dao.count()
        if (n <= max) return
        val extra = n - (max * 0.9).toInt()
        if (extra <= 0) return
        val old = dao.oldest(extra)
        if (old.isEmpty()) return
        val archived = CsvExporter.archiveToDownloads(app, old, settings.value.axisHorizontal)
        // لو الأرشفة فشلت نستنى، إلا لو السجلات عدّت الحد بنص كمان (حماية من امتلاء التخزين)
        if (!archived && n < max + max / 2) {
            Log.w(TAG, "archive failed, trim postponed")
            return
        }
        old.forEach { Evidence.delete(it.evidencePath) }
        dao.deleteOldest(extra)
    }

    // =====================  الحرارة والبطارية  =====================

    private fun readBattery(): Health? {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Health(temp, pct, charging)
    }

    private suspend fun checkHealth() {
        val h = readBattery() ?: return
        val guard = settings.value.thermalGuard
        val lowBat = h.batteryPct in 0..BAT_WARN_PCT && !h.charging
        val critBat = h.batteryPct in 0..BAT_STOP_PCT && !h.charging
        val hot = h.tempC >= HEAT_WARN_C
        val critHot = h.tempC >= HEAT_STOP_C
        val level = when {
            !guard -> 0
            critHot || critBat -> 2
            hot || lowBat -> 1
            else -> 0
        }
        val msg = when {
            level == 0 -> ""
            critHot -> "حرارة الجهاز عالية جداً (${h.tempC.toInt()}°)"
            critBat -> "البطارية على وشك الانتهاء (${h.batteryPct}%)"
            hot -> "حرارة الجهاز مرتفعة (${h.tempC.toInt()}°)"
            else -> "البطارية ضعيفة (${h.batteryPct}%)"
        }
        health.value = h.copy(level = level, message = msg)

        if (level == 0) return
        if (level == 2 && engine.running) {
            // نحفظ نسخة احتياطية قبل الإيقاف، وبعدها نوقف القياس
            try {
                val list = dao.getAll()
                if (list.isNotEmpty()) CsvExporter.backupToDownloads(app, list, settings.value.axisHorizontal)
            } catch (_: Throwable) { }
            engine.running = false
            RecordingNotifications.alert(app, "تم إيقاف القياس", "$msg. اتحفظت نسخة احتياطية. ارجع شغّل القياس لما الجهاز يهدى أو يتشحن.")
            lastAlertMs = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - lastAlertMs > ALERT_EVERY_MS) {
            lastAlertMs = System.currentTimeMillis()
            RecordingNotifications.alert(app, "تنبيه", if (level == 2) "$msg. القياس متوقف." else "$msg. فكّر تبرّد الجهاز أو توصّله بشاحن.")
        }
    }

    companion object {
        private const val TAG = "Core"
        private const val HEAT_WARN_C = 42f
        private const val HEAT_STOP_C = 46f
        private const val BAT_WARN_PCT = 20
        private const val BAT_STOP_PCT = 7
        private const val ALERT_EVERY_MS = 10 * 60 * 1000L

        @Volatile private var inst: Core? = null

        fun get(ctx: Context): Core = inst ?: synchronized(this) {
            inst ?: Core(ctx.applicationContext as Application).also { inst = it }
        }
    }
}

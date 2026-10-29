package com.example.speedcam

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

class GpsPoint(val t: Long, val lat: Double, val lon: Double, val kmh: Double, val acc: Float)

/** نتيجة مطابقة مرور مركبة مرجع (GPS) مع قياس التطبيق. rec = القياس المطابق، rawKmh = السرعة الخام قبل المعايرة. */
class GpsMatch(
    val passT: Long,
    val gpsKmh: Double,
    val spread: Double,
    val minDistM: Double,
    val rec: SpeedRecord?,
    val rawKmh: Double?,
    val ok: Boolean,
    val note: String
)

object GpsCalib {
    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 110_540.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
        return hypot(dx, dy)
    }

    fun toCsv(pts: List<GpsPoint>): String {
        val sb = StringBuilder("time_ms,lat,lon,speed_kmh,acc_m\n")
        for (p in pts) sb.append(p.t).append(',').append(p.lat).append(',').append(p.lon).append(',')
            .append(String.format(Locale.US, "%.2f", p.kmh)).append(',').append(String.format(Locale.US, "%.1f", p.acc)).append('\n')
        return sb.toString()
    }

    fun parseCsv(text: String): List<GpsPoint> = text.lineSequence().drop(1).mapNotNull { line ->
        val q = line.trim().split(',')
        if (q.size < 5) return@mapNotNull null
        val t = q[0].toLongOrNull() ?: return@mapNotNull null
        val la = q[1].toDoubleOrNull() ?: return@mapNotNull null
        val lo = q[2].toDoubleOrNull() ?: return@mapNotNull null
        val k = q[3].toDoubleOrNull() ?: return@mapNotNull null
        GpsPoint(t, la, lo, k, q[4].toFloatOrNull() ?: 0f)
    }.toList()

    /**
     * 1) نلاقي مرات مرور مركبة المرجع جنب موقع الكاميرا (أقرب نقطة GPS داخل نطاق radiusM).
     * 2) سرعة GPS وقت المرور = متوسط ±2 ثانية، وبنقبلها لو سرعتها ثابتة (فرق أقل من 4 كم/س خلال ±3 ثواني) وأعلى من 15 كم/س.
     * 3) نطابقها مع أقرب قياس للتطبيق في ±windowSec ثانية. لو فيه قياسين أو أكتر في النافذة بنعتبرها ملتبسة ومش بتتضاف تلقائياً.
     */
    fun match(
        gps: List<GpsPoint>, siteLat: Double, siteLon: Double, records: List<SpeedRecord>, s: AppSettings,
        radiusM: Double = 80.0, windowSec: Double = 5.0
    ): List<GpsMatch> {
        val sorted = gps.sortedBy { it.t }
        val passes = ArrayList<List<GpsPoint>>()
        var cur = ArrayList<GpsPoint>()
        for (p in sorted) {
            val d = distM(p.lat, p.lon, siteLat, siteLon)
            if (d <= radiusM && (cur.isEmpty() || p.t - cur.last().t <= 5000)) cur.add(p)
            else {
                if (cur.isNotEmpty()) { passes.add(cur); cur = ArrayList() }
                if (d <= radiusM) cur.add(p)
            }
        }
        if (cur.isNotEmpty()) passes.add(cur)

        val calib = if (s.speedCalib > 0.01f) s.speedCalib.toDouble() else 1.0
        val used = HashSet<Long>()
        val out = ArrayList<GpsMatch>()
        for (pass in passes) {
            val closest = pass.minByOrNull { distM(it.lat, it.lon, siteLat, siteLon) } ?: continue
            val minD = distM(closest.lat, closest.lon, siteLat, siteLon)
            val passT = closest.t
            val near = sorted.filter { abs(it.t - passT) <= 3000 }
            val inner = near.filter { abs(it.t - passT) <= 2000 }
            if (inner.size < 3) {
                out += GpsMatch(passT, 0.0, 0.0, minD, null, null, false, "عينات GPS قليلة حوالين المرور")
                continue
            }
            val gpsKmh = inner.map { it.kmh }.average()
            val spread = (near.maxOf { it.kmh } - near.minOf { it.kmh })
            val cands = records.filter { it.speedKmh >= 0 && abs(it.timestamp - passT) <= windowSec * 1000 && it.id !in used }
                .sortedBy { abs(it.timestamp - passT) }
            val rec = cands.firstOrNull()
            val problems = ArrayList<String>()
            if (spread > 4.0) problems += "السرعة مش ثابتة (فرق ${spread.toInt()} كم/س)"
            if (gpsKmh < 15.0) problems += "السرعة أقل من 15 كم/س"
            if (rec == null) problems += "مفيش قياس للتطبيق في الوقت ده"
            if (cands.size > 1) problems += "فيه مركبة تانية قريبة في الوقت"
            if (rec != null) used += rec.id
            val raw = rec?.let { (it.speedKmh - s.speedOffset) / calib }
            out += GpsMatch(passT, gpsKmh, spread, minD, rec, raw, problems.isEmpty(), if (problems.isEmpty()) "تمام" else problems.joinToString("، "))
        }
        return out
    }
}

/** تسجيل سرعة GPS على الموبايل اللي جوه العربية (المرجع)، وتحديد موقع الكاميرا. */
object GpsRef {
    val recording = MutableStateFlow(false)
    val count = MutableStateFlow(0)
    val lastKmh = MutableStateFlow(0.0)
    val lastAcc = MutableStateFlow(0f)
    private val pts = ArrayList<GpsPoint>()
    private var lm: LocationManager? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (!loc.hasSpeed()) return
            val kmh = loc.speed * 3.6
            synchronized(pts) { pts.add(GpsPoint(loc.time, loc.latitude, loc.longitude, kmh, loc.accuracy)); count.value = pts.size }
            lastKmh.value = kmh
            lastAcc.value = loc.accuracy
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context): Boolean {
        if (recording.value) return true
        return try {
            val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
            synchronized(pts) { pts.clear() }
            count.value = 0
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm = m
            recording.value = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** يوقف التسجيل ويحفظ ملف speedcam_gps_… في التنزيلات. بيرجّع اسم الملف أو null لو مفيش بيانات كفاية. */
    fun stop(ctx: Context): String? {
        try { lm?.removeUpdates(listener) } catch (_: Throwable) { }
        lm = null
        recording.value = false
        val copy = synchronized(pts) { ArrayList(pts) }
        if (copy.size < 3) return null
        val name = "speedcam_gps_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val f = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, name)
        f.writeText(GpsCalib.toCsv(copy))
        val ok = CsvExporter.saveToDownloads(ctx, f)
        f.delete()
        return if (ok) name else null
    }

    /** يحدد موقع الموبايل: ينتظر دقة 15 متر أو أحسن (أو 40 ثانية)، وبيرجّع الموقع أو null. */
    @SuppressLint("MissingPermission")
    fun fix(ctx: Context, onDone: (Location?) -> Unit) {
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val h = Handler(Looper.getMainLooper())
        var done = false
        var best: Location? = null
        val holder = arrayOfNulls<LocationListener>(1)
        fun finish(loc: Location?) {
            if (done) return
            done = true
            try { holder[0]?.let { m.removeUpdates(it) } } catch (_: Throwable) { }
            onDone(loc)
        }
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                if (best == null || loc.accuracy < best!!.accuracy) best = loc
                if (loc.accuracy <= 15f) finish(loc)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        holder[0] = l
        try {
            if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER)) { onDone(null); return }
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
            h.postDelayed({ finish(best?.takeIf { it.accuracy <= 40f }) }, 40_000L)
        } catch (_: Throwable) {
            onDone(null)
        }
    }
}

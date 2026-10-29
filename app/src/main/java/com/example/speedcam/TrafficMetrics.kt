package com.example.speedcam

import kotlin.math.ceil
import kotlin.math.max

/**
 * معاملات تحويل المركبات لمركبة ركوب مكافئة (PCE). قيم استرشادية شائعة، مش من كود طرق معيّن:
 * راجعها مع الكود المعتمد عندكم قبل استخدام النتائج في تقرير رسمي.
 */
object Pce {
    fun of(t: VehicleType): Double = when (t) {
        VehicleType.CAR -> 1.0
        VehicleType.MICROBUS -> 1.5
        VehicleType.TUKTUK -> 0.8
        VehicleType.MOTORCYCLE -> 0.4
        VehicleType.BUS -> 2.0
        VehicleType.TRUCK -> 2.0
    }
}

/** مؤشرات اتجاه واحد من الطريق. القيم الاختيارية null لما البيانات مش كفاية لحسابها. */
class DirMetrics(
    val dir: Int,
    val count: Int,
    val pcuTotal: Double,
    val spanMin: Double,
    val flowVehH: Double,          // متوسط التدفق (مركبة/ساعة) على فترة القياس
    val flowPcuH: Double,          // نفسه بوحدة PCE
    val peak15RatePcuH: Double?,   // أعلى 15 دقيقة × 4 (PCE/ساعة)
    val phf: Double?,              // معامل ساعة الذروة
    val vc: Double?,               // نسبة التدفق للسعة
    val los: String?,              // مستوى الخدمة A..F
    val meanHeadway: Double?,      // متوسط الفجوة الزمنية بين المركبات (ثانية)
    val medianHeadway: Double?,
    val shortHeadwayPct: Double?   // % الفجوات الأقل من ثانيتين
)

class LaneStat(val lane: Int, val count: Int, val meanKmh: Double?, val v85: Double?)

object TrafficMetrics {
    private const val MIN_15 = 15 * 60_000L
    private const val HOUR = 3_600_000L

    /** مستوى الخدمة التقريبي بنسبة التدفق/السعة (حدود شائعة لقطاعات الطرق السريعة: 0.35 / 0.54 / 0.77 / 0.93 / 1.0). */
    fun losOf(vc: Double): String = when {
        vc <= 0.35 -> "A"
        vc <= 0.54 -> "B"
        vc <= 0.77 -> "C"
        vc <= 0.93 -> "D"
        vc <= 1.0 -> "E"
        else -> "F"
    }

    fun percentile(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val idx = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        return sorted[idx]
    }

    fun compute(list: List<SpeedRecord>, lanesPerDir: Int, capPerLane: Int): List<DirMetrics> {
        val out = ArrayList<DirMetrics>()
        for (dir in 1..2) {
            val recs = list.filter { it.direction == dir }.sortedBy { it.timestamp }
            if (recs.isEmpty()) continue
            val n = recs.size
            val spanMs = recs.last().timestamp - recs.first().timestamp
            val spanH = max(spanMs, 60_000L) / HOUR.toDouble()
            val pcu = recs.sumOf { r -> Pce.of(VehicleType.fromName(r.vehicleType) ?: VehicleType.CAR) }

            // أعلى 15 دقيقة (بنحتاج على الأقل 15 دقيقة قياس عشان الرقم يبقى له معنى)
            var peak15: Double? = null
            if (spanMs >= MIN_15) {
                val bins = HashMap<Long, Double>()
                for (r in recs) bins[r.timestamp / MIN_15] = (bins[r.timestamp / MIN_15] ?: 0.0) + Pce.of(VehicleType.fromName(r.vehicleType) ?: VehicleType.CAR)
                peak15 = bins.values.maxOrNull()
            }
            val peakRate = peak15?.times(4.0)

            // معامل ساعة الذروة (بنحتاج ساعة قياس على الأقل)
            var phf: Double? = null
            if (spanMs >= HOUR) {
                val hours = HashMap<Long, Double>()
                val q = HashMap<Long, Double>()
                for (r in recs) {
                    val w = Pce.of(VehicleType.fromName(r.vehicleType) ?: VehicleType.CAR)
                    hours[r.timestamp / HOUR] = (hours[r.timestamp / HOUR] ?: 0.0) + w
                    q[r.timestamp / MIN_15] = (q[r.timestamp / MIN_15] ?: 0.0) + w
                }
                val peakHour = hours.maxByOrNull { it.value }
                if (peakHour != null) {
                    val p15 = (0 until 4).maxOf { q[peakHour.key * 4 + it] ?: 0.0 }
                    if (p15 > 0.0) phf = peakHour.value / (4.0 * p15)
                }
            }

            val cap = max(1, lanesPerDir) * max(1, capPerLane).toDouble()
            val vc = peakRate?.let { it / cap }

            // الفجوات الزمنية (بنتجاهل الفجوات الأطول من 5 دقايق لأنها غالباً توقف قياس مش مرور)
            val gaps = ArrayList<Double>()
            for (i in 1 until n) {
                val g = (recs[i].timestamp - recs[i - 1].timestamp) / 1000.0
                if (g in 0.0..300.0) gaps += g
            }
            val mean = if (gaps.isNotEmpty()) gaps.average() else null
            val med = if (gaps.isNotEmpty()) RobustStats.median(gaps) else null
            val shortPct = if (gaps.isNotEmpty()) gaps.count { it < 2.0 } * 100.0 / gaps.size else null

            out += DirMetrics(dir, n, pcu, spanMs / 60_000.0, n / spanH, pcu / spanH, peakRate, phf, vc, vc?.let { losOf(it) }, mean, med, shortPct)
        }
        return out
    }

    /** إحصاء كل حارة (من السجلات اللي اتحددت لها حارة، يعني مع تحويل المنظور). */
    fun laneStats(list: List<SpeedRecord>, dropOutliers: Boolean): List<LaneStat> =
        list.filter { it.lane > 0 }.groupBy { it.lane }.toSortedMap().map { (lane, rs) ->
            val sp = RobustStats.filterSorted(rs.filter { it.speedKmh >= 0 }.map { it.speedKmh }.sorted(), dropOutliers)
            LaneStat(lane, rs.size, if (sp.isNotEmpty()) sp.average() else null, percentile(sp, 0.85))
        }
}

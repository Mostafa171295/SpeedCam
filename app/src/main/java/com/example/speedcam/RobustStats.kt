package com.example.speedcam

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** أدوات إحصاء مقاومة للقيم الشاذة + فلتر كالمان + مسافة Levenshtein. */
object RobustStats {

    fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** MAD مضروب في 1.4826 (يساوي الانحراف المعياري لو البيانات طبيعية). */
    fun madSigma(v: List<Double>, med: Double): Double = 1.4826 * median(v.map { abs(it - med) })

    /**
     * يستبعد أي قيمة بعيدة عن الوسيط بأكتر من k × (1.4826 × MAD). القائمة لازم تكون مرتبة (وبترجع مرتبة).
     * لو العينات أقل من 8 أو MAD = 0 مابيستبعدش حاجة.
     */
    fun filterSorted(sorted: List<Double>, enabled: Boolean, k: Double = 3.0): List<Double> {
        if (!enabled || sorted.size < 8) return sorted
        val med = median(sorted)
        val sig = madSigma(sorted, med)
        if (sig <= 1e-9) return sorted
        return sorted.filter { abs(it - med) <= k * sig }
    }

    class Line(val slope: Double, val intercept: Double, val se: Double)

    /**
     * انحدار Theil-Sen: الميل = وسيط الميول بين كل زوج نقط، فنقطة شاذة أو اتنين مايأثروش.
     * se = الخطأ المعياري التقريبي للميل (من وسيط البواقي).
     */
    fun theilSen(t: DoubleArray, g: DoubleArray): Line? {
        val n = t.size
        if (n < 3) return null
        val slopes = ArrayList<Double>(n * (n - 1) / 2)
        for (i in 0 until n) for (j in i + 1 until n) {
            val dt = t[j] - t[i]
            if (abs(dt) > 1e-4) slopes += (g[j] - g[i]) / dt
        }
        if (slopes.isEmpty()) return null
        val slope = median(slopes)
        val intercept = median(List(n) { g[it] - slope * t[it] })
        val res = List(n) { g[it] - (intercept + slope * t[it]) }
        val sigma = 1.4826 * median(res.map { abs(it) })
        var tm = 0.0
        for (x in t) tm += x
        tm /= n
        var sxx = 0.0
        for (x in t) sxx += (x - tm) * (x - tm)
        if (sxx < 1e-9) return null
        return Line(slope, intercept, sigma / sqrt(sxx))
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** قيمة t لفترة ثقة 95% (طرفين) حسب درجات الحرية. */
    fun t95(dof: Int): Double = when {
        dof <= 0 -> 12.71
        dof == 1 -> 12.71
        dof == 2 -> 4.30
        dof == 3 -> 3.18
        dof == 4 -> 2.78
        dof == 5 -> 2.57
        dof == 6 -> 2.45
        dof == 7 -> 2.36
        dof == 8 -> 2.31
        dof == 9 -> 2.26
        dof == 10 -> 2.23
        dof <= 12 -> 2.18
        dof <= 15 -> 2.13
        dof <= 20 -> 2.09
        else -> 1.96
    }

    /** أقل قيمة مسموحة لانحراف المقياس، بتمنع القسمة على صفر. */
    fun floor(v: Double, lo: Double): Double = max(v, lo)
}

/**
 * فلتر كالمان أحادي البعد بنموذج سرعة ثابتة، على الإحداثي الطولي للمركبة على الأرض (متر).
 * بنستخدمه لكشف الفريمات اللي المربع "اتنطط" فيها (innovation كبيرة) وتجاهلها قبل الانحدار.
 */
class Kalman1D(z0: Double, private var t: Double, r0: Double) {
    private var g = z0
    private var v = 0.0
    private var p00 = r0 * r0
    private var p01 = 0.0
    private var p11 = 225.0   // عدم يقين السرعة المبدئي (15 م/ث)

    fun predictTo(now: Double) {
        val dt = now - t
        if (dt <= 0.0) return
        g += v * dt
        val q = SIGMA_A * SIGMA_A
        val n00 = p00 + dt * (2 * p01 + dt * p11) + q * dt * dt * dt * dt / 4.0
        val n01 = p01 + dt * p11 + q * dt * dt * dt / 2.0
        val n11 = p11 + q * dt * dt
        p00 = n00; p01 = n01; p11 = n11
        t = now
    }

    /** الانحراف المُطبّع للقياس عن التوقع (بعد predictTo). */
    fun nis(z: Double, r: Double): Double = (z - g) / sqrt(p00 + r * r)

    fun update(z: Double, r: Double) {
        val s = p00 + r * r
        val k0 = p00 / s
        val k1 = p01 / s
        val y = z - g
        g += k0 * y
        v += k1 * y
        val n00 = (1 - k0) * p00
        val n01 = (1 - k0) * p01
        val n11 = p11 - k1 * p01
        p00 = n00; p01 = n01; p11 = n11
    }

    private companion object {
        const val SIGMA_A = 3.0   // تسارع/تباطؤ متوقع للمركبة (م/ث²)
    }
}

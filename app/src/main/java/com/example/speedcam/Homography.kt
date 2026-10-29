package com.example.speedcam

import kotlin.math.abs

/**
 * تحويل المنظور (Homography).
 *
 * بيحوّل أي نقطة على الصورة (إحداثيات مُطبّعة 0..1) لنقطة على مستوى الأرض.
 * بنحدد على الصورة 4 نقط هي أركان مستطيل على الطريق:
 *   النقطة 1 و2 = الخط A (من جنب الطريق للجنب التاني)
 *   النقطة 4 و3 = الخط B (بنفس الترتيب: 4 قصاد 1، و3 قصاد 2)
 * وبنعرف بس المسافة الحقيقية بين الخطين على طول الطريق (distanceM).
 * بعدها الإحداثي الطولي للمركبة على الأرض (groundY) بيطلع بالمتر من الخط A، من غير ما يتأثر بالحارة اللي هي فيها.
 */
class Homography private constructor(private val h: DoubleArray) {

    /** الإحداثي الطولي على الأرض بالمتر (0 عند الخط A، distanceM عند الخط B). NaN لو النقطة على خط الأفق. */
    fun groundY(x: Double, y: Double): Double {
        val w = h[6] * x + h[7] * y + 1.0
        if (abs(w) < 1e-9) return Double.NaN
        return (h[3] * x + h[4] * y + h[5]) / w
    }

    /** الإحداثي العرضي على الأرض: 0 عند النقطة 1 (جنب الطريق الأول) و1 عند النقطة 2 (الجنب التاني). */
    fun groundX(x: Double, y: Double): Double {
        val w = h[6] * x + h[7] * y + 1.0
        if (abs(w) < 1e-9) return Double.NaN
        return (h[0] * x + h[1] * y + h[2]) / w
    }

    companion object {
        /**
         * p = 8 أرقام مُطبّعة: x1,y1,x2,y2,x3,y3,x4,y4. الأرض: 1→(0,0)  2→(1,0)  3→(1,L)  4→(0,L).
         * عرض الطريق مش مهم للسرعة (بيأثر على الإحداثي العرضي بس)، فبنحطه 1.
         */
        fun fromQuad(p: FloatArray, lengthM: Float): Homography? {
            if (p.size != 8 || lengthM <= 0f) return null
            if (!isConvex(p)) return null
            val u = doubleArrayOf(0.0, 1.0, 1.0, 0.0)
            val v = doubleArrayOf(0.0, 0.0, lengthM.toDouble(), lengthM.toDouble())
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val x = p[2 * i].toDouble()
                val y = p[2 * i + 1].toDouble()
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u[i] * x, -u[i] * y, u[i])
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v[i] * x, -v[i] * y, v[i])
            }
            val sol = solve(a) ?: return null
            return Homography(sol)
        }

        /** الشكل لازم يكون رباعي محدّب وغير منبعج (كل الزوايا في نفس الاتجاه). */
        fun isConvex(p: FloatArray): Boolean {
            if (p.size != 8) return false
            var pos = 0
            var neg = 0
            for (i in 0 until 4) {
                val x0 = p[2 * i]
                val y0 = p[2 * i + 1]
                val x1 = p[2 * ((i + 1) % 4)]
                val y1 = p[2 * ((i + 1) % 4) + 1]
                val x2 = p[2 * ((i + 2) % 4)]
                val y2 = p[2 * ((i + 2) % 4) + 1]
                val cross = (x1 - x0) * (y2 - y1) - (y1 - y0) * (x2 - x1)
                if (abs(cross) < 1e-5f) return false
                if (cross > 0f) pos++ else neg++
            }
            return pos == 4 || neg == 4
        }

        /** حل 8 معادلات خطية بطريقة جاوس-جوردان. يرجّع null لو النظام مش قابل للحل. */
        private fun solve(a: Array<DoubleArray>): DoubleArray? {
            val n = 8
            for (c in 0 until n) {
                var piv = c
                for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[piv][c])) piv = r
                if (abs(a[piv][c]) < 1e-12) return null
                val t = a[c]
                a[c] = a[piv]
                a[piv] = t
                for (r in 0 until n) {
                    if (r == c) continue
                    val f = a[r][c] / a[c][c]
                    if (f == 0.0) continue
                    for (k in c..n) a[r][k] -= f * a[c][k]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }
    }
}

/** وضع عرض الاتجاه (بيتحدّث من Core عشان directionLabel يعرف لو تحويل المنظور شغال). */
object DirectionMode {
    @Volatile var homo: Boolean = false
}

/**
 * نقطة واحدة بتحدد هندسة الخطين (A/B) وقياس موقع المركبة، سواء بالطريقة القديمة (خطين مستقيمين على الصورة)
 * أو بتحويل المنظور (خطين على الأرض).
 */
object Geo {
    private class Cache(val key: String, val h: Homography?, val pts: FloatArray?)

    @Volatile private var cache = Cache("", null, null)

    /** "x1,y1;x2,y2;x3,y3;x4,y4" → 8 أرقام، أو null. */
    fun parsePts(s: String): FloatArray? {
        val v = s.split(';', ',').mapNotNull { it.trim().toFloatOrNull() }
        return if (v.size == 8) v.toFloatArray() else null
    }

    fun encodePts(p: FloatArray): String =
        (0 until 4).joinToString(";") { "${p[2 * it]},${p[2 * it + 1]}" }

    /** النقط الافتراضية: خطين رأسيين زي الوضع القديم، تقدر تسحبها على الطريق. */
    fun defaultPts(): FloatArray = floatArrayOf(0.35f, 0.30f, 0.35f, 0.85f, 0.65f, 0.85f, 0.65f, 0.30f)

    private fun cacheFor(s: AppSettings): Cache {
        val key = if (s.homoOn) s.homoPts + "|" + s.distanceM + "|" + s.lensK1 else ""
        val c = cache
        if (c.key == key) return c
        val pts = if (s.homoOn) parsePts(s.homoPts) else null
        val h = pts?.let { Homography.fromQuad(undistortPts(it, s.lensK1), s.distanceM) }
        val n = Cache(key, h, if (h != null) pts else null)
        cache = n
        return n
    }

    /**
     * تصحيح تشوه العدسة الشعاعي (نموذج k1): الإحداثيات بتتحول لمحاور مركزها منتصف الصورة (-1..1)،
     * وبنحل xd = xu × (1 + k1 × r²) بالتكرار. k1 = 0 يعني مفيش تصحيح.
     */
    fun undistort(x: Double, y: Double, k1: Float, out: DoubleArray) {
        if (k1 == 0f) { out[0] = x; out[1] = y; return }
        val xd = (x - 0.5) * 2.0
        val yd = (y - 0.5) * 2.0
        var xu = xd
        var yu = yd
        repeat(5) {
            val f = 1.0 + k1 * (xu * xu + yu * yu)
            if (f > 0.2) { xu = xd / f; yu = yd / f }
        }
        out[0] = xu / 2.0 + 0.5
        out[1] = yu / 2.0 + 0.5
    }

    private fun undistortPts(p: FloatArray, k1: Float): FloatArray {
        if (k1 == 0f) return p
        val o = DoubleArray(2)
        val r = FloatArray(8)
        for (i in 0 until 4) {
            undistort(p[2 * i].toDouble(), p[2 * i + 1].toDouble(), k1, o)
            r[2 * i] = o[0].toFloat()
            r[2 * i + 1] = o[1].toFloat()
        }
        return r
    }

    fun homography(s: AppSettings): Homography? = cacheFor(s).h

    /** تحويل المنظور شغال وصالح؟ */
    fun active(s: AppSettings): Boolean = homography(s) != null

    /** مفعّل من الإعدادات لكن النقط غير صالحة (فبنرجع للخطين العاديين)؟ */
    fun invalid(s: AppSettings): Boolean = s.homoOn && homography(s) == null

    /** موضع الخط A / B على محور القياس (متر على الأرض لو تحويل المنظور شغال، وإلا نسبة من الصورة). */
    fun lineA(s: AppSettings): Float = if (active(s)) 0f else s.lineA

    fun lineB(s: AppSettings): Float = if (active(s)) s.distanceM else s.lineB

    /** نقطة قياس المركبة على المحور: الإحداثي الطولي على الأرض (تحويل المنظور) أو الحافة الأمامية/السفلية (الطريقة القديمة). */
    fun point(b: NBox, dir: Int, s: AppSettings): Float {
        val h = homography(s)
        if (h != null) {
            if (s.lensK1 == 0f) return h.groundY(b.cx.toDouble(), b.b.toDouble()).toFloat()
            val o = DoubleArray(2)
            undistort(b.cx.toDouble(), b.b.toDouble(), s.lensK1, o)
            return h.groundY(o[0], o[1]).toFloat()
        }
        return if (s.axisHorizontal) when (dir) { 1 -> b.r; -1 -> b.l; else -> b.cx } else b.b
    }

    /**
     * رقم الحارة (1..laneCount) من موضع المركبة العرضي بين جنبي الطريق (حارات متساوية العرض).
     * 0 = غير معروف (تحويل المنظور مش شغال) أو المركبة خارج الطريق المعلّم.
     */
    fun lane(b: NBox, s: AppSettings): Int {
        val h = homography(s) ?: return 0
        val o = DoubleArray(2)
        undistort(b.cx.toDouble(), b.b.toDouble(), s.lensK1, o)
        val gx = h.groundX(o[0], o[1])
        if (!gx.isFinite() || gx < -0.1 || gx > 1.1) return 0
        val n = s.laneCount.coerceIn(1, 8)
        return (Math.floor(gx.coerceIn(0.0, 0.9999) * n).toInt() + 1).coerceIn(1, n)
    }

    /** أقل إزاحة نعتبرها اتجاه حركة حقيقي (متر على الأرض، أو نسبة من الصورة). */
    fun minDir(s: AppSettings): Float = if (active(s)) 0.05f else 0.004f

    /** مقطع الخط idx (0 = A، 1 = B) على الصورة [x1,y1,x2,y2] مُطبّع. null لو تحويل المنظور مش شغال. */
    fun segment(s: AppSettings, idx: Int): FloatArray? {
        val p = cacheFor(s).pts ?: return null
        return if (idx == 0) floatArrayOf(p[0], p[1], p[2], p[3]) else floatArrayOf(p[6], p[7], p[4], p[5])
    }
}

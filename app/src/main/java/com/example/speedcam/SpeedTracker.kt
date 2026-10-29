package com.example.speedcam

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * تتبع المركبات وحساب السرعة بين خطين.
 *
 * تحسينات الدقة:
 *  1) الربط بين الفريمات بيستخدم توقّع حركة المركبة (مش بس أقرب مربع قديم).
 *  2) نقطة القياس: الحافة الأمامية للمركبة (حركة أفقية) أو الحافة السفلية (حركة رأسية)،
 *     بدل مركز المربع اللي بيتهز مع تغيّر حجم المربع.
 *  3) وقت عبور الخط بيتحسب من أحسن خط مستقيم على فريمات قبل وبعد العبور (بدل فريمين بس).
 *     النتيجة بتتأخر فريمين تقريباً (أقل من ربع ثانية) عشان نستنى الفريمات اللي بعد العبور.
 *  4) معايرة السرعة بمعاملين (AppSettings.calibrate): السرعة النهائية = a × الخام + b.
 *  5) هامش خطأ ± لكل قراءة، ومؤشر ثقة (1..100) من بيانات التتبع نفسها: عدد الفريمات حوالين العبور، تذبذب المربع،
 *     انقطاع التتبع، متوسط ثقة الكشف، وقص المربع عند حافة الصورة.
 */
class SpeedTracker {

    private class Sample(val t: Double, val box: NBox)

    /** عبور خط تم رصده بين فريمين (tPrev → tNow) وبانتظار فريمات كفاية بعده لتحسين وقته. */
    private class Pend(val line: Float, val tPrev: Double, val tNow: Double, val interp: Double, val sign: Int)

    /** نتيجة تحسين وقت عبور خط، مع مقاييس جودته. timeErr = عدم يقين وقت العبور بالثواني (تذبذب النقط ÷ سرعة الحركة). */
    private class Refined(val time: Double, val fitted: Boolean, val pts: Int, val timeErr: Double, val clipped: Boolean)

    private class Fit(val time: Double, val rms: Double, val slope: Double)

    private class Track(val id: Int, var box: NBox, var tsSec: Double) {
        val hist = ArrayDeque<Sample>()
        var vx = 0f          // سرعة مركز المربع (وحدات مُطبّعة / ثانية)
        var vy = 0f
        var pA: Pend? = null
        var pB: Pend? = null
        var tA: Double? = null
        var tB: Double? = null
        var rA: Refined? = null
        var rB: Refined? = null
        val path = ArrayList<DoubleArray>()   // عينات المسار على الأرض [الزمن، الإحداثي] (تحويل المنظور بس)
        var kf: Kalman1D? = null
        var kfRejects = 0
        var stillBox: NBox? = null     // آخر مكان اعتبرناه "ثابت" للمركبة (لكشف المركبة الواقفة)
        var stillSince = 0.0
        var stillFlag = false
        var justStopped = false
        var gaps = 0           // كام مرة التتبع انقطع ورجع
        var scoreSum = 0.0     // مجموع ثقة الكشف على كل الفريمات
        var scoreN = 0
        var done = false
        var speed: Double? = null
        var missed = 0
        val votes = IntArray(VehicleType.entries.size)
        fun type(): VehicleType = VehicleType.entries[votes.indices.maxByOrNull { votes[it] } ?: 0]
        init { hist.addLast(Sample(tsSec, box)) }
    }

    class Result(val tracked: List<Tracked>, val events: List<SpeedEvent>, val stopped: List<Tracked> = emptyList())

    private val tracks = ArrayList<Track>()
    @Volatile var rejectNote: String? = null
    private var nextId = 1

    fun reset() = tracks.clear()

    private class Cand(val t: Track, val di: Int, val dist: Float)

    fun update(dets: List<Det>, tsNs: Long, aspect: Float, s: AppSettings): Result {
        val now = tsNs / 1e9
        val events = ArrayList<SpeedEvent>()
        val existing = tracks.toList()

        val cands = ArrayList<Cand>()
        for (t in existing) {
            // المكان المتوقع للمركبة دلوقتي (التوقع محدود بزمن قصير)
            val dtp = min(max(now - t.tsSec, 0.0), MAX_PREDICT_S).toFloat()
            val px = t.box.cx + t.vx * dtp
            val py = t.box.cy + t.vy * dtp
            val gate = max(t.box.w * aspect, t.box.h) * 1.2f
            for ((i, d) in dets.withIndex()) {
                val dLast = hypot((d.box.cx - t.box.cx) * aspect, d.box.cy - t.box.cy)
                val dPred = hypot((d.box.cx - px) * aspect, d.box.cy - py)
                if (min(dLast, dPred) < gate) cands += Cand(t, i, dPred)
            }
        }
        cands.sortBy { it.dist }

        val usedT = HashSet<Track>()
        val usedD = BooleanArray(dets.size)
        for (c in cands) {
            if (c.t in usedT || usedD[c.di]) continue
            usedT += c.t
            usedD[c.di] = true
            applyDet(c.t, dets[c.di], now, s, events)
        }
        for ((i, d) in dets.withIndex()) if (!usedD[i]) {
            val t = Track(nextId++, d.box, now)
            t.votes[d.type.ordinal]++
            t.scoreSum += d.score; t.scoreN++
            tracks += t
        }
        for (t in existing) if (t !in usedT) {
            t.missed++
            // المركبة اختفت: نحسم أي عبور معلّق بالفريمات المتاحة بدل ما نضيعه
            if (t.missed == 1) resolve(t, s, events, force = true)
        }
        tracks.removeAll { it.missed > 10 }

        val tracked = tracks.filter { it.missed == 0 }.map {
            Tracked(it.id, it.type(), it.box, it.speed, it.tA != null || it.pA != null, it.tB != null || it.pB != null)
        }
        val stopped = tracks.filter { it.justStopped }.map {
            it.justStopped = false
            Tracked(it.id, it.type(), it.box, it.speed, it.tA != null || it.pA != null, it.tB != null || it.pB != null)
        }
        return Result(tracked, events, stopped)
    }

    /** اتجاه الحركة على المحور: +1 (زيادة الإحداثي) / -1 / 0 (لسه مش واضح). */
    private fun dirOf(t: Track, s: AppSettings): Int {
        if (t.hist.size < 2) return 0
        val a = t.hist.first().box
        val b = t.hist.last().box
        val d = if (Geo.active(s)) Geo.point(b, 0, s) - Geo.point(a, 0, s)
        else if (s.axisHorizontal) b.cx - a.cx else b.cy - a.cy
        val m = Geo.minDir(s)
        return if (d > m) 1 else if (d < -m) -1 else 0
    }

    /** نقطة القياس على المحور: الحافة الأمامية (أفقي) أو الحافة السفلية = قريبة من الأرض (رأسي). */
    private fun pointOf(b: NBox, dir: Int, s: AppSettings): Float = Geo.point(b, dir, s)

    /** أحسن خط مستقيم p = f(t) على العينات [lo, hi). يرجّع زمن وصوله للخط وتذبذب النقط حوله. null لو التقدير مش موثوق. */
    private fun fitCross(ts: DoubleArray, ps: DoubleArray, lo: Int, hi: Int, line: Float, slopeSign: Int): Fit? {
        val n = hi - lo
        if (n < 3) return null
        val tRef = ts[hi - 1]
        var mx = 0.0; var my = 0.0
        for (i in lo until hi) { mx += ts[i] - tRef; my += ps[i] }
        mx /= n; my /= n
        var sxx = 0.0; var sxy = 0.0
        for (i in lo until hi) {
            val dx = (ts[i] - tRef) - mx
            sxx += dx * dx
            sxy += dx * (ps[i] - my)
        }
        if (sxx < 1e-9) return null
        val slope = sxy / sxx
        if (abs(slope) < 1e-6) return null
        if ((slope > 0) != (slopeSign > 0)) return null
        var ss = 0.0
        for (i in lo until hi) {
            val r = ps[i] - (my + slope * ((ts[i] - tRef) - mx))
            ss += r * r
        }
        return Fit(tRef + mx + (line - my) / slope, sqrt(ss / n), slope)
    }

    private fun applyDet(t: Track, d: Det, now: Double, s: AppSettings, events: MutableList<SpeedEvent>) {
        t.votes[d.type.ordinal]++
        t.hist.addLast(Sample(now, d.box))
        while (t.hist.size > HIST) t.hist.removeFirst()

        // سرعة مركز المربع (للتوقع في الفريم الجاي)
        if (t.hist.size >= 2) {
            val a = t.hist.first()
            val b = t.hist.last()
            val dt = b.t - a.t
            if (dt > 1e-3) {
                t.vx = ((b.box.cx - a.box.cx) / dt).toFloat()
                t.vy = ((b.box.cy - a.box.cy) / dt).toFloat()
            }
        }
        t.scoreSum += d.score; t.scoreN++
        if (t.missed > 0) t.gaps++
        t.box = d.box
        t.tsSec = now
        t.missed = 0

        if (!t.done && Geo.active(s)) trackPath(t, d.box, now, s)
        checkStill(t, d.box, now, s)
        if (t.done || t.hist.size < 2) return

        // 1) رصد عبور جديد بين آخر فريمين
        val dir = dirOf(t, s)
        val n = t.hist.size
        val tPrev = t.hist[n - 2].t
        val tNow = t.hist[n - 1].t
        val p0 = pointOf(t.hist[n - 2].box, dir, s).toDouble()
        val p1 = pointOf(t.hist[n - 1].box, dir, s).toDouble()
        if (p1 != p0) {
            val sign = if (p1 > p0) 1 else -1
            fun straddle(line: Float): Pend? =
                if ((p0 < line && p1 >= line) || (p0 > line && p1 <= line))
                    Pend(line, tPrev, tNow, tPrev + (line - p0) / (p1 - p0) * (tNow - tPrev), sign)
                else null

            val lineA = Geo.lineA(s)
            val lineB = Geo.lineB(s)
            if (s.countAll) {
                // وضع العدّ: تُعدّ المركبة مرة واحدة أول ما تعدّي أي خط (A أو B)، من غير حاجة لدقة التوقيت
                if (straddle(lineA) != null || straddle(lineB) != null) {
                    t.done = true
                    events += SpeedEvent(t.id, t.type(), NO_SPEED, if (p1 > p0) 1 else 2, lane = Geo.lane(t.box, s))
                }
                return
            }
            if (t.tA == null && t.pA == null) t.pA = straddle(lineA)
            if (t.tB == null && t.pB == null) t.pB = straddle(lineB)
        }

        // 2) حسم العبورات اللي اتجمع بعدها فريمات كفاية
        resolve(t, s, events, force = false)
    }

    /**
     * يسجّل موضع المركبة على الأرض في كل فريم. فلتر كالمان بيكشف الفريم اللي المربع اتنطط فيه (انحراف أكبر من 4 سيجما)
     * فنتجاهله. لو اتنطط أكتر من 3 فريمات ورا بعض نعتبر التتبع اتلخبط ونبدأ مسار جديد.
     */
    private fun trackPath(t: Track, box: NBox, now: Double, s: AppSettings) {
        val g = Geo.point(box, 0, s).toDouble()
        if (!g.isFinite()) return
        val sigma = max(0.15, 0.03 * abs((Geo.lineB(s) - Geo.lineA(s)).toDouble()))
        val k = t.kf
        if (k == null) {
            t.kf = Kalman1D(g, now, sigma)
            t.path.add(doubleArrayOf(now, g))
            return
        }
        k.predictTo(now)
        if (abs(k.nis(g, sigma)) > GATE && t.path.size >= 3) {
            t.kfRejects++
            if (t.kfRejects > 3) {
                t.kf = Kalman1D(g, now, sigma)
                t.kfRejects = 0
                t.path.clear()
                t.path.add(doubleArrayOf(now, g))
            }
            return
        }
        t.kfRejects = 0
        k.update(g, sigma)
        t.path.add(doubleArrayOf(now, g))
        if (t.path.size > MAX_PATH) t.path.removeAt(0)
    }

    /** مركبة واقفة: لو مركزها ماتحركش أكتر من نص حجمها لمدة stopSec ثانية نرفع علامة مرة واحدة. */
    private fun checkStill(t: Track, box: NBox, now: Double, s: AppSettings) {
        val ref = t.stillBox
        if (ref == null || hypot(box.cx - ref.cx, box.cy - ref.cy) > 0.5f * max(ref.w, ref.h)) {
            t.stillBox = box
            t.stillSince = now
            t.stillFlag = false
        } else if (!t.stillFlag && s.stopSec > 0 && now - t.stillSince >= s.stopSec) {
            t.stillFlag = true
            t.justStopped = true
        }
    }

    private class Reg(val slope: Double, val se: Double, val n: Int)

    /** انحدار مقاوم (Theil-Sen) على مسار المركبة جوه منطقة القياس (+20% من كل ناحية). null لو العينات مش كفاية. */
    private fun regression(t: Track, s: AppSettings): Reg? {
        val lA = Geo.lineA(s).toDouble()
        val lB = Geo.lineB(s).toDouble()
        val len = abs(lB - lA)
        val lo = min(lA, lB) - 0.2 * len
        val hi = max(lA, lB) + 0.2 * len
        val pts = t.path.filter { it[1] in lo..hi }
        if (pts.size < MIN_REG) return null
        val ts = DoubleArray(pts.size) { pts[it][0] }
        val gs = DoubleArray(pts.size) { pts[it][1] }
        if (ts[ts.size - 1] - ts[0] < 0.2) return null
        val f = RobustStats.theilSen(ts, gs) ?: return null
        return Reg(f.slope, f.se, pts.size)
    }

    /** يحسم أوقات العبور المعلّقة، ولو الخطين اتحسموا يحسب السرعة ويطلّع الحدث. */
    private fun resolve(t: Track, s: AppSettings, events: MutableList<SpeedEvent>, force: Boolean) {
        if (t.done || s.countAll) return
        val dir = dirOf(t, s)

        fun ready(p: Pend?): Boolean =
            p != null && (force || t.hist.count { it.t >= p.tNow - 1e-9 } >= POST_N)

        fun refine(p: Pend): Refined {
            val n = t.hist.size
            val ts = DoubleArray(n) { t.hist[it].t }
            val ps = DoubleArray(n) { pointOf(t.hist[it].box, dir, s).toDouble() }
            var i = 0
            while (i < n && ts[i] < p.tNow - 1e-9) i++
            val lo = max(0, i - WIN)
            val hi = min(n, i + WIN)
            var clipped = false
            for (k in lo until hi) {
                val b = t.hist[k].box
                if (b.l < 0.01f || b.r > 0.99f || b.t < 0.01f || b.b > 0.99f) clipped = true
            }
            val fit = fitCross(ts, ps, lo, hi, p.line, p.sign)
            val slack = 0.5 * (p.tNow - p.tPrev)
            return if (fit != null && fit.time >= p.tPrev - slack && fit.time <= p.tNow + slack) {
                Refined(fit.time, true, hi - lo, fit.rms / abs(fit.slope), clipped)
            } else {
                Refined(p.interp, false, hi - lo, 0.5 * (p.tNow - p.tPrev), clipped)
            }
        }

        val pa = t.pA
        val pb = t.pB
        if (t.tA == null && pa != null && ready(pa)) { val r = refine(pa); t.rA = r; t.tA = r.time }
        if (t.tB == null && pb != null && ready(pb)) { val r = refine(pb); t.rB = r; t.tB = r.time }

        val a = t.tA
        val b = t.tB
        if (a != null && b != null) {
            t.done = true
            val dt = abs(b - a)
            val v = if (dt > 0.0) s.calibrate(s.distanceM / dt * 3.6) else 0.0
            if (dt > 0.05 && v in 2.0..300.0) {
                // مع تحويل المنظور: لو المسار فيه عينات كفاية نستخدم الانحدار المقاوم بدل نقطتين بس
                var vFinal = v
                var err = (v * (relError(t) ?: 0.0)).coerceAtMost(v)
                var confAdj = 0
                if (Geo.active(s)) {
                    val reg = regression(t, s)
                    if (reg != null) {
                        val vCal = s.calibrate(abs(reg.slope) * 3.6)
                        val sameDir = (reg.slope > 0) == (a < b)
                        if (sameDir && abs(vCal - v) <= 0.35 * max(v, 1.0)) {
                            vFinal = vCal
                            err = max(0.5, reg.se * 3.6 * s.speedCalib).coerceAtMost(vCal)
                            confAdj = 5 + min(10, reg.n - MIN_REG)
                        } else {
                            confAdj = -15   // الانحدار مش متفق مع نقطتين العبور: القراءة مشكوك فيها
                        }
                    }
                }
                t.speed = vFinal
                val conf = (confidence(t) + confAdj).coerceIn(1, 100)
                events += SpeedEvent(t.id, t.type(), vFinal, if ((a < b) == (Geo.lineA(s) < Geo.lineB(s))) 1 else 2, conf, err.toFloat(), Geo.lane(t.box, s))
            } else {
                rejectNote = "مُهمَل: " + v.toInt() + " كم/س خلال " + String.format(java.util.Locale.US, "%.2f", dt) + " ث"
            }
        }
    }

    /**
     * ثقة القراءة 1..100 من بيانات التتبع (مش مرجع حقيقي للسرعة، لكنها بتكشف القراءات الضعيفة):
     * أقل عدد فريمات حوالين العبور، تذبذب المربع، رجوع لطريقة أبسط، انقطاع التتبع، ضعف ثقة الكشف، قص المربع.
     */
    /** الخطأ النسبي المتوقع في السرعة = عدم يقين زمنَي العبور ÷ الزمن بين الخطين (مستقل عن عدد الـ FPS). */
    private fun relError(t: Track): Double? {
        val ra = t.rA ?: return null
        val rb = t.rB ?: return null
        val dtAB = abs(rb.time - ra.time)
        return if (dtAB > 0.0) sqrt(ra.timeErr * ra.timeErr + rb.timeErr * rb.timeErr) / dtAB else 1.0
    }

    private fun confidence(t: Track): Int {
        val ra = t.rA
        val rb = t.rB
        if (ra == null || rb == null) return 0
        var c = 100.0
        val pts = min(ra.pts, rb.pts)
        c -= (2 * WIN - min(2 * WIN, pts)) * 6.0
        c -= min(40.0, (relError(t) ?: 1.0) * 400.0)
        if (!ra.fitted) c -= 8.0
        if (!rb.fitted) c -= 8.0
        c -= min(20, t.gaps * 10)
        val meanScore = if (t.scoreN > 0) t.scoreSum / t.scoreN else 0.5
        c -= (1.0 - meanScore).coerceIn(0.0, 1.0) * 30.0
        if (ra.clipped || rb.clipped) c -= 10.0
        return c.roundToInt().coerceIn(1, 100)
    }

    private companion object {
        const val HIST = 8               // أقصى عدد فريمات محفوظة لكل مركبة
        const val WIN = 3                // عدد الفريمات قبل/بعد العبور المستخدمة في تقدير وقته
        const val POST_N = 3             // نستنى كام فريم (شامل فريم العبور) بعد العبور قبل الحسم
        const val MAX_PREDICT_S = 0.6    // أقصى زمن للتوقع لو المركبة اتأخرت عن الظهور
        const val GATE = 4.0             // أكبر انحراف مُطبّع (سيجما) نقبله في فلتر كالمان
        const val MAX_PATH = 60          // أقصى عدد عينات مسار لكل مركبة
        const val MIN_REG = 5            // أقل عدد عينات لاستخدام الانحدار
        const val MIN_DIR = 0.004f       // أقل إزاحة (مُطبّعة) نعتبرها اتجاه حركة حقيقي
    }
}

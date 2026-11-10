package com.example.speedcam

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * حالة ثبات الكاميرا.
 * level: 0 = ثابتة، 1 = اهتزاز/إزاحة بسيطة، 2 = الكاميرا اتحركت فعلاً.
 * score: 0..100 (100 = ثابتة تماماً) وبتدخل في ثقة كل قياس.
 * shiftPct: حجم الإزاحة عن المرجع كنسبة من عرض الصورة.
 */
data class CamStability(
    val hasRef: Boolean = false,
    val level: Int = 0,
    val score: Int = 100,
    val shiftPct: Float = 0f,
    val jitterPct: Float = 0f,
    val message: String = ""
)

/**
 * بيقارن كل ~ثانية الفريم الحالي بصورة مرجعية صغيرة (رمادي 96 بكسل عرض) محفوظة وقت المعايرة.
 * الصورة مقسّمة لبلوكات، وكل بلوك بيدوّر على أحسن إزاحة (SAD بعد طرح المتوسط)، والوسيط بين البلوكات
 * هو الإزاحة الحقيقية، فالعربيات المتحركة (بلوكات شاذة) ما بتأثرش على النتيجة.
 */
class CameraStability(ctx: Context) {
    private val file = File(ctx.applicationContext.filesDir, "cam_ref.bin")
    val state = MutableStateFlow(CamStability())

    @Volatile private var ref: IntArray? = null
    private var refW = 0
    private var refH = 0
    @Volatile private var wantCapture = false
    private var lastCheckMs = 0L
    private val recent = ArrayDeque<Pair<Float, Float>>()   // آخر إزاحات (dx, dy) بالبكسل
    private var movedStreak = 0

    init { load() }

    /** يطلب حفظ الفريم الجاي كمرجع جديد (بيتنفذ على خيط الكاميرا). */
    fun captureReference() { wantCapture = true }

    /** الويزارد بيشغّل المراقبة حتى والقياس واقف عشان المستخدم يشوف ثبات الموبايل لحظياً. */
    @Volatile var watch = false

    fun wantsFrame(): Boolean =
        wantCapture || (watch && ref != null && android.os.SystemClock.elapsedRealtime() - lastCheckMs >= CHECK_MS)

    fun clearReference() {
        ref = null; refW = 0; refH = 0; wantCapture = false
        recent.clear(); movedStreak = 0
        try { file.delete() } catch (_: Throwable) { }
        state.value = CamStability()
    }

    /** بيتنادى لكل فريم من SpeedEngine.process؛ بيشتغل بس كل ثانية تقريباً فتكلفته صغيرة. */
    fun offer(frame: Bitmap, nowMs: Long, guardOn: Boolean) {
        if (!wantCapture && (!guardOn || nowMs - lastCheckMs < CHECK_MS)) return
        lastCheckMs = nowMs
        val (g, w, h) = gray(frame)
        if (wantCapture) {
            wantCapture = false
            ref = g; refW = w; refH = h
            recent.clear(); movedStreak = 0
            save(g, w, h)
            state.value = CamStability(hasRef = true, message = tr(R.string.str_0062))
            return
        }
        val r = ref
        if (r == null) { if (state.value.hasRef) state.value = CamStability(); return }
        if (w != refW || h != refH) {
            // نسبة الصورة اتغيرت (كاميرا تانية أو دوران): المرجع مش صالح
            state.value = CamStability(hasRef = true, level = 2, score = 20, message = tr(R.string.str_0063))
            return
        }
        val (dx, dy, quality) = estimateShift(r, g, w, h)
        recent.addLast(dx to dy)
        while (recent.size > RECENT_N) recent.removeFirst()

        val shiftPx = sqrt(dx * dx + dy * dy)
        val shiftPct = shiftPx / w * 100f
        // الاهتزاز = انحراف معياري لآخر الإزاحات (الكاميرا على حامل بتهتز مع الريح/الطريق)
        val mx = recent.map { it.first }.average().toFloat()
        val my = recent.map { it.second }.average().toFloat()
        val jitterPx = if (recent.size < 3) 0f else sqrt(recent.map { (it.first - mx) * (it.first - mx) + (it.second - my) * (it.second - my) }.average().toFloat())
        val jitterPct = jitterPx / w * 100f

        // بلوكات قليلة اتطابقت = مشهد اتغير (ضوء/تغطية)، منعتبرهوش حركة كاميرا عشان ما نعملش إنذار كاذب
        val reliable = quality >= MIN_BLOCK_OK
        val movedNow = reliable && shiftPct >= MOVED_PCT
        movedStreak = if (movedNow) movedStreak + 1 else 0
        val moved = movedStreak >= 2    // لازم قراءتين ورا بعض عشان رعشة واحدة ما تعملش إنذار

        var score = 100f
        score -= (shiftPct * 18f).coerceAtMost(60f)
        score -= (jitterPct * 40f).coerceAtMost(30f)
        if (!reliable) score = max(score, 60f).coerceAtMost(80f)
        val s = score.toInt().coerceIn(0, 100)
        val level = when {
            moved -> 2
            shiftPct >= WARN_PCT || jitterPct >= 0.35f -> 1
            else -> 0
        }
        val msg = when {
            moved -> tr(R.string.str_0064, fmt(shiftPct))
            level == 1 && jitterPct >= 0.35f -> tr(R.string.str_0065)
            level == 1 -> tr(R.string.str_0066, fmt(shiftPct))
            else -> ""
        }
        state.value = CamStability(true, level, s, shiftPct, jitterPct, msg)
    }

    // ---------- معالجة الصورة ----------

    private fun gray(frame: Bitmap): Triple<IntArray, Int, Int> {
        val w = GW
        val h = max(8, (frame.height.toFloat() / frame.width * GW).toInt())
        val small = Bitmap.createScaledBitmap(frame, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== frame) small.recycle()
        val g = IntArray(w * h) { i ->
            val c = px[i]
            (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }
        return Triple(g, w, h)
    }

    /** يرجّع (dx, dy, نسبة البلوكات الموثوقة). dx/dy إزاحة الفريم الحالي عن المرجع بالبكسل. */
    private fun estimateShift(a: IntArray, b: IntArray, w: Int, h: Int): Triple<Float, Float, Float> {
        val bx = 4
        val by = 3
        val bw = w / bx
        val bh = h / by
        val dxs = ArrayList<Int>()
        val dys = ArrayList<Int>()
        var ok = 0
        for (j in 0 until by) for (i in 0 until bx) {
            val x0 = i * bw
            val y0 = j * bh
            // بلوك مسطح (سما/أسفلت) مفيهوش تفاصيل: ما ينفعش للمطابقة
            if (variance(a, w, x0, y0, bw, bh) < MIN_VAR) continue
            var best = Long.MAX_VALUE
            var bestDx = 0
            var bestDy = 0
            var second = Long.MAX_VALUE
            for (dy in -R..R) for (dx in -R..R) {
                val d = sad(a, b, w, h, x0, y0, bw, bh, dx, dy)
                if (d < best) { second = best; best = d; bestDx = dx; bestDy = dy } else if (d < second) second = d
            }
            // قمة واضحة: أحسن تطابق لازم يكون أحسن بشكل مقنع من اللي بعده
            if (best < second * 0.92) { dxs += bestDx; dys += bestDy; ok++ }
        }
        if (dxs.isEmpty()) return Triple(0f, 0f, 0f)
        dxs.sort(); dys.sort()
        val mdx = dxs[dxs.size / 2].toFloat()
        val mdy = dys[dys.size / 2].toFloat()
        // اتفاق البلوكات على نفس الإزاحة (±1) هو مقياس الثقة
        var agree = 0
        for (k in dxs.indices) if (abs(dxs[k] - mdx) <= 1f && abs(dys[k] - mdy) <= 1f) agree++
        return Triple(mdx, mdy, agree.toFloat() / (bx * by))
    }

    private fun variance(a: IntArray, w: Int, x0: Int, y0: Int, bw: Int, bh: Int): Double {
        var s = 0L; var s2 = 0L; var n = 0
        for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) { val v = a[y * w + x]; s += v; s2 += v.toLong() * v; n++ }
        val m = s.toDouble() / n
        return s2.toDouble() / n - m * m
    }

    /** SAD بعد طرح متوسط كل بلوك (عشان تغير السطوع ما يبوّظش المطابقة). */
    private fun sad(a: IntArray, b: IntArray, w: Int, h: Int, x0: Int, y0: Int, bw: Int, bh: Int, dx: Int, dy: Int): Long {
        if (x0 + dx < 0 || y0 + dy < 0 || x0 + dx + bw > w || y0 + dy + bh > h) return Long.MAX_VALUE / 4
        var sa = 0L; var sb = 0L
        for (y in 0 until bh) for (x in 0 until bw) {
            sa += a[(y0 + y) * w + x0 + x]
            sb += b[(y0 + y + dy) * w + x0 + x + dx]
        }
        val n = bw * bh
        val ma = (sa / n).toInt()
        val mb = (sb / n).toInt()
        var s = 0L
        for (y in 0 until bh) for (x in 0 until bw) {
            s += abs((a[(y0 + y) * w + x0 + x] - ma) - (b[(y0 + y + dy) * w + x0 + x + dx] - mb))
        }
        return s
    }

    // ---------- الحفظ ----------

    private fun save(g: IntArray, w: Int, h: Int) {
        try {
            file.outputStream().buffered().use { o ->
                o.write(w shr 8); o.write(w and 255); o.write(h shr 8); o.write(h and 255)
                for (v in g) o.write(v)
            }
        } catch (_: Throwable) { }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val bytes = file.readBytes()
            if (bytes.size < 4) return
            val w = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            val h = ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
            if (w <= 0 || h <= 0 || bytes.size != 4 + w * h) return
            ref = IntArray(w * h) { bytes[4 + it].toInt() and 255 }
            refW = w; refH = h
            state.value = CamStability(hasRef = true)
        } catch (_: Throwable) { }
    }

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.1f", v)

    private companion object {
        const val GW = 96              // عرض الصورة المصغّرة
        const val R = 5                // مدى البحث ±بكسل (≈ ±5% من العرض)
        const val CHECK_MS = 1000L
        const val RECENT_N = 8
        const val MOVED_PCT = 1.5f     // إزاحة أكبر من 1.5% من العرض = اتحركت
        const val WARN_PCT = 0.7f
        const val MIN_VAR = 60.0
        const val MIN_BLOCK_OK = 0.34f
    }
}

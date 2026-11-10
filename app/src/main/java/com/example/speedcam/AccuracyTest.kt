package com.example.speedcam

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.sqrt

/** عبور واحد في اختبار الدقة: سرعة حقيقية معروفة، السرعة الخام (قبل المعايرة)، والمعروضة. */
data class TestPass(val real: Float, val raw: Float, val cal: Float, val conf: Int, val ts: Long) {
    val err: Float get() = cal - real
    val relErr: Float get() = (cal - real) / real
}

data class TestState(
    val active: Boolean = false,
    val known: Float = 50f,                 // السرعة الحقيقية للعربية المختبرة بالكم/س
    val passes: List<TestPass> = emptyList(),
    val ignored: Int = 0                    // عبورات اتهملت (ثقة ضعيفة أو بعيدة جداً عن السرعة المعروفة = غالباً عربية تانية)
)

/** ملخص نتيجة الاختبار. كل القيم بالكم/س ما عدا النسب. */
class TestSummary(
    val n: Int,
    val biasKmh: Float,      // متوسط الخطأ (موجب = التطبيق بيقرأ أعلى من الحقيقي)
    val sdKmh: Float,        // تشتت القراءات حوالين متوسطها
    val biasPct: Float,
    val sdPct: Float,
    val within95Kmh: Float,  // 95% من القراءات الجاية غالباً جوه ± القيمة دي
    val needsCalibration: Boolean,
    val noisy: Boolean
) {
    val good: Boolean get() = !needsCalibration && !noisy
}

/**
 * وضع اختبار الدقة: المستخدم بيعدّي بعربية بسرعة معروفة (من العداد أو GPS) عدة مرات،
 * والتطبيق بيلتقط كل عبور تلقائياً من غير ما حد يكتب القراءة، وبيحسب الانحياز والتشتت ويقترح الحل.
 * الحالة في الذاكرة بس (بتتصفّر لما التطبيق يتقفل).
 */
class AccuracyTest {
    val state = MutableStateFlow(TestState())

    fun start() = state.update { it.copy(active = true) }
    fun stop() = state.update { it.copy(active = false) }
    fun reset() = state.update { TestState(known = it.known) }
    fun setKnown(k: Float) = state.update { it.copy(known = k) }
    fun remove(i: Int) = state.update { st ->
        st.copy(passes = st.passes.filterIndexed { idx, _ -> idx != i })
    }

    /** بيتنادى من SpeedEngine لكل حدث عبور (بعد تعديل الثقة بحسب ثبات الكاميرا). */
    fun offer(ev: SpeedEvent, s: AppSettings) {
        val st0 = state.value
        if (!st0.active || ev.speedKmh < 0 || st0.known <= 0f) return
        val cal = ev.speedKmh.toFloat()
        val ignore = ev.conf in 1..49 || abs(cal - st0.known) / st0.known > MAX_REL_DEV
        if (ignore) { state.update { it.copy(ignored = it.ignored + 1) }; return }
        val calib = if (s.speedCalib > 0.01f) s.speedCalib else 1f
        val raw = (cal - s.speedOffset) / calib      // نرجّع القراءة للخام عشان تفضل صالحة حتى لو المعايرة اتغيرت بعدين
        if (raw <= 0f) return
        state.update { st ->
            if (!st.active) st
            else st.copy(passes = (st.passes + TestPass(st.known, raw, cal, ev.conf, System.currentTimeMillis())).takeLast(MAX_PASSES))
        }
    }

    companion object {
        const val MAX_PASSES = 30
        private const val MAX_REL_DEV = 0.4f      // أكتر من 40% بعيد عن السرعة المعروفة = مش العربية المختبرة
        private const val BIAS_PCT = 3f
        private const val SD_PCT = 5f

        /** null لو العبورات أقل من 3 (مفيش إحصاء له معنى). */
        fun summarize(passes: List<TestPass>): TestSummary? {
            val n = passes.size
            if (n < 3) return null
            val errs = passes.map { it.err.toDouble() }
            val rel = passes.map { it.relErr.toDouble() * 100.0 }
            val bias = errs.average()
            val sd = sqrt(errs.sumOf { (it - bias) * (it - bias) } / (n - 1))
            val relBias = rel.average()
            val relSd = sqrt(rel.sumOf { (it - relBias) * (it - relBias) } / (n - 1))
            val half = RobustStats.t95(n - 1) * sd * sqrt(1.0 + 1.0 / n)
            return TestSummary(
                n, bias.toFloat(), sd.toFloat(), relBias.toFloat(), relSd.toFloat(),
                (abs(bias) + half).toFloat(),
                needsCalibration = abs(relBias) > BIAS_PCT,
                noisy = relSd > SD_PCT
            )
        }
    }
}

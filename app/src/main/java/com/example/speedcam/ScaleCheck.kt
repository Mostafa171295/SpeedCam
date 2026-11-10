package com.example.speedcam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.math.abs

/**
 * level: 0 = المقياس سليم، 1 = انحراف محتمل، 2 = انحراف كبير. n = عدد العربيات اللي اتقاست.
 * medianM = متوسط (وسيط) طول الملاكي المقاس بالمتر. suggestedDistM = المسافة اللي كانت هتخلّي الوسيط = الطول المتوقع.
 */
data class ScaleState(
    val applicable: Boolean = false,
    val n: Int = 0,
    val medianM: Float = 0f,
    val deviationPct: Float = 0f,
    val level: Int = 0,
    val suggestedDistM: Float = 0f
)

/**
 * مراقب المقياس بأطوال العربيات: بيقيس طول كل عربية ملاكي معدّية بين الخطين (عرض الصندوق × المسافة ÷ الفرق بين الخطين)،
 * وبياخد الوسيط. الملاكي بطول حوالين 4.3 متر، فلو الوسيط بعيد كتير يبقى غالباً المسافة اللي اتدخلت بين الخطين غلط
 * (أو الكاميرا اتحركت/الزووم اتغير). بيشتغل من غير ما تنزل الشارع.
 * حدوده: حركة أفقية وخطوط عادية بس (مش وضع المنظور)، والصندوق بيزوّد الطول شوية، فالانحراف الصغير طبيعي.
 */
class ScaleCheck {
    val state = MutableStateFlow(ScaleState())

    private val lens = ArrayDeque<Float>()
    private val seen = HashSet<Int>()
    private var key = ""

    fun reset() {
        synchronized(this) { lens.clear(); seen.clear() }
        state.value = ScaleState(applicable = state.value.applicable)
    }

    fun offer(tracked: List<Tracked>, s: AppSettings) {
        val gap = abs(s.lineB - s.lineA)
        val ok = s.axisHorizontal && !Geo.active(s) && gap >= 0.05f && s.distanceM > 0f
        val k = "${s.lineA}|${s.lineB}|${s.distanceM}|${s.axisHorizontal}|${Geo.active(s)}|${s.zoom}"
        if (k != key) { key = k; reset() }
        if (!ok) { if (state.value.applicable) state.value = ScaleState(applicable = false); return }

        val lo = minOf(s.lineA, s.lineB)
        val hi = maxOf(s.lineA, s.lineB)
        var changed = false
        synchronized(this) {
            for (t in tracked) {
                if (t.type != VehicleType.CAR || t.id in seen) continue
                val b = t.box
                if (b.l < EDGE || b.r > 1f - EDGE) continue            // صندوق مقصوص بحافة الصورة
                if (b.cx < lo || b.cx > hi) continue                     // نقيس وهي بين الخطين بس
                seen += t.id
                if (seen.size > 2000) seen.clear()
                lens.addLast(b.w * s.distanceM / gap)
                while (lens.size > WINDOW) lens.removeFirst()
                changed = true
            }
        }
        if (!changed && state.value.applicable) return
        publish(s)
    }

    private fun publish(s: AppSettings) {
        val l = synchronized(this) { lens.toList() }
        if (l.size < MIN_N) { state.value = ScaleState(true, l.size); return }
        val med = RobustStats.median(l.map { it.toDouble() }).toFloat()
        val dev = (med - EXPECTED_M) / EXPECTED_M * 100f
        val level = when {
            abs(dev) > BAD_PCT -> 2
            abs(dev) > WARN_PCT -> 1
            else -> 0
        }
        state.value = ScaleState(true, l.size, med, dev, level, s.distanceM * EXPECTED_M / med)
    }

    companion object {
        const val EXPECTED_M = 4.3f    // طول الملاكي المتوسط (متر)
        const val MIN_N = 15           // أقل عدد عربيات للحكم
        private const val WINDOW = 80
        private const val EDGE = 0.02f
        private const val WARN_PCT = 18f
        private const val BAD_PCT = 32f
    }
}

/** شارة نتيجة فحص المقياس. */
@Composable
fun ScaleCheckBadge(sc: ScaleState, modifier: Modifier = Modifier) {
    if (!sc.applicable) return
    val c = when (sc.level) { 0 -> Color(0xFF2E7D32); 1 -> Color(0xFFE65100); else -> Color(0xFFB71C1C) }
    val text = when {
        sc.n < ScaleCheck.MIN_N -> tr(R.string.sc_collecting, sc.n, ScaleCheck.MIN_N)
        sc.level == 0 -> tr(R.string.sc_ok, fmtD(sc.medianM), fmtD(ScaleCheck.EXPECTED_M))
        else -> tr(R.string.sc_warn, fmtD(sc.medianM), fmtD(ScaleCheck.EXPECTED_M), String.format(Locale.US, "%.0f", abs(sc.deviationPct)), fmtD(sc.suggestedDistM))
    }
    val col = if (sc.n < ScaleCheck.MIN_N) MaterialTheme.colorScheme.onSurfaceVariant else c
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.12f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = col, fontWeight = if (sc.level > 0 && sc.n >= ScaleCheck.MIN_N) FontWeight.Bold else FontWeight.Normal)
    }
}

/** رقم المسافة بالوحدة المعروضة (متر أو قدم). */
private fun fmtD(m: Float): String = String.format(Locale.US, "%.1f", Units.dist(m))

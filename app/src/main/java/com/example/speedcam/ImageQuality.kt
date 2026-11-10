package com.example.speedcam

import android.graphics.Bitmap
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
import kotlin.math.max

/**
 * نتيجة فحص وضوح الصورة.
 * sharp: 0..100 (-1 = مش معروف: المشهد فيه تفاصيل قليلة جداً)، brightPct: متوسط السطوع %.
 * issue: 0 تمام، 1 غامقة، 2 مفرطة الإضاءة، 3 مش حادة.
 */
data class ImgQuality(
    val ready: Boolean = false,
    val sharp: Int = -1,
    val brightPct: Int = 0,
    val clipDarkPct: Int = 0,
    val clipBrightPct: Int = 0,
    val issue: Int = 0
) {
    val message: Int?
        get() = when (issue) {
            1 -> R.string.iq_dark
            2 -> R.string.iq_bright
            3 -> R.string.iq_blur
            else -> null
        }
}

/**
 * بيفحص وضوح الصورة كل ثانية تقريباً من نفس الفريمات اللي بتتحلل (من غير تكلفة تذكر):
 *  - الحدّة: نسبة تباين اللابلاسيان قبل وبعد تنعيم خفيف. الصورة الحادة بتفقد تفاصيل كتير بالتنعيم (نسبة عالية)،
 *    والصورة المهزوزة أو برّه الفوكس أصلاً ناعمة (نسبة قريبة من 1). النسبة مش بتتأثر بمستوى إضاءة المشهد.
 *  - السطوع وقص الإضاءة: متوسط السطوع، ونسبة البكسلات الغامقة جداً أو المحروقة.
 * ملحوظة: الأرقام استرشادية (عتباتها اتظبطت بالتقدير) ومقصود بيها تنبيه المستخدم، مش قياس معملي.
 */
class ImageQualityMonitor {
    val state = MutableStateFlow(ImgQuality())

    @Volatile private var lastMs = 0L
    private var sharpEma = -1f

    fun due(nowMs: Long): Boolean = nowMs - lastMs >= CHECK_MS

    fun offer(frame: Bitmap, nowMs: Long) {
        if (!due(nowMs)) return
        lastMs = nowMs

        // نفحص الجزء الأوسط (الطريق عادةً) بعرض ثابت
        val cx = (frame.width * 0.1f).toInt()
        val cy = (frame.height * 0.1f).toInt()
        val cw = (frame.width * 0.8f).toInt().coerceAtLeast(16)
        val ch = (frame.height * 0.8f).toInt().coerceAtLeast(16)
        val sub = Bitmap.createBitmap(frame, cx, cy, cw.coerceAtMost(frame.width - cx), ch.coerceAtMost(frame.height - cy))
        val w = GW
        val h = max(16, (sub.height.toFloat() / sub.width * GW).toInt())
        val small = Bitmap.createScaledBitmap(sub, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== sub) small.recycle()
        if (sub !== frame) sub.recycle()
        val g = IntArray(w * h) { i ->
            val c = px[i]
            (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }

        // السطوع وقص الإضاءة
        var sum = 0L
        var dark = 0
        var bright = 0
        for (v in g) { sum += v; if (v <= 8) dark++; if (v >= 248) bright++ }
        val mean = sum.toDouble() / g.size
        val darkPct = (dark * 100.0 / g.size).toInt()
        val brightClipPct = (bright * 100.0 / g.size).toInt()

        // الحدّة: لابلاسيان الصورة الأصلية ÷ لابلاسيان نسخة منعّمة 3×3
        val vOrig = lapVar(g, w, h)
        val blurred = box3(g, w, h)
        val vBlur = lapVar(blurred, w, h)
        val sharp: Float = if (vOrig < MIN_TEXTURE) -1f else {
            val ratio = (vOrig / max(vBlur, 0.5)).toFloat()
            ((ratio - R_BLUR) / (R_SHARP - R_BLUR) * 100f).coerceIn(0f, 100f)
        }
        if (sharp >= 0f) sharpEma = if (sharpEma < 0f) sharp else sharpEma * 0.6f + sharp * 0.4f
        val sh = if (sharp < 0f) -1 else sharpEma.toInt()

        val issue = when {
            brightClipPct >= 35 || mean >= 215 -> 2
            mean <= 45 || darkPct >= 45 -> 1
            sh in 0..29 -> 3
            else -> 0
        }
        state.value = ImgQuality(true, sh, (mean * 100 / 255).toInt(), darkPct, brightClipPct, issue)
    }

    private fun lapVar(g: IntArray, w: Int, h: Int): Double {
        var s = 0.0
        var s2 = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]).toDouble()
            s += l; s2 += l * l; n++
        }
        if (n == 0) return 0.0
        val m = s / n
        return s2 / n - m * m
    }

    private fun box3(g: IntArray, w: Int, h: Int): IntArray {
        val o = g.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var a = 0
            for (dy in -1..1) for (dx in -1..1) a += g[(y + dy) * w + x + dx]
            o[y * w + x] = a / 9
        }
        return o
    }

    private companion object {
        const val GW = 256
        const val CHECK_MS = 1000L
        const val MIN_TEXTURE = 6.0   // أقل تباين لابلاسيان نعتبره مشهد فيه تفاصيل كفاية للحكم على الحدّة
        const val R_BLUR = 1.6f       // نسبة ≤ كده = ناعمة جداً (0%)
        const val R_SHARP = 7f        // نسبة ≥ كده = حادة (100%)
    }
}

/** لون المؤشر حسب الحالة. */
fun imgQualityColor(q: ImgQuality): Color = when (q.issue) {
    0 -> Color(0xFF2E7D32)
    3 -> Color(0xFFE65100)
    else -> Color(0xFFB71C1C)
}

/** شارة تفصيلية لوضوح الصورة (للإعدادات وويزارد المعايرة). */
@Composable
fun ImageQualityBadge(q: ImgQuality, modifier: Modifier = Modifier) {
    if (!q.ready) return
    val c = imgQualityColor(q)
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.12f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            if (q.sharp >= 0) tr(R.string.iq_clarity, q.sharp) else tr(R.string.iq_clarity_unknown),
            color = c, fontWeight = FontWeight.Bold
        )
        Text(tr(R.string.iq_brightness, q.brightPct, q.clipDarkPct, q.clipBrightPct), style = MaterialTheme.typography.bodySmall)
        Text(q.message?.let { tr(it) } ?: tr(R.string.iq_ok), style = MaterialTheme.typography.bodySmall, color = c)
    }
}

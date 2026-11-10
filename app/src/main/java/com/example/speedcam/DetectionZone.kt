package com.example.speedcam

import kotlin.math.max
import kotlin.math.min

/**
 * منطقة الكشف: بدل ما الموديل يشتغل على الصورة كلها، بنقص المنطقة اللي فيها القياس ونديها له.
 *
 * ليه ده بيحسّن الكشف: الموديل بياخد مدخل مربع صغير (حوالين 320×320) وبيحشر الصورة فيه من غير ما يمطّها،
 * فالإطار العريض بيتصغّر على ضلعه الأطول. قص منطقة أقصر على الضلع ده بيكبّر العربيات البعيدة بنفس تكلفة الموديل.
 * المكسب بيتحدد بالضلع الأطول للمنطقة (gain())، ولو المنطقة قريبة من الصورة كلها مش بنقص خالص.
 *
 *  - تلقائي: على محور الحركة بنقص من الخطين A وB زائد هامش (عشان التتبع يلحق العربية قبل وبعد الخطين).
 *  - يدوي: أربع حدود تتقاطع مع التلقائي (مثلاً تشيل السما أو الرصيف).
 */
object DetectionZone {
    private const val MIN_SIDE = 0.2f        // أصغر عرض/ارتفاع للمنطقة (نسبة من الصورة)
    private const val MIN_MARGIN = 0.2f      // أقل هامش حوالين الخطين
    private const val NO_CROP_ABOVE = 0.88f  // لو المنطقة أكبر من كده (بالضلع الأطول) مفيش مكسب يستاهل

    /** مستطيل الكشف (إحداثيات 0..1 من الصورة الكاملة)، أو null = الصورة كلها. */
    fun compute(s: AppSettings, aspect: Float): NBox? {
        var l = 0f; var t = 0f; var r = 1f; var b = 1f

        if (s.roiAuto) {
            val segA = Geo.segment(s, 0)
            val segB = Geo.segment(s, 1)
            if (segA != null && segB != null) {
                // وضع المنظور: صندوق النقط الأربعة + هامش
                val x0 = minOf(segA[0], segA[2], segB[0], segB[2]); val x1 = maxOf(segA[0], segA[2], segB[0], segB[2])
                val y0 = minOf(segA[1], segA[3], segB[1], segB[3]); val y1 = maxOf(segA[1], segA[3], segB[1], segB[3])
                val mx = max(MIN_MARGIN, (x1 - x0) * 0.6f)
                val my = max(MIN_MARGIN, (y1 - y0) * 0.6f)
                l = x0 - mx; r = x1 + mx; t = y0 - my; b = y1 + my
            } else {
                val a = min(s.lineA, s.lineB)
                val c = max(s.lineA, s.lineB)
                val m = max(MIN_MARGIN, (c - a) * 0.8f)
                if (s.axisHorizontal) { l = a - m; r = c + m } else { t = a - m; b = c + m }
            }
        }

        // الحدود اليدوية (تتقاطع مع التلقائي)
        l = max(l, s.zoneL); t = max(t, s.zoneT); r = min(r, s.zoneR); b = min(b, s.zoneB)
        l = l.coerceIn(0f, 1f); t = t.coerceIn(0f, 1f); r = r.coerceIn(0f, 1f); b = b.coerceIn(0f, 1f)
        if (r - l < MIN_SIDE || b - t < MIN_SIDE) return null          // منطقة ضيقة بشكل مش منطقي: نرجع للصورة كلها

        val roi = NBox(l, t, r, b)
        // مفيش مكسب لو الضلع الأطول لسه قريب من الصورة الكاملة
        return if (roi.longSide(aspect) > full(aspect) * NO_CROP_ABOVE) null else roi
    }

    /** الضلع الأطول للصورة الكاملة بوحدة (الارتفاع = 1). */
    private fun full(aspect: Float) = max(aspect, 1f)

    private fun NBox.longSide(aspect: Float) = max(w * aspect, h)

    /** كام مرة التفاصيل بتزيد على العربيات بسبب القص (1 = من غير مكسب). */
    fun gain(roi: NBox?, aspect: Float): Float = if (roi == null) 1f else full(aspect) / roi.longSide(aspect)

    /** نسبة مساحة المنطقة من الصورة (للعرض). */
    fun areaPct(roi: NBox?): Int = if (roi == null) 100 else (roi.w * roi.h * 100f).toInt()
}

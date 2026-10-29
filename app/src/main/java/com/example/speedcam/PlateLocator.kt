package com.example.speedcam

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * بيحدد مكان اللوحة تقريباً جوه القص السفلي للمركبة، من كثافة الحواف الرأسية (حروف وأرقام اللوحة بتعمل حواف كتير
 * في شريط أفقي ضيق). لو ملقاش مكان واضح بيرجّع null والقارئ يستخدم القص كله.
 */
object PlateLocator {

    private fun lum(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    private fun smooth(a: IntArray, r: Int): FloatArray {
        val n = a.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s = 0
            var c = 0
            for (k in max(0, i - r)..min(n - 1, i + r)) { s += a[k]; c++ }
            out[i] = s.toFloat() / c
        }
        return out
    }

    /** المستطيل بإحداثيات القص الأصلي، أو null. */
    fun find(crop: Bitmap): Rect? {
        val cw = crop.width
        val ch = crop.height
        if (cw < 60 || ch < 24) return null
        val sc = if (cw > 240) 240f / cw else 1f
        val w = max(16, (cw * sc).roundToInt())
        val h = max(8, (ch * sc).roundToInt())
        val small = if (sc == 1f) crop else Bitmap.createScaledBitmap(crop, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== crop) small.recycle()

        val g = IntArray(w * h) { lum(px[it]) }
        val e = IntArray(w * h)
        var sum = 0L
        for (y in 0 until h) for (x in 1 until w - 1) {
            val d = abs(g[y * w + x + 1] - g[y * w + x - 1])
            e[y * w + x] = d
            sum += d
        }
        val mean = sum.toDouble() / (w * h)
        val thr = max(30.0, mean * 2.2).toInt()

        // 1) الصفوف: نلاقي الشريط الأفقي اللي فيه أعلى كثافة حواف
        val rows = IntArray(h)
        for (y in 0 until h) {
            var c = 0
            for (x in 0 until w) if (e[y * w + x] > thr) c++
            rows[y] = c
        }
        val rs = smooth(rows, 2)
        var yMax = 0
        for (y in 1 until h) if (rs[y] > rs[yMax]) yMax = y
        if (rs[yMax] < 3f) return null
        val tr = rs[yMax] * 0.5f
        var y0 = yMax
        var y1 = yMax
        while (y0 > 0 && rs[y0 - 1] >= tr) y0--
        while (y1 < h - 1 && rs[y1 + 1] >= tr) y1++
        val bandH = y1 - y0 + 1
        if (bandH < max(6, (h * 0.12f).toInt()) || bandH > h * 0.85f) return null

        // 2) الأعمدة جوه الشريط ده: نحدد الامتداد الأفقي (بنمسح الفراغات بين الحروف بتنعيم واسع)
        val cols = IntArray(w)
        for (x in 0 until w) {
            var c = 0
            for (y in y0..y1) if (e[y * w + x] > thr) c++
            cols[x] = c
        }
        val cs = smooth(cols, max(3, bandH / 2))
        var xMax = 0
        for (x in 1 until w) if (cs[x] > cs[xMax]) xMax = x
        if (cs[xMax] < 1.5f) return null
        val tc = cs[xMax] * 0.3f
        var x0 = xMax
        var x1 = xMax
        while (x0 > 0 && cs[x0 - 1] >= tc) x0--
        while (x1 < w - 1 && cs[x1 + 1] >= tc) x1++
        val regW = x1 - x0 + 1
        if (regW < w * 0.25f) return null
        val aspect = regW.toFloat() / bandH
        if (aspect < 1.3f || aspect > 8f) return null

        // 3) هامش حوالين اللوحة، وترجيع الإحداثيات لحجم القص الأصلي
        val padX = (regW * 0.08f).roundToInt()
        val padY = (bandH * 0.25f).roundToInt()
        val l = (max(0, x0 - padX) / sc).roundToInt().coerceIn(0, cw - 2)
        val t = (max(0, y0 - padY) / sc).roundToInt().coerceIn(0, ch - 2)
        val r = (min(w - 1, x1 + padX) / sc).roundToInt().coerceIn(l + 1, cw - 1)
        val b = (min(h - 1, y1 + padY) / sc).roundToInt().coerceIn(t + 1, ch - 1)
        if (r - l < 40 || b - t < 12) return null
        return Rect(l, t, r, b)
    }
}

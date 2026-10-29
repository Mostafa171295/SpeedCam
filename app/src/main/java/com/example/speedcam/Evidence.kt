package com.example.speedcam

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * لقطات إثبات المتجاوزين: صورة الفريم كاملة مع مربع حوالين المركبة والخطين A/B، وشريط علوي فيه السرعة والحد والوقت.
 * بتتحفظ داخل مساحة التطبيق الخاصة (filesDir/evidence) ومش بتظهر في معرض الصور.
 */
object Evidence {
    private const val TAG = "Evidence"
    private const val MAX_FILES = 300   // الأقدم بيتمسح أوتوماتيك لما العدد يزيد

    fun dir(ctx: Context): File = File(ctx.filesDir, "evidence").apply { mkdirs() }

    /** يرسم اللقطة على نسخة من الفريم (الفريم الأصلي ما بيتغيّرش). الشريط بنصوص إنجليزي/أرقام عشان الاتجاه يفضل ثابت. */
    fun render(frame: Bitmap, box: NBox?, ev: SpeedEvent, s: AppSettings, timeMs: Long = System.currentTimeMillis()): Bitmap {
        val bmp = frame.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val stroke = max(2f, w / 320f)

        val linePaint = Paint().apply {
            color = 0xCCFFC107.toInt(); strokeWidth = max(1f, stroke / 2f); style = Paint.Style.STROKE; isAntiAlias = true
        }
        val sa = Geo.segment(s, 0)
        val sb = Geo.segment(s, 1)
        if (sa != null && sb != null) {
            c.drawLine(sa[0] * w, sa[1] * h, sa[2] * w, sa[3] * h, linePaint)
            c.drawLine(sb[0] * w, sb[1] * h, sb[2] * w, sb[3] * h, linePaint)
        } else {
            listOf(s.lineA, s.lineB).forEach { p ->
                if (s.axisHorizontal) c.drawLine(p * w, 0f, p * w, h, linePaint) else c.drawLine(0f, p * h, w, p * h, linePaint)
            }
        }
        if (box != null) {
            val boxPaint = Paint().apply { color = 0xFFE53935.toInt(); strokeWidth = stroke; style = Paint.Style.STROKE; isAntiAlias = true }
            c.drawRect(box.l * w, box.t * h, box.r * w, box.b * h, boxPaint)
        }

        val band = max(40f, h * 0.09f)
        c.drawRect(0f, 0f, w, band, Paint().apply { color = 0xB3000000.toInt() })
        val txt = Paint().apply { color = 0xFFFFFFFF.toInt(); textSize = band * 0.52f; isAntiAlias = true; isFakeBoldText = true }
        val err = if (ev.errKmh >= 0.5f) " +/- ${ev.errKmh.roundToInt()}" else ""
        val left = "${ev.speedKmh.roundToInt()}$err km/h  (limit ${s.speedLimit.roundToInt()})"
        val right = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))
        val y = band / 2f + txt.textSize * 0.35f
        txt.textAlign = Paint.Align.LEFT
        c.drawText(left, 12f, y, txt)
        txt.textAlign = Paint.Align.RIGHT
        c.drawText(right, w - 12f, y, txt)
        return bmp
    }

    /** يحفظ اللقطة ويرجّع مسار الملف، أو نص فاضي لو فشل. */
    fun save(ctx: Context, bmp: Bitmap, timeMs: Long, trackId: Int): String = try {
        val f = File(dir(ctx), "ev_${timeMs}_$trackId.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        prune(ctx)
        f.absolutePath
    } catch (e: Throwable) {
        Log.e(TAG, "save failed", e)
        ""
    }

    private fun prune(ctx: Context) {
        val files = dir(ctx).listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_FILES) files.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    fun delete(path: String) {
        if (path.isEmpty()) return
        try { File(path).delete() } catch (_: Throwable) { }
    }

    fun clearAll(ctx: Context) {
        try { dir(ctx).listFiles()?.forEach { it.delete() } } catch (_: Throwable) { }
    }

    fun share(ctx: Context, path: String) {
        val f = File(path)
        if (!f.exists()) return
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "مشاركة لقطة الإثبات").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

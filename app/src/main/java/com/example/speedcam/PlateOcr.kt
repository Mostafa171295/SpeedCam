package com.example.speedcam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** تحليل نص الـ OCR الخام وتحويله إلى (حروف + أرقام) للوحة المصرية. */
object PlateParser {
    data class Plate(val letters: String, val digits: String)

    // حروف اللوحات المصرية فقط (أي حرف غيرها يعتبر تشويش ويتم تجاهله)
    private const val PLATE_LETTERS = "أبجدرسصطعفقكلمنهوىي"

    // كلمات مكتوبة على اللوحة نفسها وليست جزءاً من الرقم
    private val STOP = setOf("مصر", "نقل", "ربع", "اجره", "أجره", "اجرة", "أجرة")

    private fun normLetter(c: Char): Char = when (c) {
        'ا', 'إ', 'آ', 'ٱ' -> 'أ'
        'ة' -> 'ه'
        else -> c
    }

    fun parse(raw: String): Plate? {
        val letters = StringBuilder()
        val digits = StringBuilder()
        for (tok in raw.split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            val l = StringBuilder()
            val d = StringBuilder()
            for (ch in tok) {
                when {
                    ch in '٠'..'٩' -> d.append('0' + (ch - '٠'))
                    ch in '۰'..'۹' -> d.append('0' + (ch - '۰'))
                    ch in '0'..'9' -> d.append(ch)
                    ch in 'ء'..'ي' && ch != 'ـ' -> l.append(normLetter(ch))
                }
            }
            // كلمة طويلة (محافظة/نوع الترخيص) أو كلمة معروفة: نتجاهل حروفها
            if (l.length > 3 || l.toString() in STOP) l.clear()
            for (c in l) if (c in PLATE_LETTERS) letters.append(c)
            digits.append(d)
        }
        if (letters.length !in 1..3) return null
        if (digits.length !in 1..4) return null
        return Plate(letters.toString(), digits.toString())
    }

    fun toArabicDigits(s: String): String =
        s.map { if (it in '0'..'9') '٠' + (it - '0') else it }.joinToString("")

    /** الشكل المعروض: الحروف ثم الأرقام بالأرقام العربية. */
    fun display(letters: String, digits: String): String =
        letters.toList().joinToString(" ") + "  " + toArabicDigits(digits)
}

/**
 * قراءة النص باستخدام Tesseract (اللغة العربية).
 * ملف اللغة ara.traineddata يتم نسخه من assets لو موجود، وإلا يتم تحميله مرة واحدة من الإنترنت.
 * لازم يُستخدم read() من خيط واحد في نفس الوقت.
 */
class PlateOcr(private val ctx: Context) {
    private var api: TessBaseAPI? = null

    private fun baseDir() = File(ctx.filesDir, "tesseract")
    private fun dataFile() = File(baseDir(), "tessdata/ara.traineddata")

    fun isDataReady(): Boolean = dataFile().let { it.exists() && it.length() > MIN_BYTES }

    /** لو حطيت ara.traineddata في app/src/main/assets ننسخه من هناك بدون إنترنت. */
    fun installFromAssets(): Boolean = try {
        File(baseDir(), "tessdata").mkdirs()
        ctx.assets.open(DATA_NAME).use { i -> dataFile().outputStream().use { o -> i.copyTo(o) } }
        isDataReady()
    } catch (e: Exception) {
        false
    }

    fun download(): Boolean = try {
        val dir = File(baseDir(), "tessdata").apply { mkdirs() }
        val tmp = File(dir, "$DATA_NAME.part")
        val conn = (URL(DATA_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        if (conn.responseCode != 200) {
            conn.disconnect()
            false
        } else {
            conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            conn.disconnect()
            if (tmp.length() < MIN_BYTES) {
                tmp.delete()
                false
            } else {
                dataFile().delete()
                tmp.renameTo(dataFile())
                isDataReady()
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "download failed", t)
        false
    }

    fun deleteData() {
        close()
        dataFile().delete()
    }

    private fun initApi(): TessBaseAPI? {
        val a = TessBaseAPI()
        val ok = try { a.init(baseDir().absolutePath, "ara") } catch (t: Throwable) { Log.e(TAG, "init failed", t); false }
        if (!ok) {
            try { a.recycle() } catch (_: Throwable) { }
            return null
        }
        a.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
        api = a
        return a
    }

    /** يرجّع (النص الخام، متوسط الثقة 0..100) أو null لو تعذّر تشغيل المحرك. */
    fun read(bmp: Bitmap): Pair<String, Int>? {
        val a = api ?: initApi() ?: return null
        a.setImage(bmp)
        val text = a.getUTF8Text() ?: ""
        val conf = a.meanConfidence()
        a.clear()
        return text to conf
    }

    fun close() {
        try { api?.recycle() } catch (_: Throwable) { }
        api = null
    }

    companion object {
        private const val TAG = "PlateOcr"
        private const val DATA_NAME = "ara.traineddata"
        private const val DATA_URL = "https://github.com/tesseract-ocr/tessdata_fast/raw/main/ara.traineddata"
        private const val MIN_BYTES = 300_000L
        private const val TARGET_W = 720f

        /** الأغلبية غامقة (بعد عتبة Otsu) = الخلفية غامقة والحروف الفاتحة هي الأقلية. */
        private fun lightTextOnDark(hist: IntArray, total: Int): Boolean {
            var sum = 0L
            for (i in 0..255) sum += i.toLong() * hist[i]
            var wB = 0L
            var sumB = 0L
            var best = -1.0
            var thr = 127
            for (t in 0..255) {
                wB += hist[t]
                if (wB == 0L) continue
                val wF = total - wB
                if (wF == 0L) break
                sumB += t.toLong() * hist[t]
                val mB = sumB.toDouble() / wB
                val mF = (sum - sumB).toDouble() / wF
                val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
                if (between > best) { best = between; thr = t }
            }
            var dark = 0L
            for (t in 0..thr) dark += hist[t]
            return dark.toDouble() / total > 0.58
        }

        /** تكبير/تصغير القص لعرض مناسب + تدرّج رمادي + تمديد التباين (+ توحيد اتجاه الألوان اختيارياً). */
        fun prepare(crop: Bitmap, targetW: Float = TARGET_W, normalizePolarity: Boolean = false): Bitmap {
            val scale = (targetW / crop.width.toFloat()).coerceIn(0.3f, 3f)
            val w = max(1, (crop.width * scale).roundToInt())
            val h = max(1, (crop.height * scale).roundToInt())
            val scaled = if (w == crop.width && h == crop.height) crop else Bitmap.createScaledBitmap(crop, w, h, true)
            val px = IntArray(w * h)
            scaled.getPixels(px, 0, w, 0, 0, w, h)
            val hist = IntArray(256)
            for (i in px.indices) {
                val c = px[i]
                val l = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
                px[i] = l
                hist[l]++
            }
            val cut = (px.size * 0.02).toInt()
            var acc = 0
            var lo = 0
            while (lo < 255 && acc + hist[lo] < cut) { acc += hist[lo]; lo++ }
            acc = 0
            var hi = 255
            while (hi > 0 && acc + hist[hi] < cut) { acc += hist[hi]; hi-- }
            if (hi - lo < 20) { lo = max(0, lo - 10); hi = min(255, hi + 10) }
            val range = max(1, hi - lo)
            val hist2 = IntArray(256)
            for (i in px.indices) {
                val v = ((px[i] - lo) * 255 / range).coerceIn(0, 255)
                px[i] = v
                hist2[v]++
            }
            // لو اللوحة غامقة وحروفها فاتحة، نقلب الألوان عشان الحروف تبقى غامقة على خلفية فاتحة (أسهل للقراءة)
            val invert = normalizePolarity && lightTextOnDark(hist2, px.size)
            for (i in px.indices) {
                val v = if (invert) 255 - px[i] else px[i]
                px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            if (scaled !== crop) scaled.recycle()
            return out
        }
    }
}

package com.example.speedcam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

const val PLATE_DATA_CHECKING = 0
const val PLATE_DATA_DOWNLOADING = 1
const val PLATE_DATA_READY = 2
const val PLATE_DATA_FAILED = 3

/** قراءة لوحة لمركبة. reads = عدد المرات اللي طلعت فيها نفس الأرقام. */
data class PlateHit(val letters: String, val digits: String, val conf: Int, val reads: Int = 1)

/** محاولة قراءة واحدة (للاختبار): الصورة اللي اتقرأت، بعد التجهيز، النص الخام، الثقة، والنتيجة لو اتقبلت. */
class PlateAttempt(val name: String, val used: Bitmap?, val prepared: Bitmap, val raw: String, val conf: Int, val hit: PlateHit?)

/** نتيجة اختبار قراءة صورة: كل المحاولات والزمن الكلي. */
class PlateDiag(val attempts: List<PlateAttempt>, val best: PlateHit?, val ms: Long, val note: String)

/**
 * يقرأ لوحات المركبات المتتبَّعة في الخلفية (على خيط مستقل عشان ما يبطّئش الكاميرا).
 * كل مركبة ليها قراءات متعددة بتتجمع، وبنختار الأكثر تكراراً.
 * بتقص الجزء السفلي من مربع المركبة (مكان اللوحة) وتقرأه.
 */
class PlateReader(context: Context) {
    private val appContext = context.applicationContext
    private val ocr = PlateOcr(appContext)
    private val ocrLock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val preparing = AtomicBoolean(false)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    val dataState = MutableStateFlow(PLATE_DATA_CHECKING)
    val ready: Boolean get() = dataState.value == PLATE_DATA_READY

    private class Rd(val digits: String, val letters: String, val conf: Int)

    /**
     * تجميع قراءات نفس المركبة: القراءات اللي بنفس الطول وبينها فرق حرف واحد (مسافة Levenshtein ≤ 1) بتتحسب لنفس اللوحة،
     * وبعدين تصويت حرف حرف بوزن ثقة كل قراءة. فقراءتين "1234" و"1284" بيدّوا "1234" أو "1284" حسب الأقوى بدل ما يتحسبوا مختلفين.
     */
    private class TrackVotes {
        private val reads = ArrayList<Rd>()

        @Synchronized fun add(h: PlateHit) {
            reads += Rd(h.digits, h.letters, h.conf)
            if (reads.size > 40) reads.removeAt(0)
        }

        private fun vote(items: List<Pair<String, Int>>): String {
            val byLen = HashMap<Int, Int>()
            for ((s, w) in items) byLen[s.length] = (byLen[s.length] ?: 0) + w
            val len = byLen.maxByOrNull { it.value }?.key ?: return ""
            val sb = StringBuilder()
            for (i in 0 until len) {
                val w = HashMap<Char, Int>()
                for ((s, wt) in items) if (s.length == len) w[s[i]] = (w[s[i]] ?: 0) + wt
                sb.append(w.maxByOrNull { it.value }?.key ?: ' ')
            }
            return sb.toString()
        }

        @Synchronized fun top(): PlateHit? {
            if (reads.isEmpty()) return null
            val clusters = ArrayList<MutableList<Rd>>()
            for (r in reads.sortedByDescending { it.conf }) {
                val c = clusters.firstOrNull { cl ->
                    cl[0].digits.length == r.digits.length && RobustStats.levenshtein(cl[0].digits, r.digits) <= 1
                }
                if (c != null) c.add(r) else clusters.add(mutableListOf(r))
            }
            val best = clusters.maxWithOrNull(compareBy({ it.size }, { it.sumOf { x -> x.conf } })) ?: return null
            val digits = vote(best.map { it.digits to it.conf.coerceAtLeast(1) })
            val letters = vote(best.map { it.letters to it.conf.coerceAtLeast(1) })
            return PlateHit(letters, digits, best.maxOf { it.conf }, best.size)
        }
    }

    private val tracks = ConcurrentHashMap<Int, TrackVotes>()

    /** يجهّز ملف اللغة العربية (نسخ من assets أو تحميل مرة واحدة). آمن لو اتنادى أكتر من مرة. */
    fun ensureData() {
        if (ocr.isDataReady()) { dataState.value = PLATE_DATA_READY; return }
        if (!preparing.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                dataState.value = PLATE_DATA_CHECKING
                if (ocr.installFromAssets()) {
                    dataState.value = PLATE_DATA_READY
                } else {
                    dataState.value = PLATE_DATA_DOWNLOADING
                    dataState.value = if (ocr.download()) PLATE_DATA_READY else PLATE_DATA_FAILED
                }
            } finally {
                preparing.set(false)
            }
        }
    }

    fun reset() = tracks.clear()

    private fun votes(id: Int) = tracks.getOrPut(id) { TrackVotes() }

    /** أفضل قراءة لمركبة (ID التتبع)، أو null لو مفيش قراءة موثوقة. */
    fun best(id: Int): PlateHit? = tracks[id]?.top()?.takeIf { acceptable(it) }

    private fun acceptable(h: PlateHit) = h.reads >= 2 || h.conf >= SINGLE_OK_CONF

    /** كل فريم: نختار أكبر مركبة لسه ملهاش قراءة مؤكدة ونبعتها للقراءة (لو الخيط فاضي). */
    fun offer(frame: Bitmap, tracked: List<Tracked>) {
        if (busy.get()) return
        val cand = tracked
            .filter { it.box.w * frame.width >= MIN_CAR_PX && (tracks[it.id]?.top()?.reads ?: 0) < 2 }
            .maxByOrNull { it.box.w * it.box.h } ?: return
        if (!busy.compareAndSet(false, true)) return
        val crop = cropLower(frame, cand.box)
        if (crop == null) { busy.set(false); return }
        if (tracks.size > 300) tracks.clear()
        val id = cand.id
        executor.execute {
            try {
                val hit = readCrop(crop)
                if (hit != null) votes(id).add(hit)
            } catch (t: Throwable) {
                Log.e(TAG, "plate read failed", t)
            } finally {
                crop.recycle()
                busy.set(false)
            }
        }
    }

    /** قراءة واحدة فورية (للوضع السريع). تتنادى من خيط خلفي. */
    fun readOnce(frame: Bitmap, box: NBox): PlateHit? {
        val crop = cropLower(frame, box) ?: return null
        return try {
            readCrop(crop)?.takeIf { acceptable(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "plate read failed", t)
            null
        } finally {
            crop.recycle()
        }
    }

    /**
     * قراءة قص المركبة السفلي: الأول نحاول نحدد اللوحة جوه القص ونقرأها لوحدها (بعد توحيد اتجاه الألوان)،
     * ولو القراءة مش واضحة نقرأ القص كله ونختار الأفضل. لو trace مش null بنسجّل كل محاولة (للاختبار).
     */
    private fun readCrop(crop: Bitmap, trace: MutableList<PlateAttempt>? = null): PlateHit? {
        var best: PlateHit? = null
        val rect = PlateLocator.find(crop)
        if (rect != null) {
            val sub = Bitmap.createBitmap(crop, rect.left, rect.top, rect.width(), rect.height())
            try {
                best = readPrepared(sub, "اللوحة المحدَّدة تلقائياً", true, trace)
            } finally {
                if (sub !== crop) sub.recycle()
            }
            if (dataState.value == PLATE_DATA_FAILED) return null
            val b0 = best
            if (b0 != null && b0.conf >= GOOD_CONF) return b0
        }
        val full = readPrepared(crop, "القص الكامل", false, trace)
        return better(best, full)
    }

    private fun better(a: PlateHit?, b: PlateHit?): PlateHit? =
        if (a == null) b else if (b == null) a else if (b.conf > a.conf) b else a

    private fun readPrepared(src: Bitmap, name: String, normalize: Boolean, trace: MutableList<PlateAttempt>?): PlateHit? {
        val prepared = PlateOcr.prepare(src, 1000f, normalize)
        var keep = false
        try {
            val res = synchronized(ocrLock) { ocr.read(prepared) }
            if (res == null) {
                // ملف اللغة تالف أو المحرك مش راضي يشتغل: نمسحه ونطلب تحميله من جديد
                synchronized(ocrLock) { ocr.deleteData() }
                dataState.value = PLATE_DATA_FAILED
                return null
            }
            val (text, conf) = res
            val parsed = if (conf >= MIN_CONF) PlateParser.parse(text) else null
            val hit = parsed?.let { PlateHit(it.letters, it.digits, conf) }
            if (trace != null) {
                trace += PlateAttempt(name, src.copy(Bitmap.Config.ARGB_8888, false), prepared, text.trim(), conf, hit)
                keep = true
            }
            return hit
        } finally {
            if (!keep) prepared.recycle()
        }
    }

    /**
     * اختبار قراءة من صورة ثابتة: لو box موجود يتعامل مع الصورة كصورة مركبة كاملة ويقص الجزء السفلي منها،
     * وإلا يعتبر الصورة كلها صورة لوحة قريبة. بيرجّع كل المحاولات عشان تشوف إيه اللي بيحصل في كل خطوة.
     */
    fun diagnose(img: Bitmap, box: NBox?): PlateDiag {
        val t0 = System.currentTimeMillis()
        if (!ready) return PlateDiag(emptyList(), null, 0, "ملف اللغة العربية لسه مش جاهز")
        val crop: Bitmap? = if (box != null) cropLower(img, box) else img.copy(Bitmap.Config.ARGB_8888, false)
        if (crop == null) return PlateDiag(emptyList(), null, 0, "المركبة صغيرة جداً في الصورة")
        val list = ArrayList<PlateAttempt>()
        val hit: PlateHit? = try {
            readCrop(crop, list)
        } finally {
            if (crop !== img) crop.recycle()
        }
        val note = when {
            list.isEmpty() -> "تعذّر تشغيل محرك القراءة"
            list.size == 1 && list[0].name.startsWith("اللوحة") -> "اتحددت اللوحة تلقائياً واتقرأت من أول محاولة."
            list.size == 1 -> "ما اتحددتش اللوحة تلقائياً، فاتقرأ القص كله."
            else -> "القراءة من اللوحة المحدَّدة مكانتش واضحة، فاتجرّب القص الكامل كمان."
        }
        return PlateDiag(list, hit, System.currentTimeMillis() - t0, note)
    }

    /** الجزء السفلي من مربع المركبة (مكان اللوحة). null لو صغير جداً. */
    private fun cropLower(frame: Bitmap, b: NBox): Bitmap? {
        val fw = frame.width
        val fh = frame.height
        val top = b.t + b.h * 0.45f
        val x = (b.l * fw).roundToInt().coerceIn(0, fw - 2)
        val y = (top * fh).roundToInt().coerceIn(0, fh - 2)
        val w = ((b.r - b.l) * fw).roundToInt().coerceAtMost(fw - x)
        val h = ((b.b - top) * fh).roundToInt().coerceAtMost(fh - y)
        if (w < MIN_CAR_PX || h < 12) return null
        return Bitmap.createBitmap(frame, x, y, w, h)
    }

    companion object {
        private const val TAG = "PlateReader"
        private const val MIN_CAR_PX = 90          // أصغر عرض للمركبة (بالبكسل) نحاول نقرأ لوحتها
        private const val MIN_CONF = 35            // أقل ثقة لقبول قراءة واحدة
        private const val SINGLE_OK_CONF = 55      // قراءة واحدة بتتقبل لو ثقتها عالية، وإلا لازم تتكرر مرتين
        private const val GOOD_CONF = 60           // لو قراءة اللوحة المحدَّدة ثقتها كده، مش محتاجين نقرأ القص كله
    }
}

package com.example.speedcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** يستقبل فريمات الكاميرا، يكشف ويصنّف المركبات، يتتبعها، يحسب السرعة، ويسجّل في قاعدة البيانات. */
class SpeedEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val dao: RecordDao,
    private val plateReader: PlateReader,
    private val settings: () -> AppSettings
) : ImageAnalysis.Analyzer {

    private val appContext = context.applicationContext
    private var detector: VehicleDetector? = null
    private var detectorGpu = true
    private val detLock = Any()
    private val tracker = SpeedTracker()

    // تصنيف النوع في الوضع السريع بيتم على خيط مستقل عشان ما يبطّئش الكاميرا
    private val classifier = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    val runningFlow = MutableStateFlow(true)

    /** القياس شغال؟ بيتغيّر من الزرار أو من حماية الحرارة/البطارية، والشاشات بتتابعه عن طريق runningFlow. */
    var running: Boolean
        get() = runningFlow.value
        set(v) { runningFlow.value = v }
    @Volatile private var resetRequested = false
    val overlay = MutableStateFlow(Overlay())

    @Volatile private var lastEvent = ""
    @Volatile private var sessionCount = 0
    @Volatile private var classifyMs = 0
    // مربعات الوضع السريع: (المربع، وقت الاختفاء) - بتظهر حوالي ثانية ونص بعد العبور
    @Volatile private var flash: List<Pair<OverlayBox, Long>> = emptyList()
    private var lastFrameMs = 0L
    private var prepAvg = 0f
    private var detAvg = 0f

    // ---- حالة الوضع السريع (بتتغيّر من خيط الكاميرا فقط) ----
    private class Strip {
        var bg: FloatArray? = null
        var active = false
        var quiet = 0
        var hits = 0
        var firstHitTs = 0L
        var activeSince = 0L
    }

    private var stripA = Strip()
    private var stripB = Strip()
    private val pendA = ArrayList<Long>()
    private val pendB = ArrayList<Long>()

    fun reset() { resetRequested = true }

    private fun resetFast() {
        stripA = Strip(); stripB = Strip()
        pendA.clear(); pendB.clear()
    }

    private fun obtainDetector(s: AppSettings): VehicleDetector = synchronized(detLock) {
        var d = detector
        if (d == null || detectorGpu != s.useGpu) {
            d?.close()
            d = VehicleDetector(appContext, s.useGpu)
            detector = d
            detectorGpu = s.useGpu
        }
        d
    }

    /** كشف مرة واحدة على صورة ثابتة (لاختبار قراءة اللوحات من صورة). */
    fun detectBoxes(bmp: Bitmap, s: AppSettings): List<Det> {
        val d = obtainDetector(s)
        return synchronized(detLock) { d.detect(bmp, s.minScore) }
    }

    private var fpsEma = 0f

    /** FPS مُنعَّم (متوسط متحرك) عشان الرقم ما يقفزش مع كل فريم. */
    private fun calcFps(nowMs: Long): Int {
        if (lastFrameMs != 0L) {
            val inst = 1000f / max(1L, nowMs - lastFrameMs)
            fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.92f + inst * 0.08f
        }
        lastFrameMs = nowMs
        return fpsEma.roundToInt()
    }

    /** تصفير/إيقاف: بيرجّع false لو الفريم ده مايتحللش. */
    private fun ready(): Boolean {
        if (resetRequested) { tracker.reset(); resetFast(); plateReader.reset(); resetRequested = false }
        if (!running) {
            lastFrameMs = 0L
            fpsEma = 0f
            overlay.value = overlay.value.copy(boxes = emptyList())
            return false
        }
        return true
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!ready()) return
            val nowMs = SystemClock.elapsedRealtime()
            val raw = image.toBitmap()
            val rot = image.imageInfo.rotationDegrees
            val frame = if (rot == 0) raw else Bitmap.createBitmap(
                raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true
            )
            process(frame, image.imageInfo.timestamp, nowMs)
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
        } finally {
            image.close()
        }
    }

    /** آخر فريم جاي من كاميرا الشبكة (للعرض في تبويب الكاميرا). */
    val previewFrame = MutableStateFlow<Bitmap?>(null)

    /** تحليل فريم جاهز (من كاميرا الشبكة). tsNs = توقيت وصول الفريم بالنانو ثانية. */
    fun analyzeFrame(frame: Bitmap, tsNs: Long) {
        try {
            previewFrame.value = frame
            if (!ready()) return
            process(frame, tsNs, SystemClock.elapsedRealtime())
        } catch (t: Throwable) {
            Log.e(TAG, "analyzeFrame failed", t)
        }
    }

    private fun process(frame: Bitmap, tsNs: Long, nowMs: Long) {
            val tPrep = SystemClock.elapsedRealtime()
            val s = settings()
            val aspect = frame.width.toFloat() / frame.height

            if (s.fastMode) {
                fastFrame(frame, tsNs, s)
                val cost = (SystemClock.elapsedRealtime() - nowMs).toFloat()
                prepAvg = if (prepAvg == 0f) cost else prepAvg * 0.8f + cost * 0.2f
                val fps = calcFps(nowMs)
                val nowT = SystemClock.elapsedRealtime()
                val flashBoxes = flash.filter { it.second > nowT }.map { it.first }
                overlay.value = Overlay(
                    flashBoxes, aspect, fps, false, lastEvent, sessionCount,
                    prepAvg.roundToInt(), classifyMs, detector?.gpuActive ?: false
                )
                return
            }

            val det = obtainDetector(s)
            var dets = synchronized(detLock) { det.detect(frame, s.minScore) }
            if (!det.usingCustom) dets = dets.map { SizeClassifier.refine(it, s) }

            val tDet = SystemClock.elapsedRealtime()
            prepAvg = if (prepAvg == 0f) (tPrep - nowMs).toFloat() else prepAvg * 0.8f + (tPrep - nowMs) * 0.2f
            detAvg = if (detAvg == 0f) (tDet - tPrep).toFloat() else detAvg * 0.8f + (tDet - tPrep) * 0.2f

            val res = tracker.update(dets, tsNs, aspect, s)
            val plates = s.readPlates && plateReader.ready
            res.stopped.forEach { st ->
                raise("مركبة واقفة", "${st.type.ar} واقفة في الصورة من أكتر من ${s.stopSec} ثانية", true)
            }
            res.events.forEach { ev ->
                // لقطة إثبات للمتجاوزين بس (الفريم الحالي لسه فيه المركبة لأن الحدث بيطلع بعد العبور بفريمين)
                val shot = if (s.saveEvidence && ev.speedKmh > s.speedLimit)
                    Evidence.render(frame, res.tracked.firstOrNull { it.id == ev.trackId }?.box, ev, s)
                else null
                handleEvent(ev, null, plates, evidence = shot)
            }
            if (plates) plateReader.offer(frame, res.tracked)
            tracker.rejectNote?.let { lastEvent = it; tracker.rejectNote = null }

            val boxes = res.tracked.map { t ->
                val sp = t.speedKmh
                if (s.countAll) {
                    val counted = t.a || t.b
                    OverlayBox(t.box, if (counted) "${t.type.ar} ✓" else t.type.ar, if (counted) 1 else 0, t.type)
                } else {
                    val label = if (sp == null) "${t.type.ar} ${if (t.a) "A✓" else "A-"} ${if (t.b) "B✓" else "B-"}" else "${t.type.ar} ${sp.roundToInt()}"
                    OverlayBox(t.box, label, if (sp == null) 0 else 1, t.type)
                }
            }
            val fps = calcFps(nowMs)
            overlay.value = Overlay(boxes, aspect, fps, !det.isReady, lastEvent, sessionCount, prepAvg.roundToInt(), detAvg.roundToInt(), det.gpuActive)
    }

    // =====================  الوضع السريع  =====================

    private fun fastFrame(frame: Bitmap, ts: Long, s: AppSettings) {
        val riseA = stripStep(stripA, stripProfile(frame, 0, s), ts, s.motionThr)
        val riseB = stripStep(stripB, stripProfile(frame, 1, s), ts, s.motionThr)

        val maxDt = s.distanceM / (MIN_KMH / 3.6) * 1e9
        val minDt = s.distanceM / (MAX_KMH / 3.6) * 1e9
        pendA.removeAll { ts - it > maxDt }
        pendB.removeAll { ts - it > maxDt }

        if (riseA != null) onRise(riseA, pendA, pendB, false, frame, 0, s, minDt, maxDt)
        if (riseB != null) onRise(riseB, pendB, pendA, true, frame, 1, s, minDt, maxDt)
    }

    /** own = قائمة الخط اللي حصلت عنده الحركة، other = قائمة الخط التاني. aFirst = الخط A كان الأول. */
    private fun onRise(
        t: Long, own: MutableList<Long>, other: MutableList<Long>, aFirst: Boolean,
        frame: Bitmap, lineIdx: Int, s: AppSettings, minDt: Double, maxDt: Double
    ) {
        val idx = other.indexOfFirst { val dt = (t - it).toDouble(); dt >= minDt && dt <= maxDt }
        if (idx < 0) { own += t; return }
        val t0 = other[idx]
        repeat(idx + 1) { other.removeAt(0) }
        val dt = (t - t0) / 1e9
        if (dt <= 0.0) return
        val v = s.calibrate(s.distanceM / dt * 3.6)
        if (v !in 2.0..300.0) return
        val dir = if (aFirst == (Geo.lineA(s) < Geo.lineB(s))) 1 else 2
        classifyAndRecord(frame, lineIdx, dir, v, s)
    }

    /** يعمل خطوة واحدة لشريط واحد، ويرجّع وقت بداية الحركة لو بدأت دلوقتي. */
    private fun stripStep(st: Strip, cur: IntArray, ts: Long, thr: Float): Long? {
        val bg = st.bg
        if (bg == null) {
            st.bg = FloatArray(cur.size) { cur[it].toFloat() }
            return null
        }
        val mc = cur.average().toFloat()
        val mb = bg.average().toFloat()
        var n = 0
        for (i in cur.indices) if (abs((cur[i] - mc) - (bg[i] - mb)) > PIX_THR) n++
        val changed = n.toFloat() / cur.size > thr

        var rise: Long? = null
        if (changed) {
            st.quiet = 0
            if (!st.active) {
                if (st.hits == 0) st.firstHitTs = ts
                st.hits++
                if (st.hits >= 2) { st.active = true; st.activeSince = ts; rise = st.firstHitTs }
            }
        } else {
            st.hits = 0
            if (st.active) {
                st.quiet++
                if (st.quiet >= 3) { st.active = false; st.quiet = 0 }
            }
        }
        // الخلفية بتتحدّث ببطء وقت ما الشريط فاضي بس
        if (!st.active && !changed) {
            for (i in bg.indices) bg[i] += (cur[i] - bg[i]) * 0.05f
        }
        // لو فضل الشريط "مشغول" أكتر من 10 ثواني نعتبره تغيّر إضاءة ونعيد ضبط الخلفية
        if (st.active && ts - st.activeSince > 10_000_000_000L) {
            for (i in bg.indices) bg[i] = cur[i].toFloat()
            st.active = false; st.hits = 0; st.quiet = 0
        }
        return rise
    }

    /** متوسط الإضاءة على طول شريط رفيع حوالين الخط، مقسّم لـ BINS قطعة. */
    private fun profile(frame: Bitmap, pos: Float, horizontal: Boolean): IntArray {
        val w = frame.width
        val h = frame.height
        val out = IntArray(BINS)
        if (horizontal) {
            val thick = max(2, (w * 0.012f).roundToInt())
            val x0 = ((pos * w).roundToInt() - thick / 2).coerceIn(0, w - thick)
            val px = IntArray(thick * h)
            frame.getPixels(px, 0, thick, x0, 0, thick, h)
            for (b in 0 until BINS) {
                val y0 = b * h / BINS
                val y1 = max(y0 + 1, (b + 1) * h / BINS)
                var sum = 0L
                var n = 0
                for (y in y0 until y1) for (x in 0 until thick) { sum += lum(px[y * thick + x]); n++ }
                out[b] = (sum / n).toInt()
            }
        } else {
            val thick = max(2, (h * 0.012f).roundToInt())
            val y0 = ((pos * h).roundToInt() - thick / 2).coerceIn(0, h - thick)
            val px = IntArray(w * thick)
            frame.getPixels(px, 0, w, 0, y0, w, thick)
            for (b in 0 until BINS) {
                val x0 = b * w / BINS
                val x1 = max(x0 + 1, (b + 1) * w / BINS)
                var sum = 0L
                var n = 0
                for (y in 0 until thick) for (x in x0 until x1) { sum += lum(px[y * w + x]); n++ }
                out[b] = (sum / n).toInt()
            }
        }
        return out
    }

    /** بروفايل الشريط عند الخط idx (0 = A، 1 = B): مقطع مائل لو تحويل المنظور شغال، وإلا شريط رأسي/أفقي عادي. */
    private fun stripProfile(frame: Bitmap, idx: Int, s: AppSettings): IntArray {
        val seg = Geo.segment(s, idx)
        return if (seg != null) profileSeg(frame, seg)
        else profile(frame, if (idx == 0) s.lineA else s.lineB, s.axisHorizontal)
    }

    /** متوسط الإضاءة على طول مقطع مائل (x1,y1,x2,y2 مُطبّعة) مقسّم لـ BINS نقطة، كل نقطة متوسط مربع صغير حواليها. */
    private fun profileSeg(frame: Bitmap, g: FloatArray): IntArray {
        val w = frame.width
        val h = frame.height
        val out = IntArray(BINS)
        val half = 2
        for (b in 0 until BINS) {
            val f = (b + 0.5f) / BINS
            val cx = ((g[0] + (g[2] - g[0]) * f) * w).roundToInt()
            val cy = ((g[1] + (g[3] - g[1]) * f) * h).roundToInt()
            var sum = 0
            var n = 0
            for (dy in -half..half) for (dx in -half..half) {
                val x = cx + dx
                val y = cy + dy
                if (x in 0 until w && y in 0 until h) { sum += lum(frame.getPixel(x, y)); n++ }
            }
            out[b] = if (n > 0) sum / n else 0
        }
        return out
    }

    private fun lum(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    private fun axisDistance(b: NBox, pos: Float, s: AppSettings): Float {
        val lo = if (s.axisHorizontal) b.l else b.t
        val hi = if (s.axisHorizontal) b.r else b.b
        return when {
            pos < lo -> lo - pos
            pos > hi -> pos - hi
            else -> 0f
        }
    }

    /** المسافة (مُطبّعة) بين مربع المركبة والخط idx. 0 = الخط داخل المربع. */
    private fun gapToLine(b: NBox, idx: Int, s: AppSettings): Float {
        val g = Geo.segment(s, idx) ?: return axisDistance(b, if (idx == 0) s.lineA else s.lineB, s)
        var best = Float.MAX_VALUE
        for (i in 0..20) {
            val f = i / 20f
            val px = g[0] + (g[2] - g[0]) * f
            val py = g[1] + (g[3] - g[1]) * f
            val dx = max(max(b.l - px, px - b.r), 0f)
            val dy = max(max(b.t - py, py - b.b), 0f)
            best = min(best, hypot(dx, dy))
        }
        return best
    }

    /** يشغّل الموديل مرة واحدة على صورة لحظة العبور عشان يحدد نوع المركبة، وبعدها يسجّل. */
    private fun classifyAndRecord(frame: Bitmap, lineIdx: Int, dir: Int, speed: Double, s: AppSettings) {
        scope.launch(classifier) {
            var type = VehicleType.CAR
            var plate: PlateHit? = null
            var shot: Bitmap? = null
            var laneNo = 0
            val t0 = SystemClock.elapsedRealtime()
            try {
                val det = obtainDetector(s)
                var dets = synchronized(detLock) { det.detect(frame, s.minScore) }
                if (!det.usingCustom) dets = dets.map { SizeClassifier.refine(it, s) }
                val best = dets.minWithOrNull(
                    compareBy<Det> { gapToLine(it.box, lineIdx, s) }.thenByDescending { it.score }
                )
                val near = best != null && gapToLine(best.box, lineIdx, s) <= 0.08f
                if (best != null && near) laneNo = Geo.lane(best.box, s)
                if (best != null && near) {
                    type = best.type
                    if (s.readPlates && plateReader.ready) plate = plateReader.readOnce(frame, best.box)
                    val nowT = SystemClock.elapsedRealtime()
                    val box = OverlayBox(best.box, type.ar, 1, type)
                    flash = flash.filter { it.second > nowT } + (box to nowT + 1500L)
                }
                // لقطة إثبات للمتجاوزين في الوضع السريع كمان (الفريم لسه فيه المركبة)
                if (s.saveEvidence && speed > s.speedLimit) {
                    shot = Evidence.render(frame, if (near) best?.box else null, SpeedEvent(0, type, speed, dir), s)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "classify failed", t)
            }
            classifyMs = (SystemClock.elapsedRealtime() - t0).toInt()
            handleEvent(SpeedEvent(0, type, speed, dir, lane = laneNo), plate, evidence = shot)
        }
    }

    /**
     * يسجّل القياس. لو waitPlate: نستنى لحد ~1.5 ثانية لحد ما اللوحة تتقرأ (المركبة لسه ظاهرة في الكاميرا)،
     * وبعدها نسجّل السرعة واللوحة مع بعض في نفس الصف.
     */
    // ---------- الحالات غير العادية ----------
    private val recentSpeeds = ArrayDeque<Pair<Long, Double>>()
    private val lastNotify = HashMap<String, Long>()

    /** يسجّل حالة غير عادية في قاعدة البيانات، ويبعت إشعار (مرة كل دقيقة لكل نوع عشان ما يزعجش). */
    private fun raise(kind: String, details: String, notify: Boolean, notifyEveryMs: Long = 60_000L) {
        val ts = System.currentTimeMillis()
        val doNotify = notify && synchronized(lastNotify) {
            val last = lastNotify[kind] ?: 0L
            if (ts - last >= notifyEveryMs) { lastNotify[kind] = ts; true } else false
        }
        scope.launch(Dispatchers.IO) {
            try { dao.insertAnomaly(AnomalyRecord(timestamp = ts, kind = kind, details = details)) } catch (e: Throwable) { Log.e(TAG, "anomaly save failed", e) }
            if (doNotify) RecordingNotifications.alert(appContext, kind, details, 43)
        }
    }

    /** عكس اتجاه + سرعة عالية جداً + ازدحام (وسيط سرعة آخر 6 مركبات أقل من الحد). */
    private fun checkAnomalies(ev: SpeedEvent) {
        val s = settings()
        if (s.allowedDir != 0 && ev.direction != s.allowedDir) {
            raise("عكس الاتجاه", "${ev.type.ar} ماشية عكس الاتجاه المسموح" + (if (ev.speedKmh >= 0) " (${ev.speedKmh.roundToInt()} كم/س)" else ""), true, 30_000L)
        }
        if (ev.speedKmh < 0) return
        if (s.alertSpeed > 0 && ev.speedKmh >= s.alertSpeed && (ev.conf == 0 || ev.conf >= 50)) {
            raise("سرعة عالية جداً", "${ev.type.ar}: ${ev.speedKmh.roundToInt()} كم/س (حد التنبيه ${s.alertSpeed})", true, 20_000L)
        }
        if (s.congestionKmh > 0) {
            val now = System.currentTimeMillis()
            val med: Double? = synchronized(recentSpeeds) {
                recentSpeeds.addLast(now to ev.speedKmh)
                while (recentSpeeds.isNotEmpty() && now - recentSpeeds.first().first > 5 * 60_000L) recentSpeeds.removeFirst()
                while (recentSpeeds.size > 6) recentSpeeds.removeFirst()
                if (recentSpeeds.size >= 6) RobustStats.median(recentSpeeds.map { it.second }) else null
            }
            if (med != null && med < s.congestionKmh) {
                raise("ازدحام", "وسيط سرعة آخر 6 مركبات ${med.roundToInt()} كم/س (أقل من ${s.congestionKmh})", true, 5 * 60_000L)
            }
        }
    }

    private fun handleEvent(ev: SpeedEvent, plate: PlateHit? = null, waitPlate: Boolean = false, evidence: Bitmap? = null) {
        sessionCount++
        checkAnomalies(ev)
        val baseText = if (ev.speedKmh < 0) "${ev.type.ar}: تم العد" else "${ev.type.ar}: ${ev.speedKmh.roundToInt()} كم/س"
        lastEvent = baseText
        val ts = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                var p = plate
                if (p == null && waitPlate) {
                    repeat(10) {
                        val b = plateReader.best(ev.trackId)
                        if (b != null && b.reads >= 2) return@repeat
                        delay(150)
                    }
                    p = plateReader.best(ev.trackId)
                }
                val evPath = if (evidence != null) Evidence.save(appContext, evidence, ts, ev.trackId) else ""
                evidence?.recycle()
                dao.insert(
                    SpeedRecord(
                        timestamp = ts, vehicleType = ev.type.name, speedKmh = ev.speedKmh, direction = ev.direction,
                        plateLetters = p?.letters ?: "", plateDigits = p?.digits ?: "", plateConf = p?.conf ?: 0,
                        speedConf = ev.conf, speedErr = ev.errKmh, evidencePath = evPath, lane = ev.lane
                    )
                )
                if (p != null) lastEvent = baseText + "  |  " + PlateParser.display(p.letters, p.digits)
            } catch (e: Throwable) {
                Log.e(TAG, "save failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "SpeedEngine"
        private const val BINS = 64
        private const val PIX_THR = 14f
        private const val MIN_KMH = 5.0
        private const val MAX_KMH = 250.0
    }
}

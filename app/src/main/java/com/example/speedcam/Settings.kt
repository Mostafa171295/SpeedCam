package com.example.speedcam

import android.content.Context
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class AppSettings(
    val axisHorizontal: Boolean = true,   // true: العربيات تتحرك يمين/شمال (الخطوط رأسية)
    val lineA: Float = 0.35f,
    val lineB: Float = 0.65f,
    val distanceM: Float = 10f,
    val minScore: Float = 0.5f,
    val sizeRefine: Boolean = true,       // تمييز الميكروباص/التوك توك بالحجم (للموديل الجاهز فقط)
    val tukMaxLen: Float = 3.4f,          // أقل من كده = توك توك
    val microMinLen: Float = 5.0f,        // من كده فما فوق (ملاكي) = ميكروباص
    val busMinLen: Float = 8.0f,          // أقل من كده (أتوبيس) = ميكروباص
    val highRes: Boolean = false,
    val useGpu: Boolean = true,           // تشغيل الكشف على الـ GPU (أسرع غالباً)
    val countAll: Boolean = false,        // true: عدّ أي مركبة تعدّي أي خط بدون التركيز على السرعة
    val fastMode: Boolean = false,        // true: الوضع السريع (حركة عند الخطين ~30 فريم، والموديل للنوع فقط)
    val motionThr: Float = 0.12f,         // حساسية الحركة في الوضع السريع (نسبة الشريط اللي لازم تتغيّر)
    val readPlates: Boolean = true,       // قراءة لوحات المركبات وتسجيلها مع السرعة
    val speedCalib: Float = 1f,           // معايرة السرعة: السرعة النهائية = speedCalib × المحسوبة + speedOffset
    val speedOffset: Float = 0f,          // الإزاحة الثابتة (كم/س) في معادلة المعايرة
    val calSamples: String = "",          // عينات المعايرة "الحقيقية:الخام;..." (للحساب بالانحدار الخطي)
    val saveEvidence: Boolean = true,     // حفظ لقطة إثبات لكل مركبة متجاوزة لحد السرعة
    val speedLimit: Float = 60f,          // حد السرعة (كم/س): أي مركبة أسرع منه تظهر باللون الأحمر في تبويب المركبات
    // ---- تحويل المنظور (Homography): النقط الأربعة على الطريق، والمسافة بين الخطين هي distanceM ----
    val homoOn: Boolean = false,
    val homoPts: String = "",             // "x1,y1;x2,y2;x3,y3;x4,y4" (مُطبّعة 0..1): 1-2 = الخط A ، 4-3 = الخط B
    // ---- التصوير في الضوء الضعيف ----
    val lockFps: Boolean = false,         // تثبيت معدل الفريمات (يمنع الكاميرا من إبطاء الفريمات بالليل)
    val targetFps: Int = 30,
    val manualExp: Boolean = false,       // تعريض يدوي (سرعة غالق + ISO)
    val shutterDenom: Int = 250,          // سرعة الغالق = 1 ÷ shutterDenom ثانية
    val isoManual: Int = 800,
    val evIndex: Int = 0,                 // تعويض التعريض (سطوع) بوحدات الجهاز
    // ---- الثبات والتشغيل الطويل ----
    val bgService: Boolean = false,       // تشغيل القياس في الخلفية (خدمة) عشان يكمّل والشاشة مقفولة
    val thermalGuard: Boolean = true,     // تنبيه/إيقاف تلقائي عند ارتفاع الحرارة أو ضعف البطارية
    val maxRecords: Int = 20000,          // أقصى عدد سجلات محفوظة (0 = بدون حد). الأقدم بيتأرشف ويتمسح
    // ---- دقة إضافية ----
    val lensK1: Float = 0f,               // تصحيح تشوه العدسة الشعاعي (0 = بدون تصحيح)
    val dropOutliers: Boolean = false,    // استبعاد القراءات الشاذة (3 × MAD) من الإحصائيات
    // ---- مؤشرات المرور والحالات غير العادية ----
    val laneCount: Int = 2,               // عدد الحارات في كل اتجاه (للحارات ومستوى الخدمة)
    val laneCapacity: Int = 1800,         // سعة الحارة (مركبة ركوب مكافئة/ساعة)
    val allowedDir: Int = 0,              // طريق باتجاه واحد: الاتجاه المسموح (0 = مش محدد، 1 أو 2)
    val stopSec: Int = 10,                // تنبيه مركبة واقفة بعد كام ثانية (0 = إيقاف)
    val congestionKmh: Int = 15,          // تنبيه ازدحام لو وسيط سرعة آخر المركبات أقل من كده (0 = إيقاف)
    val alertSpeed: Int = 0,              // تنبيه فوري لو سرعة المركبة وصلت لكده (0 = إيقاف)
    // ---- معايرة GPS ومصدر الصورة ----
    val siteLat: String = "",             // موقع الكاميرا (خط العرض) لمطابقة مرور مركبة المرجع
    val siteLon: String = "",
    val sourceMode: Int = 0,              // 0 = كاميرا الموبايل ، 1 = كاميرا على الشبكة (MJPEG عبر HTTP)
    val streamUrl: String = ""
)

class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings2", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            axisHorizontal = p.getBoolean("axisH", d.axisHorizontal),
            lineA = p.getFloat("lineA", d.lineA),
            lineB = p.getFloat("lineB", d.lineB),
            distanceM = p.getFloat("dist", d.distanceM),
            minScore = p.getFloat("minScore", d.minScore),
            sizeRefine = p.getBoolean("sizeRefine", d.sizeRefine),
            tukMaxLen = p.getFloat("tukMax", d.tukMaxLen),
            microMinLen = p.getFloat("microMin", d.microMinLen),
            busMinLen = p.getFloat("busMin", d.busMinLen),
            highRes = p.getBoolean("highRes", d.highRes),
            useGpu = p.getBoolean("useGpu", d.useGpu),
            countAll = p.getBoolean("countAll", d.countAll),
            fastMode = p.getBoolean("fastMode", d.fastMode),
            motionThr = p.getFloat("motionThr", d.motionThr),
            readPlates = p.getBoolean("readPlates", d.readPlates),
            speedCalib = p.getFloat("speedCalib", d.speedCalib),
            speedOffset = p.getFloat("speedOffset", d.speedOffset),
            calSamples = p.getString("calSamples", d.calSamples) ?: "",
            saveEvidence = p.getBoolean("saveEvidence", d.saveEvidence),
            speedLimit = p.getFloat("speedLimit", d.speedLimit),
            homoOn = p.getBoolean("homoOn", d.homoOn),
            homoPts = p.getString("homoPts", d.homoPts) ?: "",
            lockFps = p.getBoolean("lockFps", d.lockFps),
            targetFps = p.getInt("targetFps", d.targetFps),
            manualExp = p.getBoolean("manualExp", d.manualExp),
            shutterDenom = p.getInt("shutterDenom", d.shutterDenom),
            isoManual = p.getInt("isoManual", d.isoManual),
            evIndex = p.getInt("evIndex", d.evIndex),
            bgService = p.getBoolean("bgService", d.bgService),
            thermalGuard = p.getBoolean("thermalGuard", d.thermalGuard),
            maxRecords = p.getInt("maxRecords", d.maxRecords),
            lensK1 = p.getFloat("lensK1", d.lensK1),
            dropOutliers = p.getBoolean("dropOutliers", d.dropOutliers),
            laneCount = p.getInt("laneCount", d.laneCount),
            laneCapacity = p.getInt("laneCapacity", d.laneCapacity),
            allowedDir = p.getInt("allowedDir", d.allowedDir),
            stopSec = p.getInt("stopSec", d.stopSec),
            congestionKmh = p.getInt("congestionKmh", d.congestionKmh),
            alertSpeed = p.getInt("alertSpeed", d.alertSpeed),
            siteLat = p.getString("siteLat", d.siteLat) ?: "",
            siteLon = p.getString("siteLon", d.siteLon) ?: "",
            sourceMode = p.getInt("sourceMode", d.sourceMode),
            streamUrl = p.getString("streamUrl", d.streamUrl) ?: ""
        )
    }

    fun save(s: AppSettings) {
        p.edit()
            .putBoolean("axisH", s.axisHorizontal)
            .putFloat("lineA", s.lineA).putFloat("lineB", s.lineB)
            .putFloat("dist", s.distanceM).putFloat("minScore", s.minScore)
            .putBoolean("sizeRefine", s.sizeRefine)
            .putFloat("tukMax", s.tukMaxLen).putFloat("microMin", s.microMinLen).putFloat("busMin", s.busMinLen)
            .putBoolean("highRes", s.highRes)
            .putBoolean("useGpu", s.useGpu)
            .putBoolean("countAll", s.countAll)
            .putBoolean("fastMode", s.fastMode)
            .putFloat("motionThr", s.motionThr)
            .putBoolean("readPlates", s.readPlates)
            .putFloat("speedCalib", s.speedCalib)
            .putFloat("speedOffset", s.speedOffset)
            .putString("calSamples", s.calSamples)
            .putBoolean("saveEvidence", s.saveEvidence)
            .putFloat("speedLimit", s.speedLimit)
            .putBoolean("homoOn", s.homoOn)
            .putString("homoPts", s.homoPts)
            .putBoolean("lockFps", s.lockFps)
            .putInt("targetFps", s.targetFps)
            .putBoolean("manualExp", s.manualExp)
            .putInt("shutterDenom", s.shutterDenom)
            .putInt("isoManual", s.isoManual)
            .putInt("evIndex", s.evIndex)
            .putBoolean("bgService", s.bgService)
            .putBoolean("thermalGuard", s.thermalGuard)
            .putInt("maxRecords", s.maxRecords)
            .putFloat("lensK1", s.lensK1)
            .putBoolean("dropOutliers", s.dropOutliers)
            .putInt("laneCount", s.laneCount)
            .putInt("laneCapacity", s.laneCapacity)
            .putInt("allowedDir", s.allowedDir)
            .putInt("stopSec", s.stopSec)
            .putInt("congestionKmh", s.congestionKmh)
            .putInt("alertSpeed", s.alertSpeed)
            .putString("siteLat", s.siteLat)
            .putString("siteLon", s.siteLon)
            .putInt("sourceMode", s.sourceMode)
            .putString("streamUrl", s.streamUrl)
            .apply()
    }
}

/** تطبيق معايرة السرعة على القيمة الخام المحسوبة من الأوقات (لا تقل عن صفر). */
fun AppSettings.calibrate(rawKmh: Double): Double = max(0.0, rawKmh * speedCalib + speedOffset)

/**
 * معايرة بمعاملين: السرعة الحقيقية = a × الخام + b.
 * كل عينة = (سرعة حقيقية من العداد/GPS، السرعة الخام اللي حسبها التطبيق قبل أي تصحيح).
 */
object Calibration {
    data class Sample(val real: Float, val raw: Float)

    fun parse(s: String): List<Sample> = s.split(';').mapNotNull { p ->
        val q = p.split(':')
        val r = q.getOrNull(0)?.toFloatOrNull()
        val w = q.getOrNull(1)?.toFloatOrNull()
        if (q.size == 2 && r != null && w != null && r > 0f && w > 0f) Sample(r, w) else null
    }

    fun encode(l: List<Sample>): String = l.joinToString(";") { "${it.real}:${it.raw}" }

    /**
     * عينة أو اتنين → معامل نسبة بس (b = 0). تلات عينات فأكتر بسرعات متباعدة (انحراف معياري ≥ 15% من المتوسط)
     * → انحدار خطي بمعاملين. لو النتيجة غير منطقية (a خارج 0.5..2 أو |b| > 15) نرجع للنسبة.
     */
    fun fit(l: List<Sample>): Pair<Float, Float> = fitRaw(inliers(l))

    /** من 6 عينات فأكتر: نستبعد أي عينة بعيدة عن الخط الأولي بأكتر من 3 انحرافات (مقاوم للشواذ، بحد أدنى 0.5 كم/س). */
    fun inliers(l: List<Sample>): List<Sample> {
        if (l.size < 6) return l
        val (a0, b0) = fitRaw(l)
        val res = l.map { abs(it.real - (a0 * it.raw + b0)).toDouble() }
        val sig = max(0.5, 1.4826 * RobustStats.median(res))
        val keep = l.filterIndexed { i, _ -> res[i] <= 3.0 * sig }
        return if (keep.size >= 4) keep else l
    }

    class Stats(val n: Int, val rmse: Float, val half95: Float, val dropped: Int)

    /** إحصاء المعايرة: RMSE للبواقي، ونص عرض فترة الثقة 95% للقراءة المصحّحة، وعدد العينات المستبعدة. null لو أقل من 3 عينات. */
    fun stats(all: List<Sample>): Stats? {
        val l = inliers(all)
        val n = l.size
        if (n < 3) return null
        val (a, b) = fitRaw(l)
        val params = if (b != 0f) 2 else 1
        val dof = n - params
        if (dof < 1) return null
        var ss = 0.0
        for (x in l) {
            val r = x.real - (a * x.raw + b)
            ss += r * r
        }
        val s = sqrt(ss / dof)
        val half = RobustStats.t95(dof) * s * sqrt(1.0 + 1.0 / n)
        return Stats(n, s.toFloat(), half.toFloat(), all.size - n)
    }

    private fun fitRaw(l: List<Sample>): Pair<Float, Float> {
        if (l.isEmpty()) return 1f to 0f
        val n = l.size
        val ratio = (l.sumOf { (it.real * it.raw).toDouble() } / l.sumOf { (it.raw * it.raw).toDouble() })
            .toFloat().coerceIn(0.5f, 2f)
        if (n < 3) return ratio to 0f
        val mx = l.sumOf { it.raw.toDouble() } / n
        val my = l.sumOf { it.real.toDouble() } / n
        val sxx = l.sumOf { (it.raw - mx) * (it.raw - mx) }
        if (sxx <= 0.0 || sqrt(sxx / n) < 0.15 * mx) return ratio to 0f
        val a = l.sumOf { (it.raw - mx) * (it.real - my) } / sxx
        val b = my - a * mx
        if (a < 0.5 || a > 2.0 || abs(b) > 15.0) return ratio to 0f
        return a.toFloat() to b.toFloat()
    }
}

package com.example.speedcam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * يحمّل vehicles_custom.tflite (موديلك المدرّب: ميكروباص/توك توك/...) لو موجود،
 * وإلا يستخدم efficientdet_lite0.tflite الجاهز (COCO).
 */
class VehicleDetector(context: Context, private val useGpu: Boolean = true) {

    private val appContext = context.applicationContext
    private var modelName: String? = null

    /** true لو الكشف شغال فعلاً على الـ GPU. */
    var gpuActive = false
        private set

    /** true لو الموديل المخصص هو المستخدم (فلا حاجة للتمييز بالحجم). */
    var usingCustom = false
        private set

    private var detector: ObjectDetector? = create(appContext)

    val isReady: Boolean get() = detector != null

    private fun create(context: Context): ObjectDetector? {
        val files = try { context.assets.list("")?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        val model = when {
            CUSTOM_MODEL in files -> CUSTOM_MODEL
            STOCK_MODEL in files -> STOCK_MODEL
            else -> { Log.e(TAG, "لا يوجد ملف موديل في assets"); return null }
        }
        usingCustom = model == CUSTOM_MODEL
        modelName = model
        if (useGpu) {
            try {
                val d = build(context, model, true)
                gpuActive = true
                return d
            } catch (e: Throwable) {
                Log.w(TAG, "الـ GPU غير متاح، الرجوع للمعالج", e)
            }
        }
        return try {
            gpuActive = false
            build(context, model, false)
        } catch (e: Throwable) {
            Log.e(TAG, "تعذر تحميل الموديل $model", e)
            null
        }
    }

    private fun build(context: Context, model: String, gpu: Boolean): ObjectDetector {
        val base = BaseOptions.builder().setModelAssetPath(model)
        if (gpu) base.setDelegate(Delegate.GPU)
        val opts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base.build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(20)
            .setScoreThreshold(0.3f)
            .build()
        return ObjectDetector.createFromOptions(context, opts)
    }

    fun close() {
        try { detector?.close() } catch (_: Throwable) { }
        detector = null
    }

    /**
     * roi: منطقة الكشف (إحداثيات 0..1 من الصورة الكاملة) أو null للصورة كلها. لو موجودة بنقص الصورة،
     * ونرجّع الصناديق بإحداثيات الصورة الكاملة، ونتجاهل أي صندوق ملامس لحافة القص الصناعية
     * (عربية مقطوعة بنصها مش هتدّي مكان دقيق، وهتظهر كاملة لما تدخل المنطقة).
     */
    fun detect(bitmap: Bitmap, minScore: Float, roi: NBox? = null): List<Det> {
        val d = detector ?: return emptyList()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val out = ArrayList<Det>()

        var x0 = 0; var y0 = 0; var cw = bitmap.width; var ch = bitmap.height
        if (roi != null) {
            x0 = kotlin.math.floor(roi.l * w).toInt().coerceIn(0, bitmap.width - MIN_CROP_PX)
            y0 = kotlin.math.floor(roi.t * h).toInt().coerceIn(0, bitmap.height - MIN_CROP_PX)
            val x1 = kotlin.math.ceil(roi.r * w).toInt().coerceIn(x0 + MIN_CROP_PX, bitmap.width)
            val y1 = kotlin.math.ceil(roi.b * h).toInt().coerceIn(y0 + MIN_CROP_PX, bitmap.height)
            cw = x1 - x0; ch = y1 - y0
        }
        val cropped = cw < bitmap.width || ch < bitmap.height
        val src = if (cropped) Bitmap.createBitmap(bitmap, x0, y0, cw, ch) else bitmap
        try {
            return detectOn(d, src, minScore, out, x0, y0, cw, ch, w, h, cropped, bitmap)
        } finally {
            if (cropped) src.recycle()
        }
    }

    private fun detectOn(
        d: ObjectDetector, src: Bitmap, minScore: Float, out: ArrayList<Det>,
        x0: Int, y0: Int, cw: Int, ch: Int, w: Float, h: Float, cropped: Boolean, full: Bitmap
    ): List<Det> {
        val result = try {
            d.detect(BitmapImageBuilder(src).build())
        } catch (e: Throwable) {
            val model = modelName
            if (gpuActive && model != null) {
                Log.w(TAG, "فشل الكشف على الـ GPU، التحويل للمعالج", e)
                try { d.close() } catch (_: Throwable) { }
                gpuActive = false
                detector = try { build(appContext, model, false) } catch (t: Throwable) { null }
                return emptyList()
            }
            throw e
        }
        for (det in result.detections()) {
            val c = det.categories().maxByOrNull { it.score() } ?: continue
            if (c.score() < minScore) continue
            val type = VehicleType.fromLabel(c.categoryName() ?: "") ?: continue
            val b = det.boundingBox()
            if (cropped) {
                // حافة القص الصناعية (مش حافة الصورة الأصلية): الصندوق الملامس لها مقطوع
                val e = EDGE_PX
                if ((x0 > 0 && b.left < e) || (x0 + cw < full.width && b.right > cw - e) ||
                    (y0 > 0 && b.top < e) || (y0 + ch < full.height && b.bottom > ch - e)
                ) continue
            }
            out += Det(
                type,
                NBox(
                    ((b.left + x0) / w).coerceIn(0f, 1f), ((b.top + y0) / h).coerceIn(0f, 1f),
                    ((b.right + x0) / w).coerceIn(0f, 1f), ((b.bottom + y0) / h).coerceIn(0f, 1f)
                ),
                c.score()
            )
        }
        return out
    }

    companion object {
        const val CUSTOM_MODEL = "vehicles_custom.tflite"
        const val STOCK_MODEL = "efficientdet_lite0.tflite"
        private const val TAG = "VehicleDetector"
        private const val MIN_CROP_PX = 32     // أصغر قص ممكن بالبكسل
        private const val EDGE_PX = 2f         // صندوق أقرب من كده لحافة القص يتهمل
    }
}

/**
 * تمييز تقريبي بالحجم الحقيقي (للموديل الجاهز فقط):
 * طول المركبة بالمتر ≈ عرض المربع × (المسافة المعايَرة ÷ الفرق بين الخطين).
 * يعمل فقط لو الحركة أفقية والمركبة كاملة في الصورة وفي نفس مستوى علامات المعايرة تقريباً.
 */
object SizeClassifier {
    fun refine(d: Det, s: AppSettings): Det {
        if (!s.sizeRefine || !s.axisHorizontal) return d
        val gap = kotlin.math.abs(s.lineB - s.lineA)
        if (gap < 0.03f) return d
        if (d.box.l < 0.01f || d.box.r > 0.99f) return d
        val len = d.box.w * s.distanceM / gap
        val t = when (d.type) {
            VehicleType.MOTORCYCLE -> if (len > 2.5f) VehicleType.TUKTUK else VehicleType.MOTORCYCLE
            VehicleType.CAR -> when {
                len < s.tukMaxLen -> VehicleType.TUKTUK
                len >= s.microMinLen -> VehicleType.MICROBUS
                else -> VehicleType.CAR
            }
            VehicleType.BUS -> if (len < s.busMinLen) VehicleType.MICROBUS else VehicleType.BUS
            else -> d.type
        }
        return d.copy(type = t)
    }
}

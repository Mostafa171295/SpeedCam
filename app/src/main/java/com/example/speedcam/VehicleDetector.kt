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

    fun detect(bitmap: Bitmap, minScore: Float): List<Det> {
        val d = detector ?: return emptyList()
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val out = ArrayList<Det>()
        val result = try {
            d.detect(BitmapImageBuilder(bitmap).build())
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
            out += Det(
                type,
                NBox(
                    (b.left / w).coerceIn(0f, 1f), (b.top / h).coerceIn(0f, 1f),
                    (b.right / w).coerceIn(0f, 1f), (b.bottom / h).coerceIn(0f, 1f)
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

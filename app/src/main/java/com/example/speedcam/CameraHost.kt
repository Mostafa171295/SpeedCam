package com.example.speedcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/** إمكانيات كاميرا الجهاز (بتتقرأ بعد أول تشغيل) عشان شاشة الإعدادات تعرض الحدود الصحيحة. */
data class CameraCaps(
    val ready: Boolean = false,
    val manualSupported: Boolean = false,
    val isoMin: Int = 100,
    val isoMax: Int = 3200,
    val expMinNs: Long = 0L,
    val expMaxNs: Long = 0L,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    val fpsRanges: String = ""
)

/**
 * مسؤول واحد عن الكاميرا في التطبيق كله:
 *  - الكاميرا شغالة طول ما الشاشة ظاهرة (أي تبويب) أو طول ما الخدمة الخلفية شغالة.
 *  - المعاينة (Preview) بتتوصّل بالشاشة لما تظهر وتتفصل لما تختفي، من غير ما الكاميرا تتقفل.
 *  - تثبيت الـ FPS والتعريض اليدوي والتعويض بيتطبقوا مباشرة بدون إعادة تشغيل الكاميرا.
 * كل الدوال لازم تتنادى من الخيط الرئيسي.
 */
class CameraHost(
    context: Context,
    private val engine: SpeedEngine,
    private val settings: () -> AppSettings
) {
    private val app = context.applicationContext
    private val owner = HostLifecycleOwner()
    private val executor = Executors.newSingleThreadExecutor()

    private var provider: ProcessCameraProvider? = null
    private var providerRequested = false
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var boundHighRes: Boolean? = null

    private var activityVisible = false
    private var serviceUp = false
    private var previewView: PreviewView? = null
    private var lastControlKey = ""
    private var fpsRangeList: List<Range<Int>> = emptyList()

    val caps = MutableStateFlow(CameraCaps())

    // ---- مصدر كاميرا الشبكة ----
    val streamStatus = MutableStateFlow("")
    private var stream: StreamSource? = null
    private var streamKey = ""
    private var srcKey = "0|"

    private fun startStream(url: String) {
        if (stream != null && streamKey == url) return
        stopStream()
        engine.reset()
        streamKey = url
        stream = StreamSource(url, engine) { streamStatus.value = it }.also { it.start() }
    }

    private fun stopStream() {
        stream?.stop()
        stream = null
        streamKey = ""
        if (engine.previewFrame.value != null) engine.previewFrame.value = null
        if (streamStatus.value.isNotEmpty()) streamStatus.value = ""
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun setActivityVisible(v: Boolean) {
        activityVisible = v
        refresh()
    }

    fun setService(v: Boolean) {
        serviceUp = v
        refresh()
    }

    fun attachPreview(v: PreviewView) {
        previewView = v
        preview?.setSurfaceProvider(v.surfaceProvider)
    }

    fun detachPreview(v: PreviewView) {
        if (previewView === v) {
            previewView = null
            preview?.setSurfaceProvider(null)
        }
    }

    /** بيتنادى لما الإعدادات تتغيّر: لو الدقة اتغيّرت نعيد الربط، وإلا نطبّق إعدادات التصوير بس. */
    fun onSettings(s: AppSettings) {
        val k = "${s.sourceMode}|${s.streamUrl}"
        if (k != srcKey) {
            srcKey = k
            refresh()
            return
        }
        if (s.sourceMode == 1) return
        if (camera != null && boundHighRes != null && boundHighRes != s.highRes) {
            refresh()
            return
        }
        applyControls(s)
    }

    /** يقرر هل الكاميرا تشتغل ولا تقفل، حسب ظهور الشاشة/الخدمة والإذن. */
    fun refresh() {
        val s0 = settings()
        val wantRun = activityVisible || serviceUp
        if (s0.sourceMode == 1) {
            // كاميرا الشبكة: نقفل كاميرا الموبايل ونشغّل البث لو فيه رابط
            unbind()
            if (wantRun && s0.streamUrl.isNotBlank()) startStream(s0.streamUrl) else stopStream()
            return
        }
        stopStream()
        val want = wantRun && hasPermission()
        if (!want) {
            unbind()
            return
        }
        val p = provider
        if (p == null) {
            requestProvider()
            return
        }
        val s = settings()
        if (camera != null && boundHighRes == s.highRes) return
        bind(p, s)
    }

    private fun requestProvider() {
        if (providerRequested) return
        providerRequested = true
        val f = ProcessCameraProvider.getInstance(app)
        f.addListener({
            try {
                provider = f.get()
            } catch (t: Throwable) {
                Log.e(TAG, "camera provider failed", t)
                providerRequested = false
                return@addListener
            }
            refresh()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun bind(p: ProcessCameraProvider, s: AppSettings) {
        try {
            owner.start()
            p.unbindAll()
            val ratio = AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            val pr = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
            previewView?.let { pr.setSurfaceProvider(it.surfaceProvider) }
            val size = if (s.highRes) Size(1280, 720) else Size(640, 360)
            val an = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { it.setAnalyzer(executor, engine) }
            camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pr, an)
            preview = pr
            boundHighRes = s.highRes
            lastControlKey = ""
            readCaps()
            applyControls(s)
        } catch (e: Exception) {
            Log.e(TAG, "bind failed", e)
            camera = null
            preview = null
            boundHighRes = null
        }
    }

    private fun unbind() {
        try { provider?.unbindAll() } catch (_: Exception) { }
        camera = null
        preview = null
        boundHighRes = null
        lastControlKey = ""
    }

    // =====================  إمكانيات الجهاز  =====================

    @SuppressLint("UnsafeOptInUsageError")
    private fun readCaps() {
        val cam = camera ?: return
        try {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val iso = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val capList = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val ranges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            fpsRangeList = ranges?.toList() ?: emptyList()
            val manual = capList?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true &&
                iso != null && exp != null
            val ev = cam.cameraInfo.exposureState
            caps.value = CameraCaps(
                ready = true,
                manualSupported = manual,
                isoMin = iso?.lower ?: 100,
                isoMax = iso?.upper ?: 3200,
                expMinNs = exp?.lower ?: 0L,
                expMaxNs = exp?.upper ?: 0L,
                evMin = ev.exposureCompensationRange.lower,
                evMax = ev.exposureCompensationRange.upper,
                evStep = ev.exposureCompensationStep.toFloat(),
                fpsRanges = fpsRangeList.joinToString("  ") { "${it.lower}-${it.upper}" }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "readCaps failed", t)
            val ev = try { cam.cameraInfo.exposureState } catch (_: Throwable) { null }
            caps.value = CameraCaps(
                ready = true,
                evMin = ev?.exposureCompensationRange?.lower ?: 0,
                evMax = ev?.exposureCompensationRange?.upper ?: 0,
                evStep = ev?.exposureCompensationStep?.toFloat() ?: 0f
            )
        }
    }

    // =====================  تثبيت الـ FPS والتعريض  =====================

    /** يختار أنسب نطاق FPS من اللي الجهاز يدعمه: الثابت (مثلاً 30-30) لو موجود، وإلا أقرب نطاق. */
    private fun pickFpsRange(target: Int): Range<Int>? {
        val list = fpsRangeList
        if (list.isEmpty()) return null
        list.firstOrNull { it.lower == target && it.upper == target }?.let { return it }
        val exact = list.filter { it.upper == target }
        if (exact.isNotEmpty()) return exact.maxByOrNull { it.lower }
        val above = list.filter { it.upper > target }
        if (above.isNotEmpty()) return above.sortedWith(compareBy<Range<Int>> { it.upper }.thenByDescending { it.lower }).first()
        return list.maxByOrNull { it.upper }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun applyControls(s: AppSettings) {
        val cam = camera ?: return
        val c = caps.value
        val manual = s.manualExp && c.manualSupported
        val ev = if (c.evMax > c.evMin) s.evIndex.coerceIn(c.evMin, c.evMax) else 0
        val key = "${s.lockFps}|${s.targetFps}|$manual|${s.shutterDenom}|${s.isoManual}|$ev"
        if (key == lastControlKey) return
        lastControlKey = key
        try {
            if (c.evMax > c.evMin) cam.cameraControl.setExposureCompensationIndex(ev)

            val b = CaptureRequestOptions.Builder()
            if (manual) {
                val frameNs = (1_000_000_000.0 / s.targetFps.coerceAtLeast(5)).toLong()
                var expNs = (1_000_000_000.0 / s.shutterDenom.coerceAtLeast(1)).toLong()
                if (c.expMaxNs > 0L) expNs = expNs.coerceIn(c.expMinNs, c.expMaxNs)
                expNs = expNs.coerceAtMost(frameNs)
                val iso = s.isoManual.coerceIn(c.isoMin, c.isoMax)
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
            } else if (s.lockFps) {
                pickFpsRange(s.targetFps)?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
            // لو مفيش أي اختيار، الـ builder فاضي: بيرجّع الكاميرا للوضع التلقائي
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (t: Throwable) {
            Log.w(TAG, "applyControls failed", t)
        }
    }

    private companion object {
        const val TAG = "CameraHost"
    }
}

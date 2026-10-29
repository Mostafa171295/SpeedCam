package com.example.speedcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image as UiImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "VideoTest"
private const val MAX_ROWS_SHOWN = 40

/** فك ضغط فيديو فريم فريم مع التوقيت الحقيقي لكل فريم (presentation time)، لتجربة الدقة بدون النزول للطريق. */
object VideoFrames {

    /**
     * يقرأ الفيديو بالترتيب ويستدعي onFrame لكل فريم مختار (بحد أقصى analysisFps فريم/ث، لمحاكاة سرعة جهازك).
     * الفريم بيرجع مُصغّراً (أطول ضلع = targetLong) ومعدول الدوران، والوقت بالنانو ثانية.
     */
    fun readFrames(
        ctx: Context, uri: Uri, analysisFps: Int, targetLong: Int,
        startUs: Long = 0L, maxFrames: Int = Int.MAX_VALUE,
        isActive: () -> Boolean, onProgress: (Float) -> Unit, onFrame: (Bitmap, Long) -> Unit
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(ctx, uri, null)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) throw IllegalStateException("الملف ده مفيهوش فيديو")
            extractor.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("نوع الفيديو غير معروف")
            val rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            val minGapUs = 1_000_000L / max(1, analysisFps)
            var lastUs = Long.MIN_VALUE
            var emitted = 0
            var inputDone = false
            var outputDone = false

            while (!outputDone && isActive()) {
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)
                        val n = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val ptsUs = info.presentationTimeUs
                    val wanted = info.size > 0 && ptsUs >= startUs && (lastUs == Long.MIN_VALUE || ptsUs - lastUs >= minGapUs - 500)
                    if (wanted) {
                        val img = dec.getOutputImage(outIdx)
                        if (img != null) {
                            val raw = try { yuvToBitmap(img) } finally { img.close() }
                            if (raw != null) {
                                onFrame(fit(raw, targetLong, rotation), ptsUs * 1000L)
                                lastUs = ptsUs
                                emitted++
                                if (emitted >= maxFrames) outputDone = true
                            }
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (eos) outputDone = true
                    if (durationUs > 0) onProgress((ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
                }
            }
            onProgress(1f)
        } finally {
            try { codec?.stop() } catch (_: Throwable) { }
            try { codec?.release() } catch (_: Throwable) { }
            try { extractor.release() } catch (_: Throwable) { }
        }
    }

    /** أول فريم واضح (بعد حوالي 0.3 ثانية) لمعاينة الخطين فوق الفيديو. */
    fun previewFrame(ctx: Context, uri: Uri): Bitmap? {
        var out: Bitmap? = null
        try {
            readFrames(ctx, uri, 1, 960, startUs = 300_000L, maxFrames = 1, isActive = { true }, onProgress = { }) { b, _ -> out = b }
        } catch (e: Throwable) {
            Log.e(TAG, "preview failed", e)
        }
        return out
    }

    private fun fit(src: Bitmap, targetLong: Int, rotation: Int): Bitmap {
        val m = Matrix()
        val longSide = max(src.width, src.height)
        if (longSide > targetLong) { val sc = targetLong.toFloat() / longSide; m.postScale(sc, sc) }
        if (rotation != 0) m.postRotate(rotation.toFloat())
        return if (m.isIdentity) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** YUV_420_888 → NV21 → JPEG → Bitmap (يتعامل مع rowStride/pixelStride). */
    private fun yuvToBitmap(img: Image): Bitmap? {
        val w = img.width and -2
        val h = img.height and -2
        if (w <= 0 || h <= 0) return null
        val nv21 = ByteArray(w * h * 3 / 2)
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        var pos = 0
        val yBuf = yp.buffer
        for (row in 0 until h) {
            if (yp.pixelStride == 1) {
                yBuf.position(row * yp.rowStride)
                yBuf.get(nv21, pos, w)
                pos += w
            } else {
                for (col in 0 until w) nv21[pos++] = yBuf.get(row * yp.rowStride + col * yp.pixelStride)
            }
        }
        val uBuf = up.buffer
        val vBuf = vp.buffer
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                nv21[pos++] = vBuf.get(row * vp.rowStride + col * vp.pixelStride)
                nv21[pos++] = uBuf.get(row * up.rowStride + col * up.pixelStride)
            }
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 90, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}

/** يشغّل نفس الكشف والتتبع بتاع التطبيق على فيديو مسجّل، من غير ما يكتب أي حاجة في قاعدة البيانات. */
class VideoAnalyzer(private val ctx: Context) {

    class Row(val type: VehicleType, val rawKmh: Double, val errKmh: Float, val conf: Int, val atSec: Double, val thumb: Bitmap?)
    class Result(val rows: List<Row>, val frames: Int, val videoSec: Double, val detMs: Int)

    /** السرعات بترجع "خام" (من غير معايرة)، عشان نقدر نقارنها بالسرعة الحقيقية ونضيفها كعينة معايرة. */
    suspend fun run(uri: Uri, analysisFps: Int, s: AppSettings, onProgress: (Float) -> Unit): Result =
        withContext(Dispatchers.Default) {
            val det = VehicleDetector(ctx, s.useGpu)
            if (!det.isReady) throw IllegalStateException("ملف الموديل مش موجود في assets")
            val tracker = SpeedTracker()
            val raw = s.copy(speedCalib = 1f, speedOffset = 0f, countAll = false)
            val rows = ArrayList<Row>()
            var frames = 0
            var detSum = 0L
            var lastPtsNs = 0L
            var lastAspect = 16f / 9f
            try {
                VideoFrames.readFrames(
                    ctx, uri, analysisFps, if (s.highRes) 1280 else 640,
                    isActive = { isActive }, onProgress = onProgress
                ) { frame, ptsNs ->
                    val t0 = SystemClock.elapsedRealtime()
                    var dets = det.detect(frame, s.minScore)
                    if (!det.usingCustom) dets = dets.map { SizeClassifier.refine(it, s) }
                    detSum += SystemClock.elapsedRealtime() - t0
                    frames++
                    lastAspect = frame.width.toFloat() / frame.height
                    lastPtsNs = ptsNs
                    val res = tracker.update(dets, ptsNs, lastAspect, raw)
                    for (ev in res.events) {
                        if (ev.speedKmh < 0) continue
                        val box = res.tracked.firstOrNull { it.id == ev.trackId }?.box
                        rows += Row(ev.type, ev.speedKmh, ev.errKmh, ev.conf, ptsNs / 1e9, thumbOf(frame, box))
                    }
                }
                // آخر الفيديو: نحسم أي عبور لسه معلّق
                val tail = tracker.update(emptyList(), lastPtsNs + 33_000_000L, lastAspect, raw)
                for (ev in tail.events) if (ev.speedKmh >= 0) rows += Row(ev.type, ev.speedKmh, ev.errKmh, ev.conf, lastPtsNs / 1e9, null)
            } finally {
                det.close()
            }
            Result(rows, frames, lastPtsNs / 1e9, if (frames > 0) (detSum / frames).toInt() else 0)
        }

    private fun thumbOf(frame: Bitmap, box: NBox?): Bitmap? {
        if (box == null) return null
        val w = frame.width
        val h = frame.height
        val pad = 0.15f
        val l = ((box.l - box.w * pad) * w).toInt().coerceIn(0, w - 1)
        val t = ((box.t - box.h * pad) * h).toInt().coerceIn(0, h - 1)
        val r = ((box.r + box.w * pad) * w).toInt().coerceIn(l + 1, w)
        val b = ((box.b + box.h * pad) * h).toInt().coerceIn(t + 1, h)
        return try {
            val c = Bitmap.createBitmap(frame, l, t, r - l, b - t)
            val sc = 160f / max(c.width, c.height)
            if (sc < 1f) Bitmap.createScaledBitmap(c, max(1, (c.width * sc).toInt()), max(1, (c.height * sc).toInt()), true) else c
        } catch (_: Throwable) {
            null
        }
    }
}

/** يضيف عينة (سرعة حقيقية، سرعة خام) لمعايرة السرعة ويعيد حساب المعاملين. */
internal fun addCalSample(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, real: Float, raw: Float) {
    val list = (Calibration.parse(s.calSamples) + Calibration.Sample(real, raw)).takeLast(MAX_CAL_SAMPLES)
    val (fa, fb) = Calibration.fit(list)
    update { it.copy(speedCalib = fa, speedOffset = fb, calSamples = Calibration.encode(list)) }
}

private fun fmtTime(sec: Double): String = String.format(Locale.US, "%d:%04.1f", (sec / 60).toInt(), sec % 60)

@Composable
fun VideoTestCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var fps by remember { mutableStateOf(15) }
    var progress by remember { mutableStateOf(0f) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VideoAnalyzer.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var realTxt by remember { mutableStateOf("") }
    val real = realTxt.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) {
            uri = u; result = null; error = null; preview = null
            scope.launch { preview = withContext(Dispatchers.IO) { VideoFrames.previewFrame(ctx, u) } }
        }
    }

    fun start() {
        val u = uri ?: return
        running = true; progress = 0f; result = null; error = null
        job = scope.launch {
            try {
                result = VideoAnalyzer(ctx).run(u, fps, s) { progress = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "analysis failed", e)
                error = e.message ?: "فشل تحليل الفيديو"
            } finally {
                running = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "صوّر فيديو للطريق بكاميرا الموبايل من نفس مكان التثبيت، وحلّله هنا أكتر من مرة وجرّب أي تعديل في الإعدادات " +
                "(الخطين، المسافة، المعايرة) على نفس الفيديو. التحليل بيستخدم نفس الكشف والتتبع، ومش بيسجّل أي حاجة في قاعدة البيانات.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = { picker.launch("video/*") }, enabled = !running) {
            Text(if (uri == null) "اختيار فيديو" else "تغيير الفيديو")
        }
        preview?.let { bmp ->
            Box(Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height).clip(RoundedCornerShape(10.dp))) {
                UiImage(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize()) {
                    val ga = Geo.segment(s, 0)
                    val gb = Geo.segment(s, 1)
                    if (ga != null && gb != null) {
                        drawLine(Color(0xFFD85A30), Offset(ga[0] * size.width, ga[1] * size.height), Offset(ga[2] * size.width, ga[3] * size.height), 4f)
                        drawLine(Color(0xFF1D9E75), Offset(gb[0] * size.width, gb[1] * size.height), Offset(gb[2] * size.width, gb[3] * size.height), 4f)
                    } else listOf(s.lineA to Color(0xFFD85A30), s.lineB to Color(0xFF1D9E75)).forEach { (p, col) ->
                        if (s.axisHorizontal) drawLine(col, Offset(p * size.width, 0f), Offset(p * size.width, size.height), 4f)
                        else drawLine(col, Offset(0f, p * size.height), Offset(size.width, p * size.height), 4f)
                    }
                }
            }
            Text(
                "الخط الأول (برتقالي) والثاني (أخضر) بنفس مكانهم في الإعدادات. عدّلهم من كارت المعايرة فوق لحد ما ينطبقوا على علامتي القياس على الأرض.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("عدد الفريمات المحللة في الثانية (بيحاكي سرعة جهازك)", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 15, 30).forEach { v ->
                FilterChip(fps == v, { fps = v }, { Text("$v") }, enabled = !running)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = uri != null && !running, onClick = { start() }) { Text("ابدأ التحليل") }
            if (running) OutlinedButton(onClick = { job?.cancel() }) { Text("إيقاف") }
        }
        if (running) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
            }
            Text("${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        result?.let { r ->
            Text(
                "اتحلل ${r.frames} فريم (${fmtTime(r.videoSec)} من الفيديو)  •  ${r.rows.size} قياس  •  " +
                    "زمن الكشف ${r.detMs} ms للفريم، يعني جهازك يقدر يحلل حوالي ${(1000f / max(1, r.detMs)).roundToInt()} فريم/ث (من زمن الكشف فقط).",
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
            )
            if (r.rows.isEmpty()) {
                Text(
                    "ما اتقاسش أي عبور كامل. تأكد إن الخطين على مسار العربيات وإن العربية بتعدي الخطين الاتنين، وإن المسافة بينهم مظبوطة.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = realTxt, onValueChange = { realTxt = it },
                    label = { Text("السرعة الحقيقية للعربية (كم/س، اختياري)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "لو كل العربيات في الفيديو بنفس السرعة المعروفة، هتظهر نسبة الخطأ لكل قياس، وتقدر تضيف أي قياس كعينة معايرة.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                r.rows.take(MAX_ROWS_SHOWN).forEach { row -> VideoRowItem(row, s, real) { rv -> addCalSample(s, vm::update, rv, row.rawKmh.toFloat()); Toast.makeText(ctx, "تمت إضافة العينة", Toast.LENGTH_SHORT).show() } }
                if (r.rows.size > MAX_ROWS_SHOWN) {
                    Text("معروض أول $MAX_ROWS_SHOWN قياس من ${r.rows.size}.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun VideoRowItem(row: VideoAnalyzer.Row, s: AppSettings, real: Float?, onAddSample: (Float) -> Unit) {
    val cal = s.calibrate(row.rawKmh)
    val errTxt = if (row.errKmh >= 0.5f) " ± ${row.errKmh.roundToInt()}" else ""
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        val th = row.thumb
        if (th != null) {
            UiImage(th.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${row.type.ar}  •  عند ${fmtTime(row.atSec)}", fontWeight = FontWeight.Bold)
            Text(
                "خام ${String.format(Locale.US, "%.1f", row.rawKmh)}  •  بعد المعايرة ${String.format(Locale.US, "%.1f", cal)}$errTxt كم/س" +
                    if (row.conf > 0) "  •  ثقة ${row.conf}%" else "",
                style = MaterialTheme.typography.bodySmall
            )
            if (real != null) {
                val pct = (cal - real) / real * 100.0
                val bad = kotlin.math.abs(pct) > 10.0
                Text(
                    "الخطأ عن الحقيقي: " + (if (pct >= 0) "+" else "") + String.format(Locale.US, "%.1f", pct) + "%",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                    color = if (bad) MaterialTheme.colorScheme.error else Color(0xFF2E7D32)
                )
                TextButton(onClick = { onAddSample(real) }) { Text("أضف كعينة معايرة") }
            }
        }
    }
}

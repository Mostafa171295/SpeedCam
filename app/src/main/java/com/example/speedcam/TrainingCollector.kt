package com.example.speedcam

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CollectState(
    val count: Int = 0,
    val bytes: Long = 0L,
    val full: Boolean = false,
    val lastSavedMs: Long = 0L
)

/**
 * جامع بيانات التدريب: بيحفظ لقطات من الشارع كل فترة مع صناديق الكاشف (بصيغة YOLO) عشان تقدر تراجعها وتدرّب موديل
 * بيعرف عربيات بلدك (توك توك، ميكروباص...). التصدير بيطلع ZIP فيه images/ وlabels/ وclasses.txt وdata.yaml.
 *
 * ملاحظات تصميم:
 *  - الصناديق بتتحسب من كشف منفصل على الصورة كلها (من غير قص منطقة الكشف)، عشان العربيات اللي برّه المنطقة تتعلّم برضه.
 *    من غير كده الصورة هتتحفظ وفيها عربيات من غير تعليم، وده بيبوّظ التدريب.
 *  - الصناديق تخمينات آلية (pseudo-labels): لازم تتراجع قبل التدريب.
 *  - الشغل كله على خيط خلفي منفصل وفي فريم واحد في المرة، فتأثيره على القياس صغير.
 */
class TrainingCollector(ctx: Context) {
    private val app = ctx.applicationContext
    private val dir = File(app.filesDir, "train").apply { mkdirs() }
    val state = MutableStateFlow(CollectState())

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "train-collector").apply { priority = Thread.MIN_PRIORITY } }
    @Volatile private var lastMs = 0L
    @Volatile private var busy = false

    init { refresh() }

    private fun images(): List<File> = (dir.listFiles { f -> f.extension == "jpg" } ?: emptyArray()).sortedBy { it.name }

    fun refresh() {
        val imgs = images()
        state.value = CollectState(imgs.size, imgs.sumOf { it.length() }, state.value.full && imgs.isNotEmpty(), state.value.lastSavedMs)
    }

    /** بيتنادى لكل فريم من المحرك؛ بيشتغل فعلاً مرة كل s.collectEverySec بس. detect = كشف على الصورة كلها. */
    fun offer(frame: Bitmap, nowMs: Long, s: AppSettings, detect: (Bitmap) -> List<Det>) {
        if (busy || nowMs - lastMs < s.collectEverySec * 1000L) return
        val st = state.value
        if (st.count >= s.collectMax || st.bytes >= MAX_BYTES) {
            if (!st.full) state.value = st.copy(full = true)
            return
        }
        val copy = (try { frame.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Throwable) { null }) ?: return
        busy = true
        lastMs = nowMs
        val mode = s.collectMode
        exec.execute {
            try { save(copy, mode, detect) } catch (t: Throwable) { Log.w(TAG, "collect failed", t) }
            finally { copy.recycle(); busy = false }
        }
    }

    private fun save(bmp: Bitmap, mode: Int, detect: (Bitmap) -> List<Det>) {
        val dets = detect(bmp)
        if (dets.isEmpty()) return                                       // مفيش عربيات: مفيش حاجة تتعلّم
        if (mode == MODE_UNCERTAIN && dets.none { it.score < UNCERTAIN }) return   // الكشف كله واثق: مش محتاجينه
        val name = "img_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val img = File(dir, "$name.jpg")
        img.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val labels = dets.joinToString("\n") { d ->
            String.format(Locale.US, "%d %.6f %.6f %.6f %.6f", d.type.ordinal, d.box.cx, d.box.cy, d.box.w, d.box.h)
        }
        File(dir, "$name.txt").writeText(labels + "\n")
        state.update { it.copy(count = it.count + 1, bytes = it.bytes + img.length(), lastSavedMs = System.currentTimeMillis()) }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        state.value = CollectState()
    }

    // =====================  التصدير  =====================

    /** يعمل ZIP بصيغة YOLO. null لو مفيش صور. */
    fun exportZip(): File? {
        val imgs = images()
        if (imgs.isEmpty()) return null
        val outDir = File(app.cacheDir, "exports").apply { mkdirs() }
        val out = File(outDir, "training_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.setLevel(Deflater.NO_COMPRESSION)    // الصور JPEG مضغوطة أصلاً
            fun text(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
            for (img in imgs) {
                z.putNextEntry(ZipEntry("images/${img.name}")); img.inputStream().use { it.copyTo(z) }; z.closeEntry()
                val lab = File(dir, img.nameWithoutExtension + ".txt")
                if (lab.exists()) { z.putNextEntry(ZipEntry("labels/${lab.name}")); lab.inputStream().use { it.copyTo(z) }; z.closeEntry() }
            }
            val names = VehicleType.entries.map { it.name.lowercase() }
            text("classes.txt", names.joinToString("\n") + "\n")
            text("data.yaml", "path: .\ntrain: images\nval: images\nnames:\n" + names.mapIndexed { i, n -> "  $i: $n" }.joinToString("\n") + "\n")
            text("README.txt", README)
        }
        return out
    }

    fun saveZipToDownloads(file: File): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) false else {
            app.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            true
        }
    } catch (_: Exception) { false }

    fun shareZip(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, tr(R.string.tr_share)))
    }

    companion object {
        private const val TAG = "TrainingCollector"
        const val MODE_ALL = 0
        const val MODE_UNCERTAIN = 1
        /** أقل ثقة نكتب بيها صندوق في الملف (أقل من كده ضوضاء). */
        const val LABEL_MIN_SCORE = 0.4f
        private const val UNCERTAIN = 0.6f           // أي صندوق أقل من كده = كشف غير مؤكد
        private const val MAX_BYTES = 600L * 1024 * 1024

        private const val README = """Training data exported by Speed Radar

Layout (YOLO format):
  images/*.jpg    frames from your camera
  labels/*.txt    one line per vehicle: <class_id> <x_center> <y_center> <width> <height> (all 0..1)
  classes.txt     class names in id order
  data.yaml       dataset description

IMPORTANT
- The boxes are automatic guesses from the app's detector. Review and correct them (for example with
  Label Studio, CVAT or Roboflow) before training, especially the vehicle types you care about.
- Keep class names exactly as in classes.txt (car, microbus, tuktuk, motorcycle, bus, truck): the app maps
  model labels by these names.
- Train an object-detection model of your choice, export it as a TensorFlow Lite detection model with metadata
  that MediaPipe's Object Detector can load, and name it vehicles_custom.tflite in the app's assets folder.
- The images can show people and license plates. Keep them private, follow your local privacy laws, and blur
  plates and faces before sharing the dataset with anyone.
"""
    }
}

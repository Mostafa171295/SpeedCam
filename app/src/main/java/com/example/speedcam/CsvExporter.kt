package com.example.speedcam

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** يصدّر السجلات بصيغة CSV (UTF-8 مع BOM) تُفتح مباشرة في Excel بالعربي. */
object CsvExporter {

    fun build(ctx: Context, records: List<SpeedRecord>, axisHorizontal: Boolean): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "traffic_stats_$stamp.csv")
        val dateF = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeF = SimpleDateFormat("HH:mm:ss", Locale.US)
        val hourF = SimpleDateFormat("HH", Locale.US)

        file.outputStream().use { os ->
            os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            os.writer(Charsets.UTF_8).buffered().use { w ->
                w.write("sep=,\r\n")
                w.write("الرقم,التاريخ,الوقت,الساعة,نوع المركبة,السرعة (كم/س),الاتجاه,اللوحة,حروف اللوحة,أرقام اللوحة,ثقة القراءة %,ثقة السرعة %,هامش الخطأ ± (كم/س),الحارة\r\n")
                for (r in records.sortedBy { it.timestamp }) {
                    val d = Date(r.timestamp)
                    val type = VehicleType.fromName(r.vehicleType)?.ar ?: r.vehicleType
                    val row = listOf(
                        r.id.toString(), dateF.format(d), timeF.format(d), hourF.format(d), type,
                        if (r.speedKmh < 0) "" else String.format(Locale.US, "%.1f", r.speedKmh), directionLabel(r.direction, axisHorizontal),
                        if (r.plateDigits.isEmpty()) "" else PlateParser.display(r.plateLetters, r.plateDigits),
                        r.plateLetters.toList().joinToString(" "), r.plateDigits,
                        if (r.plateDigits.isEmpty()) "" else r.plateConf.toString(),
                        if (r.speedConf <= 0) "" else r.speedConf.toString(),
                        if (r.speedErr <= 0f) "" else String.format(Locale.US, "%.1f", r.speedErr),
                        if (r.lane <= 0) "" else r.lane.toString()
                    )
                    w.write(row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
                    w.write("\r\n")
                }
            }
        }
        return file
    }

    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "مشاركة ملف Excel"))
    }

    fun saveToDownloads(ctx: Context, file: File): Boolean {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** نسخة احتياطية يومية: ملف واحد لكل يوم في مجلد التنزيلات، ويتحدّث بنفس الاسم. */
    fun backupToDownloads(ctx: Context, records: List<SpeedRecord>, axisHorizontal: Boolean): Boolean {
        return try {
            val file = build(ctx, records, axisHorizontal)
            val name = "speedcam_backup_" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".csv"
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            var found: android.net.Uri? = null
            resolver.query(
                collection, arrayOf(MediaStore.Downloads._ID),
                MediaStore.Downloads.DISPLAY_NAME + "=?", arrayOf(name), null
            )?.use { c ->
                if (c.moveToFirst()) found = android.content.ContentUris.withAppendedId(collection, c.getLong(0))
            }
            if (found == null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                found = resolver.insert(collection, values)
            }
            val target = found ?: return false
            resolver.openOutputStream(target, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            file.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** أرشفة سجلات قديمة قبل مسحها تلقائياً: ملف جديد باسم speedcam_archive_ وتاريخ ووقت في مجلد التنزيلات. */
    fun archiveToDownloads(ctx: Context, records: List<SpeedRecord>, axisHorizontal: Boolean): Boolean {
        return try {
            val f = build(ctx, records, axisHorizontal)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val named = File(f.parentFile, "speedcam_archive_$stamp.csv")
            if (!f.renameTo(named)) return false
            val ok = saveToDownloads(ctx, named)
            named.delete()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** تقرير حجم المرور: صف لكل ساعة (أو يوم) وعمود لكل نوع مركبة. */
    fun buildVolume(ctx: Context, buckets: List<Bucket>, byDay: Boolean): File {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "traffic_volume_${if (byDay) "daily" else "hourly"}_$stamp.csv")
        val dateF = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        file.outputStream().use { os ->
            os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            os.writer(Charsets.UTF_8).buffered().use { w ->
                w.write("sep=,\r\n")
                val head = mutableListOf("التاريخ", if (byDay) "الفترة" else "من الساعة")
                if (!byDay) head += "إلى الساعة"
                head += VehicleType.entries.map { it.ar }
                head += "الإجمالي"
                w.write(head.joinToString(",") { "\"$it\"" } + "\r\n")
                for (b in buckets.sortedBy { it.startMs }) {
                    val cal = Calendar.getInstance().apply { timeInMillis = b.startMs }
                    val h = cal.get(Calendar.HOUR_OF_DAY)
                    val row = mutableListOf(dateF.format(Date(b.startMs)))
                    if (byDay) row += "يوم كامل"
                    else {
                        row += String.format(Locale.US, "%02d:00", h)
                        row += String.format(Locale.US, "%02d:00", (h + 1) % 24)
                    }
                    row += b.counts.map { it.toString() }
                    row += b.total.toString()
                    w.write(row.joinToString(",") { "\"$it\"" } + "\r\n")
                }
            }
        }
        return file
    }
}

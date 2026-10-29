package com.example.speedcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

private fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val o = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
}

/**
 * البند 5: اختبار قراءة اللوحات من صورة (من المعرض) من غير ما تنزل الشارع.
 * بيعرض كل خطوة: الصورة اللي اتقرأت، بعد التجهيز، النص الخام اللي طلع من المحرك، والثقة، والنتيجة النهائية.
 */
@Composable
fun PlateTestCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val dataState by vm.plateReader.dataState.collectAsStateWithLifecycle()
    var mode by remember { mutableIntStateOf(0) }       // 0 = صورة مركبة كاملة ، 1 = صورة لوحة قريبة
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var rot by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var diag by remember { mutableStateOf<PlateDiag?>(null) }
    var msg by remember { mutableStateOf("") }

    fun run() {
        val base = src ?: return
        scope.launch {
            busy = true
            msg = ""
            diag = null
            try {
                diag = withContext(Dispatchers.Default) {
                    val img = if (rot == 0) base else Bitmap.createBitmap(base, 0, 0, base.width, base.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                    if (mode == 0) {
                        val dets = vm.engine.detectBoxes(img, s)
                        val best = dets.maxByOrNull { it.box.w * it.box.h }
                        if (best == null) {
                            msg = "ما لقيتش مركبة في الصورة. جرّب تدوير الصورة أو اختار \"صورة لوحة قريبة\"."
                            null
                        } else vm.plateReader.diagnose(img, best.box)
                    } else vm.plateReader.diagnose(img, null)
                }
            } catch (t: Throwable) {
                msg = "فشل الاختبار: ${t.message ?: ""}"
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                src = withContext(Dispatchers.IO) { try { loadBitmap(ctx, uri, 1600) } catch (_: Throwable) { null } }
                rot = 0
                if (src == null) msg = "تعذّر فتح الصورة" else run()
            }
        }
    }

    AppCard {
        SectionTitle(Icons.Filled.CreditCard, "اختبار قراءة اللوحات من صورة")
        Text(
            "اختار صورة من المعرض (مركبة كاملة أو لوحة قريبة) وشوف التطبيق بيقرأ إيه، وفين بيغلط. " +
                "كل ما الصورة أقرب وأوضح كل ما القراءة أحسن.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == 0, { mode = 0; run() }, { Text("صورة مركبة كاملة") })
            FilterChip(mode == 1, { mode = 1; run() }, { Text("صورة لوحة قريبة") })
        }
        if (dataState != PLATE_DATA_READY) {
            Text(
                "ملف اللغة العربية لسه مش جاهز (فعّل قراءة اللوحات فوق وانتظر التحميل، أو حط ara.traineddata في assets).",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { picker.launch("image/*") }) { Text("اختيار صورة") }
            OutlinedButton(enabled = !busy && src != null, onClick = { rot = (rot + 90) % 360; run() }) { Text("تدوير 90°") }
        }
        if (busy) Text("جاري القراءة...", style = MaterialTheme.typography.bodySmall)
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        diag?.let { d ->
            Text(d.note, style = MaterialTheme.typography.bodySmall)
            val best = d.best
            Text(
                if (best != null) "النتيجة: ${PlateParser.display(best.letters, best.digits)}   (ثقة ${best.conf}%)"
                else "النتيجة: مفيش قراءة مقبولة",
                fontWeight = FontWeight.Bold,
                color = if (best != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text("الزمن: ${d.ms} ms", style = MaterialTheme.typography.bodySmall)
            d.attempts.forEach { a ->
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.name, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.used?.let { Image(it.asImageBitmap(), null, Modifier.weight(1f).heightIn(max = 120.dp), contentScale = ContentScale.Fit) }
                        Image(a.prepared.asImageBitmap(), null, Modifier.weight(1f).heightIn(max = 120.dp), contentScale = ContentScale.Fit)
                    }
                    Text("النص الخام: \u200E" + (a.raw.ifBlank { "(فاضي)" }).replace('\n', ' ') + "   |   ثقة ${a.conf}%")
                    Text(
                        a.hit?.let { "اتقبلت: " + PlateParser.display(it.letters, it.digits) }
                            ?: "اترفضت (ثقة أقل من 35 أو الشكل مش شكل لوحة: 1-3 حروف و1-4 أرقام)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (a.hit != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

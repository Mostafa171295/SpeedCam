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
                            msg = tr(R.string.str_0168)
                            null
                        } else vm.plateReader.diagnose(img, best.box)
                    } else vm.plateReader.diagnose(img, null)
                }
            } catch (t: Throwable) {
                msg = tr(R.string.str_0169, t.message ?: "")
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
                if (src == null) msg = tr(R.string.str_0170) else run()
            }
        }
    }

    AppCard {
        SectionTitle(Icons.Filled.CreditCard, tr(R.string.str_0171))
        Text(
            tr(R.string.str_0172) +
                tr(R.string.str_0173),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == 0, { mode = 0; run() }, { Text(tr(R.string.str_0174)) })
            FilterChip(mode == 1, { mode = 1; run() }, { Text(tr(R.string.str_0175)) })
        }
        if (dataState != PLATE_DATA_READY) {
            Text(
                tr(R.string.str_0176, vm.plateReader.dataFileName()),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f)) { BtnText(tr(R.string.str_0177)) }
            OutlinedButton(modifier = Modifier.weight(1f), enabled = !busy && src != null, onClick = { rot = (rot + 90) % 360; run() }) { BtnText(tr(R.string.str_0178)) }
        }
        if (busy) Text(tr(R.string.str_0179), style = MaterialTheme.typography.bodySmall)
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        diag?.let { d ->
            Text(d.note, style = MaterialTheme.typography.bodySmall)
            val best = d.best
            Text(
                if (best != null) tr(R.string.str_0180, PlateParser.display(best.letters, best.digits), best.conf)
                else tr(R.string.str_0181),
                fontWeight = FontWeight.Bold,
                color = if (best != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Text(tr(R.string.str_0182, d.ms), style = MaterialTheme.typography.bodySmall)
            d.attempts.forEach { a ->
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.name, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.used?.let { Image(it.asImageBitmap(), null, Modifier.weight(1f).heightIn(max = 120.dp), contentScale = ContentScale.Fit) }
                        Image(a.prepared.asImageBitmap(), null, Modifier.weight(1f).heightIn(max = 120.dp), contentScale = ContentScale.Fit)
                    }
                    Text(tr(R.string.str_0183) + (a.raw.ifBlank { tr(R.string.str_0184) }).replace('\n', ' ') + tr(R.string.str_0185, a.conf))
                    Text(
                        a.hit?.let { tr(R.string.str_0186) + PlateParser.display(it.letters, it.digits) }
                            ?: tr(R.string.str_0187),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (a.hit != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

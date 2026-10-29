package com.example.speedcam

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val MAX_ROWS = 300
private val AMBER = Color(0xFFD98200)

/** 0 = ضمن الحد أو بدون قياس، 1 = فوق الحد لكن ضمن هامش الخطأ، 2 = تجاوز مؤكد (حتى بعد خصم الهامش). */
private fun statusOf(r: SpeedRecord, limit: Float): Int {
    if (r.speedKmh < 0 || r.speedKmh <= limit) return 0
    return if (r.speedKmh - r.speedErr > limit) 2 else 1
}
private const val MIN_RELIABLE = 70   // أقل ثقة نعتبرها "موثوقة" في فلتر القراءات الموثوقة
private val QUICK_LIMITS = listOf(40, 60, 80, 100, 120)

private fun limitText(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else String.format(Locale.US, "%.1f", v)

/**
 * تبويب "المركبات": كل مركبة ظهرت في الكاميرا وتم تسجيلها.
 * أي مركبة سرعتها أعلى من الحد اللي بيدخله المستخدم بتظهر باللون الأحمر.
 */
@Composable
fun VehiclesScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val allRecords by vm.records.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    var onlyOver by rememberSaveable { mutableStateOf(false) }
    var reliableOnly by rememberSaveable { mutableStateOf(false) }
    var evidencePath by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(0) }                    // 1 = حذف المعروض ، 2 = حذف الكل
    var delOne by remember { mutableStateOf<SpeedRecord?>(null) }    // حذف مركبة واحدة
    var limitTxt by remember { mutableStateOf(limitText(s.speedLimit)) }
    val fmt = remember { SimpleDateFormat("MM/dd  HH:mm:ss", Locale.getDefault()) }
    val red = MaterialTheme.colorScheme.error
    val limit = s.speedLimit

    // ثقة 0 = غير معروفة (قياسات قديمة أو الوضع السريع) وبتتحسب مع الموثوقة
    val all = remember(allRecords, reliableOnly) {
        if (reliableOnly) allRecords.filter { it.speedConf == 0 || it.speedConf >= MIN_RELIABLE } else allRecords
    }
    val measured = remember(all) { all.filter { it.speedKmh >= 0 } }
    // مؤكدة: الحد الأدنى لهامش الخطأ فوق الحد. غير مؤكدة: السرعة فوق الحد لكن ضمن هامش الخطأ.
    val over = remember(measured, limit) { measured.filter { statusOf(it, limit) > 0 } }
    val confirmed = remember(measured, limit) { measured.filter { statusOf(it, limit) == 2 } }
    val uncertainCount = over.size - confirmed.size
    val list = if (onlyOver) over else all
    val maxSpeed = measured.maxOfOrNull { it.speedKmh }
    val rate = if (measured.isEmpty()) 0f else confirmed.size.toFloat() / measured.size

    evidencePath?.let { p -> EvidenceDialog(p) { evidencePath = null } }

    if (confirm != 0) {
        val all2 = confirm == 2
        val n = if (all2) allRecords.size else list.size
        AlertDialog(
            onDismissRequest = { confirm = 0 },
            title = { Text(if (all2) "حذف كل المركبات؟" else "حذف المركبات المعروضة؟") },
            text = {
                Text(
                    "هيتم حذف $n مركبة" + (if (all2) " (كل المسجّل)" else " (حسب الفلتر الحالي)") +
                        " ومعاها لقطات الإثبات بتاعتها، ولا يمكن التراجع. لو عايز نسخة، صدّر Excel الأول."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (all2) vm.clearAll() else vm.deleteMany(list)
                    confirm = 0
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = 0 }) { Text("إلغاء") } }
        )
    }
    delOne?.let { r ->
        AlertDialog(
            onDismissRequest = { delOne = null },
            title = { Text("حذف المركبة دي؟") },
            text = { Text("هيتم حذف المركبة المسجلة في ${fmt.format(Date(r.timestamp))} ومعاها لقطة الإثبات (لو فيه).") },
            confirmButton = {
                TextButton(onClick = { vm.delete(r); delOne = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { delOne = null }) { Text("إلغاء") } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader("المركبات", "كل المركبات اللي ظهرت في الكاميرا، والمتجاوزة للسرعة بالأحمر")
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- حد السرعة ----------
            item {
                AppCard {
                    SectionTitle(Icons.Filled.Speed, "حد السرعة")
                    OutlinedTextField(
                        value = limitTxt,
                        onValueChange = { v ->
                            limitTxt = v
                            v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
                                ?.let { n -> vm.update { it.copy(speedLimit = n) } }
                        },
                        label = { Text("أي مركبة أسرع من (كم/س) تظهر بالأحمر") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QUICK_LIMITS.forEach { v ->
                            FilterChip(
                                selected = limit == v.toFloat(),
                                onClick = { limitTxt = v.toString(); vm.update { it.copy(speedLimit = v.toFloat()) } },
                                label = { Text("$v") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = red.copy(alpha = 0.15f), selectedLabelColor = red
                                )
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("قراءات موثوقة فقط", fontWeight = FontWeight.Bold)
                            Text(
                                "يستبعد القراءات اللي ثقتها أقل من $MIN_RELIABLE% (التتبع كان ضعيف أو المربع بيتهز).",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = reliableOnly, onCheckedChange = { reliableOnly = it })
                    }
                }
            }

            // ---------- ملخص ----------
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryTile(Modifier.weight(1f).fillMaxHeight(), "إجمالي المركبات", "${all.size}", MaterialTheme.colorScheme.primary)
                    SummaryTile(Modifier.weight(1f).fillMaxHeight(), "متجاوزة مؤكدة", "${confirmed.size}", red)
                    SummaryTile(
                        Modifier.weight(1f).fillMaxHeight(), "أعلى سرعة",
                        maxSpeed?.let { "${it.roundToInt()}" } ?: "—",
                        if (maxSpeed != null && maxSpeed > limit) red else MaterialTheme.colorScheme.secondary
                    )
                }
            }
            if (measured.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("نسبة المتجاوزين المؤكدين", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${(rate * 100).roundToInt()}%  (${confirmed.size} من ${measured.size})", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = if (confirmed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else red)
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            if (rate > 0f) Box(Modifier.fillMaxWidth(rate.coerceIn(0.02f, 1f)).fillMaxHeight().background(red))
                        }
                        if (uncertainCount > 0) {
                            Text(
                                "$uncertainCount قراءة فوق الحد لكن ضمن هامش الخطأ (غير مؤكدة)",
                                style = MaterialTheme.typography.bodySmall, color = AMBER
                            )
                        }
                    }
                }
            }

            // ---------- فلتر + تصدير ----------
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!onlyOver, { onlyOver = false }, { Text("كل المركبات (${all.size})") })
                    FilterChip(
                        onlyOver, { onlyOver = true }, { Text("المتجاوزة (${over.size})") },
                        leadingIcon = { Icon(Icons.Filled.Warning, null, Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = red.copy(alpha = 0.15f), selectedLabelColor = red, selectedLeadingIconColor = red
                        )
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { CsvExporter.build(ctx, list, s.axisHorizontal) }
                            CsvExporter.share(ctx, f)
                        }
                    }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("مشاركة Excel") }
                    OutlinedButton(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                CsvExporter.saveToDownloads(ctx, CsvExporter.build(ctx, list, s.axisHorizontal))
                            }
                            Toast.makeText(ctx, if (ok) "تم الحفظ في مجلد التنزيلات" else "فشل الحفظ", Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("حفظ في التنزيلات") }
                }
            }

            // ---------- حذف ----------
            item {
                AppCard {
                    SectionTitle(Icons.Filled.Delete, "حذف المركبات")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = list.isNotEmpty(), onClick = { confirm = 1 },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = red)
                        ) { Text("حذف المعروض (${list.size})") }
                        Button(
                            enabled = allRecords.isNotEmpty(), onClick = { confirm = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = red)
                        ) { Text("حذف الكل (${allRecords.size})") }
                    }
                    Text(
                        "\"حذف المعروض\" بيمسح اللي ظاهر حسب الفلتر (المتجاوزين أو الموثوقة بس). وفيه زرار حذف على كل مركبة لوحدها.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- القائمة ----------
            if (list.isEmpty()) {
                item {
                    AppCard {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                if (onlyOver) "لا توجد مركبات متجاوزة لـ ${limitText(limit)} كم/س" else "لا توجد مركبات مسجلة بعد",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (onlyOver) "غيّر حد السرعة أو اعرض كل المركبات." else "شغّل القياس من تبويب الكاميرا وهتظهر هنا كل مركبة معدّية.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(list.take(MAX_ROWS), key = { it.id }) { r ->
                    VehicleRow(r, limit, fmt, { delOne = r }) { evidencePath = it }
                }
                if (list.size > MAX_ROWS) {
                    item {
                        Text(
                            "معروض أحدث $MAX_ROWS من ${list.size} — التصدير يشمل الكل.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(modifier: Modifier, label: String, value: String, tint: Color) {
    Card(
        modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tint)
        }
    }
}

@Composable
private fun VehicleRow(r: SpeedRecord, limit: Float, fmt: SimpleDateFormat, onDelete: () -> Unit, onEvidence: (String) -> Unit) {
    val red = MaterialTheme.colorScheme.error
    val type = VehicleType.fromName(r.vehicleType)
    val measured = r.speedKmh >= 0
    val status = if (measured) statusOf(r, limit) else 0
    val isOver = status == 2
    val isUncertain = status == 1
    val flag = if (isOver) red else AMBER
    val flagged = status > 0
    val accent = if (flagged) flag else Color(type?.color ?: 0xFF888888)

    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (flagged) flag.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(if (flagged) 1.5.dp else 1.dp, if (flagged) flag.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(type?.color ?: 0xFF888888)))
                        Text(type?.ar ?: r.vehicleType, fontWeight = FontWeight.Bold)
                        if (flagged) {
                            Icon(Icons.Filled.Warning, if (isOver) "تجاوز السرعة" else "تجاوز غير مؤكد", tint = flag, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        if (r.plateDigits.isNotEmpty()) PlateParser.display(r.plateLetters, r.plateDigits) else "لوحة غير مقروءة",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.plateDigits.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        fmt.format(Date(r.timestamp)) + when {
                            isOver -> "  •  أعلى من الحد بـ ${(r.speedKmh - limit).roundToInt()} كم/س"
                            isUncertain -> "  •  فوق الحد لكن ضمن هامش الخطأ"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (flagged) flag else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (flagged) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (measured && r.speedConf > 0) ConfidenceTag(r.speedConf)
                    if (r.evidencePath.isNotEmpty()) {
                        Row(
                            Modifier.clickable { onEvidence(r.evidencePath) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Filled.PhotoCamera, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Text("لقطة الإثبات", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (measured) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (flagged) flag else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (flagged) Color.White else MaterialTheme.colorScheme.onSurface
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${r.speedKmh.roundToInt()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                if (r.speedErr >= 0.5f) "± ${r.speedErr.roundToInt()} كم/س" else "كم/س",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                } else {
                    Text("عدّ فقط", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** مؤشر ثقة قراءة السرعة: أخضر عالية، كهرماني متوسطة، رمادي ضعيفة (مش أحمر عشان ما يتلخبطش مع التجاوز). */
@Composable
private fun ConfidenceTag(conf: Int) {
    val (color, label) = when {
        conf >= 75 -> Color(0xFF2E7D32) to "عالية"
        conf >= MIN_RELIABLE -> Color(0xFF2E7D32) to "جيدة"
        conf >= 50 -> Color(0xFFD98200) to "متوسطة"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to "ضعيفة"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            "ثقة القراءة $conf%  •  $label",
            style = MaterialTheme.typography.labelSmall, color = color
        )
    }
}

/** عرض لقطة الإثبات مع زر مشاركة. */
@Composable
private fun EvidenceDialog(path: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val bmp = remember(path) { try { BitmapFactory.decodeFile(path) } catch (_: Throwable) { null } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("لقطة الإثبات") },
        text = {
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), null,
                    Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("الصورة مش موجودة (ممكن تكون اتمسحت تلقائياً لأن التطبيق بيحتفظ بآخر 300 لقطة).")
            }
        },
        confirmButton = {
            if (bmp != null) TextButton(onClick = { Evidence.share(ctx, path) }) { Text("مشاركة") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إغلاق") } }
    )
}

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
// حدود سريعة بالوحدة المعروضة (كم/س أو ميل/س)
private fun quickLimits(): List<Int> = if (Units.imperial) listOf(25, 40, 50, 60, 75) else listOf(40, 60, 80, 100, 120)

private fun limitText(v: Float): String {
    val d = Units.spd(v)
    return if (kotlin.math.abs(d - d.toInt()) < 0.05) d.toInt().toString() else String.format(Locale.US, "%.1f", d)
}

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
    var weakFirst by rememberSaveable { mutableStateOf(false) }   // ترتيب الأضعف ثقة أولاً عشان نراجع المشكوك فيه
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
    val base = if (onlyOver) over else all
    // ثقة 0 (غير معروفة) بتتحط في الآخر عند الترتيب بالأضعف
    val list = if (weakFirst) base.sortedBy { if (it.speedConf == 0) 101 else it.speedConf } else base
    val qHigh = remember(allRecords) { allRecords.count { it.speedKmh >= 0 && it.speedConf >= 75 } }
    val qMid = remember(allRecords) { allRecords.count { it.speedKmh >= 0 && it.speedConf in 50..74 } }
    val qLow = remember(allRecords) { allRecords.count { it.speedKmh >= 0 && it.speedConf in 1..49 } }
    val maxSpeed = measured.maxOfOrNull { it.speedKmh }
    val rate = if (measured.isEmpty()) 0f else confirmed.size.toFloat() / measured.size

    evidencePath?.let { p -> EvidenceDialog(p) { evidencePath = null } }

    if (confirm != 0) {
        val all2 = confirm == 2
        val n = if (all2) allRecords.size else list.size
        AlertDialog(
            onDismissRequest = { confirm = 0 },
            title = { Text(if (all2) tr(R.string.str_0385) else tr(R.string.str_0386)) },
            text = {
                Text(
                    tr(R.string.str_0387, n) + (if (all2) tr(R.string.str_0388) else tr(R.string.str_0389)) +
                        tr(R.string.str_0390)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (all2) vm.clearAll() else vm.deleteMany(list)
                    confirm = 0
                }) { BtnText(tr(R.string.str_0163)) }
            },
            dismissButton = { TextButton(onClick = { confirm = 0 }) { BtnText(tr(R.string.str_0167)) } }
        )
    }
    delOne?.let { r ->
        AlertDialog(
            onDismissRequest = { delOne = null },
            title = { Text(tr(R.string.str_0391)) },
            text = { Text(tr(R.string.str_0392, fmt.format(Date(r.timestamp)))) },
            confirmButton = {
                TextButton(onClick = { vm.delete(r); delOne = null }) { BtnText(tr(R.string.str_0163)) }
            },
            dismissButton = { TextButton(onClick = { delOne = null }) { BtnText(tr(R.string.str_0167)) } }
        )
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader(tr(R.string.str_0126), tr(R.string.str_0393))
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- حد السرعة ----------
            item {
                AppCard {
                    SectionTitle(Icons.Filled.Speed, tr(R.string.str_0394))
                    OutlinedTextField(
                        value = limitTxt,
                        onValueChange = { v ->
                            limitTxt = v
                            v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
                                ?.let { n -> vm.update { it.copy(speedLimit = Units.toKmh(n).toFloat()) } }
                        },
                        label = { Text(tr(R.string.str_0395)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickLimits().forEach { v ->
                            FilterChip(
                                selected = kotlin.math.abs(limit - Units.toKmh(v).toFloat()) < 0.5f,
                                onClick = { limitTxt = v.toString(); vm.update { it.copy(speedLimit = Units.toKmh(v).toFloat()) } },
                                label = { Text("$v") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = red.copy(alpha = 0.15f), selectedLabelColor = red
                                )
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr(R.string.str_0396), fontWeight = FontWeight.Bold)
                            Text(
                                tr(R.string.str_0397, MIN_RELIABLE),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = reliableOnly, onCheckedChange = { reliableOnly = it })
                    }
                    // توزيع جودة القياسات: عالية / متوسطة / ضعيفة (الضعيفة غالباً تتبع ضعيف أو الكاميرا اتحركت)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityTile(Modifier.weight(1f), tr(R.string.str_0398), qHigh, Color(0xFF2E7D32))
                        QualityTile(Modifier.weight(1f), tr(R.string.str_0399), qMid, AMBER)
                        QualityTile(Modifier.weight(1f), tr(R.string.str_0400), qLow, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FilterChip(weakFirst, { weakFirst = !weakFirst }, { Text(tr(R.string.str_0401)) })
                }
            }

            // ---------- ملخص ----------
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryTile(Modifier.weight(1f).fillMaxHeight(), tr(R.string.str_0402), "${all.size}", MaterialTheme.colorScheme.primary)
                    SummaryTile(Modifier.weight(1f).fillMaxHeight(), tr(R.string.str_0403), "${confirmed.size}", red)
                    SummaryTile(
                        Modifier.weight(1f).fillMaxHeight(), tr(R.string.str_0404),
                        maxSpeed?.let { "${Units.spdI(it)}" } ?: "—",
                        if (maxSpeed != null && maxSpeed > limit) red else MaterialTheme.colorScheme.secondary
                    )
                }
            }
            if (measured.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tr(R.string.str_0405), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(tr(R.string.str_0406, (rate * 100).roundToInt(), confirmed.size, measured.size), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = if (confirmed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else red)
                        }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            if (rate > 0f) Box(Modifier.fillMaxWidth(rate.coerceIn(0.02f, 1f)).fillMaxHeight().background(red))
                        }
                        if (uncertainCount > 0) {
                            Text(
                                tr(R.string.str_0407, uncertainCount),
                                style = MaterialTheme.typography.bodySmall, color = AMBER
                            )
                        }
                    }
                }
            }

            // ---------- فلتر + تصدير ----------
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!onlyOver, { onlyOver = false }, { Text(tr(R.string.str_0408, all.size)) })
                    FilterChip(
                        onlyOver, { onlyOver = true }, { Text(tr(R.string.str_0409, over.size)) },
                        leadingIcon = { Icon(Icons.Filled.Warning, null, Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = red.copy(alpha = 0.15f), selectedLabelColor = red, selectedLeadingIconColor = red
                        )
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { CsvExporter.build(ctx, list, s.axisHorizontal) }
                            CsvExporter.share(ctx, f)
                        }
                    }, modifier = Modifier.weight(1f), contentPadding = BtnPadding) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); BtnText(tr(R.string.str_0152)) }
                    OutlinedButton(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                CsvExporter.saveToDownloads(ctx, CsvExporter.build(ctx, list, s.axisHorizontal))
                            }
                            Toast.makeText(ctx, if (ok) tr(R.string.str_0153) else tr(R.string.str_0154), Toast.LENGTH_LONG).show()
                        }
                    }, modifier = Modifier.weight(1f), contentPadding = BtnPadding) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); BtnText(tr(R.string.str_0155)) }
                }
            }

            // ---------- حذف ----------
            item {
                AppCard {
                    SectionTitle(Icons.Filled.Delete, tr(R.string.str_0410))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f), contentPadding = BtnPadding,
                            enabled = list.isNotEmpty(), onClick = { confirm = 1 },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = red)
                        ) { BtnText(tr(R.string.str_0411, list.size)) }
                        Button(
                            modifier = Modifier.weight(1f), contentPadding = BtnPadding,
                            enabled = allRecords.isNotEmpty(), onClick = { confirm = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = red)
                        ) { BtnText(tr(R.string.str_0412, allRecords.size)) }
                    }
                    Text(
                        tr(R.string.str_0413),
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
                                if (onlyOver) tr(R.string.str_0414, limitText(limit)) else tr(R.string.str_0415),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (onlyOver) tr(R.string.str_0416) else tr(R.string.str_0417),
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
                            tr(R.string.str_0418, MAX_ROWS, list.size),
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
                            Icon(Icons.Filled.Warning, if (isOver) tr(R.string.str_0419) else tr(R.string.str_0420), tint = flag, modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        if (r.plateDigits.isNotEmpty()) PlateParser.display(r.plateLetters, r.plateDigits) else tr(R.string.str_0159),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.plateDigits.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        fmt.format(Date(r.timestamp)) + when {
                            isOver -> tr(R.string.str_0421, Units.spdI(r.speedKmh - limit))
                            isUncertain -> tr(R.string.str_0422)
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
                            Text(tr(R.string.str_0423), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
                            Text("${Units.spdI(r.speedKmh)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                if (r.speedErr >= 0.5f) tr(R.string.str_0424, Units.spdI(r.speedErr)) else tr(R.string.str_0161),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                } else {
                    Text(tr(R.string.str_0162), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Delete, tr(R.string.str_0163), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun QualityTile(modifier: Modifier, label: String, count: Int, color: Color) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** مؤشر ثقة قراءة السرعة: أخضر عالية، كهرماني متوسطة، رمادي ضعيفة (مش أحمر عشان ما يتلخبطش مع التجاوز). */
@Composable
private fun ConfidenceTag(conf: Int) {
    val (color, label) = when {
        conf >= 75 -> Color(0xFF2E7D32) to tr(R.string.str_0398)
        conf >= MIN_RELIABLE -> Color(0xFF2E7D32) to tr(R.string.str_0425)
        conf >= 50 -> Color(0xFFD98200) to tr(R.string.str_0399)
        else -> MaterialTheme.colorScheme.onSurfaceVariant to tr(R.string.str_0400)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            tr(R.string.str_0426, conf, label),
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
        title = { Text(tr(R.string.str_0423)) },
        text = {
            if (bmp != null) {
                Image(
                    bmp.asImageBitmap(), null,
                    Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(tr(R.string.str_0427))
            }
        },
        confirmButton = {
            if (bmp != null) TextButton(onClick = { Evidence.share(ctx, path) }) { BtnText(tr(R.string.str_0428)) }
        },
        dismissButton = { TextButton(onClick = onClose) { BtnText(tr(R.string.str_0002)) } }
    )
}

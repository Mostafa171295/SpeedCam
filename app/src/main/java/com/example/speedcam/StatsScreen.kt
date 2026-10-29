package com.example.speedcam

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

private fun percentile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
    return sorted[idx]
}

@Composable
private fun MetricTile(modifier: Modifier, icon: ImageVector, label: String, value: String, unit: String, tint: Color) {
    Card(
        modifier, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.13f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun StatsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by vm.records.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val anomalies by vm.anomalies.collectAsStateWithLifecycle()
    var range by remember { mutableIntStateOf(2) }   // 0 اليوم، 1 آخر 7 أيام، 2 الكل
    var confirmClear by remember { mutableStateOf(false) }
    var groupDay by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.getDefault()) }

    val cutoff = when (range) {
        0 -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        1 -> System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        else -> 0L
    }
    val list = all.filter { it.timestamp >= cutoff }
    val total = list.size
    val rawSpeeds = list.filter { it.speedKmh >= 0 }.map { it.speedKmh }.sorted()
    val speeds = RobustStats.filterSorted(rawSpeeds, settings.dropOutliers)
    val droppedSpeeds = rawSpeeds.size - speeds.size
    val primary = MaterialTheme.colorScheme.primary
    val amber = MaterialTheme.colorScheme.tertiary

    Column(Modifier.fillMaxSize()) {
        AppHeader("الإحصائيات", "تحليل حركة المرور من القياسات المسجلة")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(range == 0, { range = 0 }, { Text("اليوم") })
                    FilterChip(range == 1, { range = 1 }, { Text("آخر 7 أيام") })
                    FilterChip(range == 2, { range = 2 }, { Text("الكل") })
                }
            }

            item {
                FilterChip(
                    settings.dropOutliers,
                    { vm.update { it.copy(dropOutliers = !settings.dropOutliers) } },
                    { Text("استبعاد القراءات الشاذة (أبعد من 3 × MAD عن الوسيط)") }
                )
            }

            item { TrafficMetricsCard(list, settings) }
            item { AnomalyCard(anomalies) { vm.clearAnomalies() } }

            if (total == 0) {
                item {
                    AppCard {
                        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Traffic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                            Text("ابدأ أول قياس", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("شغّل القياس من تبويب الكاميرا وستظهر الإحصائيات هنا.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricTile(Modifier.weight(1f), Icons.Filled.DirectionsCar, "المركبات", "$total", "", Color(0xFF2979FF))
                            MetricTile(Modifier.weight(1f), Icons.Filled.Speed, "المتوسط", if (speeds.isEmpty()) "—" else "${speeds.average().roundToInt()}", "كم/س", Color(0xFF00A58E))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MetricTile(Modifier.weight(1f), Icons.Filled.Timeline, "سرعة V85", if (speeds.isEmpty()) "—" else "${percentile(speeds, 0.85).roundToInt()}", "كم/س", Color(0xFFD98200))
                            MetricTile(Modifier.weight(1f), Icons.Filled.Warning, "الأعلى", if (speeds.isEmpty()) "—" else "${speeds.last().roundToInt()}", "كم/س", Color(0xFFE53935))
                        }
                        Text(
                            "V85: السرعة التي يقل عنها 85% من المركبات، ومعيار شائع في دراسات المرور." +
                                (if (droppedSpeeds > 0) "  (اتستبعدت $droppedSpeeds قراءة شاذة من حساب السرعات)" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                item {
                    val parts = VehicleType.entries.map { it to list.count { r -> r.vehicleType == it.name } }.filter { it.second > 0 }
                    AppCard {
                        SectionTitle(Icons.Filled.DirectionsCar, "حسب نوع المركبة")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.fillMaxSize()) {
                                    val sw = 26.dp.toPx()
                                    var start = -90f
                                    parts.forEach { (t, n) ->
                                        val sweep = 360f * n / total
                                        drawArc(
                                            color = Color(t.color), startAngle = start, sweepAngle = max(sweep - 2f, 0.5f),
                                            useCenter = false, topLeft = Offset(sw / 2, sw / 2),
                                            size = GSize(size.width - sw, size.height - sw), style = Stroke(sw)
                                        )
                                        start += sweep
                                    }
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$total", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text("مركبة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                parts.forEach { (t, n) ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(t.color)))
                                        Text(t.ar, Modifier.weight(1f))
                                        Text("$n (${(n * 100f / total).roundToInt()}%)", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        parts.forEach { (t, _) ->
                            val sp = RobustStats.filterSorted(
                                list.filter { it.vehicleType == t.name && it.speedKmh >= 0 }.map { it.speedKmh }.sorted(),
                                settings.dropOutliers
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(Color(t.color)))
                                Text(t.ar, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                Text(
                                    if (sp.isEmpty()) "عدّ فقط (بدون سرعة)" else "متوسط ${sp.average().roundToInt()}  •  V85 ${percentile(sp, 0.85).roundToInt()}  كم/س",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item {
                    val d1 = list.count { it.direction == 1 }
                    val d2 = list.count { it.direction == 2 }
                    AppCard {
                        SectionTitle(Icons.Filled.SwapHoriz, "حسب الاتجاه")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(1 to d1, 2 to d2).forEach { (d, n) ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text("$n", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text(directionLabel(d, settings.axisHorizontal), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                item {
                    val buckets = bucketize(list, groupDay)
                    val periodMs = if (groupDay) 86_400_000.0 else 3_600_000.0
                    val span = if (buckets.isEmpty()) 1 else max(1, ((buckets.first().startMs - buckets.last().startMs) / periodMs).roundToInt() + 1)
                    val avgPer = total.toFloat() / span
                    val peakB = buckets.maxByOrNull { it.total }
                    AppCard {
                        SectionTitle(Icons.Filled.Timeline, "حجم المرور")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!groupDay, { groupDay = false }, { Text("بالساعة") })
                            FilterChip(groupDay, { groupDay = true }, { Text("باليوم") })
                        }
                        Text(
                            "المتوسط: ${String.format(Locale.US, "%.1f", avgPer)} مركبة ${if (groupDay) "في اليوم" else "في الساعة"}" +
                                (peakB?.let { "   |   الذروة: ${it.total} مركبة (${bucketLabel(it.startMs, groupDay)})" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        buckets.take(48).forEach { b ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(bucketLabel(b.startMs, groupDay), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Text("${b.total}", fontWeight = FontWeight.Bold)
                                    Text(" مركبة", style = MaterialTheme.typography.bodySmall)
                                }
                                Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
                                    VehicleType.entries.forEach { t ->
                                        val n = b.counts[t.ordinal]
                                        if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(Color(t.color)))
                                    }
                                }
                                Text(
                                    VehicleType.entries.filter { b.counts[it.ordinal] > 0 }
                                        .joinToString("  •  ") { "${it.ar} ${b.counts[it.ordinal]}" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (buckets.size > 48) {
                            Text("يعرض أحدث 48 فترة، والتقرير المُصدَّر فيه الكل.", style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { CsvExporter.buildVolume(ctx, buckets, groupDay) }
                                CsvExporter.share(ctx, f)
                            }
                        }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("تصدير تقرير الحجم Excel") }
                    }
                }

                item {
                    val hours = IntArray(24)
                    list.forEach {
                        val c = Calendar.getInstance().apply { timeInMillis = it.timestamp }
                        hours[c.get(Calendar.HOUR_OF_DAY)]++
                    }
                    val maxH = max(1, hours.max())
                    val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
                    AppCard {
                        SectionTitle(Icons.Filled.Schedule, "الحركة حسب ساعة اليوم")
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                                val bw = size.width / 24f
                                hours.forEachIndexed { i, c ->
                                    val h = size.height * c / maxH
                                    drawRoundRect(
                                        color = if (i == peak && c > 0) amber else primary.copy(alpha = 0.55f),
                                        topLeft = Offset(i * bw + 3f, size.height - max(h, 2f)),
                                        size = GSize(bw - 6f, max(h, 2f)), cornerRadius = CornerRadius(6f)
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                listOf("0", "6", "12", "18", "23").forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        if (hours[peak] > 0) {
                            Text("ذروة الحركة: الساعة $peak:00 (${hours[peak]} مركبة)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { CsvExporter.build(ctx, list, settings.axisHorizontal) }
                            CsvExporter.share(ctx, f)
                        }
                    }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("مشاركة Excel") }
                    OutlinedButton(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                CsvExporter.saveToDownloads(ctx, CsvExporter.build(ctx, list, settings.axisHorizontal))
                            }
                            Toast.makeText(ctx, if (ok) "تم الحفظ في مجلد التنزيلات" else "فشل الحفظ", Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("حفظ في التنزيلات") }
                    IconButton(enabled = all.isNotEmpty(), onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, "مسح الكل") }
                }
            }

            if (total > 0) {
                item { Text("آخر القراءات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(list.take(200), key = { it.id }) { r ->
                    val type = VehicleType.fromName(r.vehicleType)
                    Card(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(type?.color ?: 0xFF888888)))
                            Column(Modifier.weight(1f)) {
                                Text(type?.ar ?: r.vehicleType, fontWeight = FontWeight.Bold)
                                Text(
                                    "${fmt.format(Date(r.timestamp))}  •  ${directionLabel(r.direction, settings.axisHorizontal)}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (r.speedKmh >= 0) {
                                Text("${r.speedKmh.roundToInt()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("كم/س", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("عدّ فقط", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.delete(r) }) { Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح كل البيانات؟") },
            text = { Text("سيتم حذف كل القراءات نهائياً.") },
            confirmButton = { TextButton({ vm.clearAll(); confirmClear = false }) { Text("مسح") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("إلغاء") } }
        )
    }
}

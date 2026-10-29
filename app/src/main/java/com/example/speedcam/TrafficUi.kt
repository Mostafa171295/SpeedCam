package com.example.speedcam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

private fun f1(v: Double): String = String.format(Locale.US, "%.1f", v)

/** مؤشرات هندسة المرور من السجلات: تدفق، PCE، ساعة الذروة، الفجوات الزمنية، مستوى الخدمة، وإحصاء كل حارة. */
@Composable
fun TrafficMetricsCard(list: List<SpeedRecord>, s: AppSettings) {
    val metrics = remember(list, s.laneCount, s.laneCapacity) { TrafficMetrics.compute(list, s.laneCount, s.laneCapacity) }
    val lanes = remember(list, s.dropOutliers) { TrafficMetrics.laneStats(list, s.dropOutliers) }
    AppCard {
        SectionTitle(Icons.Filled.BarChart, "مؤشرات هندسة المرور")
        if (metrics.isEmpty()) {
            Text("مفيش قياسات في الفترة دي.", style = MaterialTheme.typography.bodySmall)
        }
        metrics.forEach { m ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(directionLabel(m.dir, s.axisHorizontal) + "  —  ${m.count} مركبة", fontWeight = FontWeight.Bold)
                Text("التدفق المتوسط: ${m.flowVehH.toInt()} مركبة/س  (${m.flowPcuH.toInt()} PCE/س)", style = MaterialTheme.typography.bodyMedium)
                if (m.peak15RatePcuH != null) {
                    Text("أعلى 15 دقيقة (×4): ${m.peak15RatePcuH.toInt()} PCE/س", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("أعلى 15 دقيقة: محتاج 15 دقيقة قياس على الأقل.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (m.phf != null) "معامل ساعة الذروة PHF: ${String.format(Locale.US, "%.2f", m.phf)}" else "معامل ساعة الذروة: محتاج ساعة قياس على الأقل.",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (m.vc != null && m.los != null) {
                    Text(
                        "التدفق ÷ السعة = ${String.format(Locale.US, "%.2f", m.vc)}  →  مستوى الخدمة ${m.los}",
                        fontWeight = FontWeight.Bold,
                        color = if (m.vc > 0.93) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                if (m.meanHeadway != null && m.medianHeadway != null && m.shortHeadwayPct != null) {
                    Text(
                        "الفجوة الزمنية بين المركبات: متوسط ${f1(m.meanHeadway)} ث • وسيط ${f1(m.medianHeadway)} ث • ${m.shortHeadwayPct.toInt()}% أقل من ثانيتين",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (lanes.isNotEmpty()) {
            Text("لكل حارة", fontWeight = FontWeight.Bold)
            lanes.forEach { l ->
                Text(
                    "حارة ${l.lane}: ${l.count} مركبة" +
                        (if (l.meanKmh != null) " • متوسط ${l.meanKmh.toInt()} كم/س" else "") +
                        (if (l.v85 != null) " • V85 ${l.v85.toInt()}" else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else if (metrics.isNotEmpty()) {
            Text("إحصاء الحارات بيظهر لما تفعّل تحويل المنظور.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "ملاحظات: PCE بقيم استرشادية (ملاكي 1، ميكروباص 1.5، توك توك 0.8، موتوسيكل 0.4، أتوبيس ونقل 2). " +
                "مستوى الخدمة تقدير مبدئي بنسبة التدفق للسعة بحدود شائعة، ومش بديل لحساب HCM الكامل. " +
                "الحارات متساوية العرض بين جنبي الطريق المعلّم، والتدفق على فترة القياس الفعلية (لو القياس اتوقف بيقل).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** آخر الحالات غير العادية: عكس اتجاه، مركبة واقفة، ازدحام، سرعة عالية جداً. */
@Composable
fun AnomalyCard(items: List<AnomalyRecord>, onClear: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()) }
    AppCard {
        SectionTitle(Icons.Filled.Warning, "حالات غير عادية (${items.size})")
        if (items.isEmpty()) {
            Text(
                "مفيش حالات متسجلة. فعّل التنبيهات من الإعدادات (عكس الاتجاه، مركبة واقفة، ازدحام، سرعة عالية جداً).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            items.take(30).forEach { a ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmt.format(a.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(a.kind, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text(a.details, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (items.size > 30) Text("(معروض آخر 30 حالة)", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onClear) { Text("مسح السجل") }
        }
    }
}

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
        SectionTitle(Icons.Filled.BarChart, tr(R.string.str_0364))
        if (metrics.isEmpty()) {
            Text(tr(R.string.str_0365), style = MaterialTheme.typography.bodySmall)
        }
        metrics.forEach { m ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(directionLabel(m.dir, s.axisHorizontal) + tr(R.string.str_0366, m.count), fontWeight = FontWeight.Bold)
                Text(tr(R.string.str_0367, m.flowVehH.toInt(), m.flowPcuH.toInt()), style = MaterialTheme.typography.bodyMedium)
                if (m.peak15RatePcuH != null) {
                    Text(tr(R.string.str_0368, m.peak15RatePcuH.toInt()), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(tr(R.string.str_0369), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (m.phf != null) tr(R.string.str_0370, String.format(Locale.US, "%.2f", m.phf)) else tr(R.string.str_0371),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (m.vc != null && m.los != null) {
                    Text(
                        tr(R.string.str_0372, String.format(Locale.US, "%.2f", m.vc), m.los),
                        fontWeight = FontWeight.Bold,
                        color = if (m.vc > 0.93) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
                if (m.meanHeadway != null && m.medianHeadway != null && m.shortHeadwayPct != null) {
                    Text(
                        tr(R.string.str_0373, f1(m.meanHeadway), f1(m.medianHeadway), m.shortHeadwayPct.toInt()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        if (lanes.isNotEmpty()) {
            Text(tr(R.string.str_0374), fontWeight = FontWeight.Bold)
            lanes.forEach { l ->
                Text(
                    tr(R.string.str_0375, l.lane, l.count) +
                        (if (l.meanKmh != null) tr(R.string.str_0376, Units.spdI(l.meanKmh)) else "") +
                        (if (l.v85 != null) " • V85 ${Units.spdI(l.v85)}" else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else if (metrics.isNotEmpty()) {
            Text(tr(R.string.str_0377), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            tr(R.string.str_0378) +
                tr(R.string.str_0379) +
                tr(R.string.str_0380),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** آخر الحالات غير العادية: عكس اتجاه، مركبة واقفة، ازدحام، سرعة عالية جداً. */
@Composable
fun AnomalyCard(items: List<AnomalyRecord>, onClear: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault()) }
    AppCard {
        SectionTitle(Icons.Filled.Warning, tr(R.string.str_0381, items.size))
        if (items.isEmpty()) {
            Text(
                tr(R.string.str_0382),
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
            if (items.size > 30) Text(tr(R.string.str_0383), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onClear) { BtnText(tr(R.string.str_0384)) }
        }
    }
}

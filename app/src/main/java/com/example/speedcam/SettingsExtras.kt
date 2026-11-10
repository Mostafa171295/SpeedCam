package com.example.speedcam

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
    }
}

private val SHUTTERS = listOf(30, 60, 125, 250, 500, 1000)
private val ISOS = listOf(100, 200, 400, 800, 1600, 3200)

/** تحويل المنظور في الإعدادات (التفعيل والمسافة؛ النقط بتتضبط من الكاميرا). */
@Composable
fun PerspectiveSettingsCard(vm: MainViewModel, s: AppSettings) {
    AppCard {
        SectionTitle(Icons.Filled.Visibility, tr(R.string.str_0194))
        PerspectivePanel(s, vm::update, editorHint = false)
    }
}

/** البند 6: التصوير في الضوء الضعيف. */
@Composable
fun LowLightCard(vm: MainViewModel, s: AppSettings) {
    val caps by vm.cameraCaps.collectAsStateWithLifecycle()
    AppCard {
        SectionTitle(Icons.Filled.Visibility, tr(R.string.str_0195))
        Text(
            tr(R.string.str_0196),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ToggleRow(tr(R.string.str_0197), s.lockFps, enabled = !s.manualExp) { v -> vm.update { it.copy(lockFps = v) } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 24, 30).forEach { f ->
                FilterChip(s.targetFps == f, { vm.update { it.copy(targetFps = f) } }, { Text("$f FPS") })
            }
        }
        if (caps.ready && caps.fpsRanges.isNotEmpty()) {
            Text(tr(R.string.str_0198, caps.fpsRanges), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (caps.evMax > caps.evMin) {
            val ev = s.evIndex.coerceIn(caps.evMin, caps.evMax)
            Text(tr(R.string.str_0199, ev) + if (caps.evStep > 0f) tr(R.string.str_0200, String.format(Locale.US, "%.2f", caps.evStep)) else "")
            Slider(
                value = ev.toFloat(),
                onValueChange = { v -> vm.update { it.copy(evIndex = v.roundToInt()) } },
                valueRange = caps.evMin.toFloat()..caps.evMax.toFloat(),
                steps = (caps.evMax - caps.evMin - 1).coerceAtLeast(0),
                enabled = !s.manualExp
            )
        } else {
            Text(tr(R.string.str_0201), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ToggleRow(tr(R.string.str_0202), s.manualExp, enabled = caps.manualSupported) { v -> vm.update { it.copy(manualExp = v) } }
        if (caps.ready && !caps.manualSupported) {
            Text(tr(R.string.str_0203), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (s.manualExp && caps.manualSupported) {
            Text(tr(R.string.str_0204), fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SHUTTERS.forEach { d -> FilterChip(s.shutterDenom == d, { vm.update { it.copy(shutterDenom = d) } }, { Text("1/$d") }) }
            }
            Text(tr(R.string.str_0205, caps.isoMin, caps.isoMax), fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ISOS.filter { it in caps.isoMin..caps.isoMax }.forEach { i -> FilterChip(s.isoManual == i, { vm.update { it.copy(isoManual = i) } }, { Text("$i") }) }
            }
            Text(
                tr(R.string.str_0206),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            tr(R.string.str_0207),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** البند 7: الثبات والتشغيل لساعات. */
@Composable
fun StabilityCard(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val health by vm.health.collectAsStateWithLifecycle()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    AppCard {
        SectionTitle(Icons.Filled.Info, tr(R.string.str_0208))
        ToggleRow(tr(R.string.str_0209), s.bgService) { on ->
            vm.update { it.copy(bgService = on) }
            if (on && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch("android.permission.POST_NOTIFICATIONS")
        }
        Text(
            tr(R.string.str_0210) +
                tr(R.string.str_0211),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = {
            try {
                ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
                Toast.makeText(ctx, tr(R.string.str_0212), Toast.LENGTH_LONG).show()
            }
        }, modifier = Modifier.fillMaxWidth()) { BtnText(tr(R.string.str_0213), maxLines = 2) }

        ToggleRow(tr(R.string.str_0214), s.thermalGuard) { v -> vm.update { it.copy(thermalGuard = v) } }
        Text(
            tr(R.string.str_0215),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (health.batteryPct >= 0) {
            Text(
                tr(R.string.str_0216, health.tempC.toInt(), health.batteryPct) + if (health.charging) tr(R.string.str_0217) else "",
                fontWeight = FontWeight.Bold,
                color = if (health.level >= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }

        Text(tr(R.string.str_0218), fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1000, 5000, 20000, 100000, 0).forEach { m ->
                FilterChip(s.maxRecords == m, { vm.update { it.copy(maxRecords = m) } }, { Text(if (m == 0) tr(R.string.str_0219) else "$m") })
            }
        }
        Text(
            tr(R.string.str_0220),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** إعدادات مؤشرات المرور والتنبيهات (عكس اتجاه، مركبة واقفة، ازدحام، سرعة عالية جداً). */
@Composable
fun TrafficSettingsCard(vm: MainViewModel, s: AppSettings) {
    AppCard {
        SectionTitle(Icons.Filled.Info, tr(R.string.str_0221))

        Text(tr(R.string.str_0222), fontWeight = FontWeight.Bold)
        ChipRow { listOf(1, 2, 3, 4).forEach { n -> FilterChip(s.laneCount == n, { vm.update { it.copy(laneCount = n) } }, { Text("$n") }) } }

        Text(tr(R.string.str_0223), fontWeight = FontWeight.Bold)
        ChipRow { listOf(1500, 1800, 2000, 2200).forEach { c -> FilterChip(s.laneCapacity == c, { vm.update { it.copy(laneCapacity = c) } }, { Text("$c") }) } }

        Text(tr(R.string.str_0224), fontWeight = FontWeight.Bold)
        ChipRow {
            FilterChip(s.allowedDir == 0, { vm.update { it.copy(allowedDir = 0) } }, { Text(tr(R.string.str_0225)) })
            FilterChip(s.allowedDir == 1, { vm.update { it.copy(allowedDir = 1) } }, { Text(directionLabel(1, s.axisHorizontal)) })
            FilterChip(s.allowedDir == 2, { vm.update { it.copy(allowedDir = 2) } }, { Text(directionLabel(2, s.axisHorizontal)) })
        }

        Text(tr(R.string.str_0226), fontWeight = FontWeight.Bold)
        ChipRow { listOf(0, 5, 10, 20, 30).forEach { v -> FilterChip(s.stopSec == v, { vm.update { it.copy(stopSec = v) } }, { Text(if (v == 0) tr(R.string.str_0058) else "$v") }) } }

        Text(tr(R.string.str_0227), fontWeight = FontWeight.Bold)
        ChipRow { speedPresets(listOf(0, 10, 15, 20, 30), listOf(0, 5, 10, 15, 20)).forEach { (shown, kmh) -> FilterChip(s.congestionKmh == kmh, { vm.update { it.copy(congestionKmh = kmh) } }, { Text(if (shown == 0) tr(R.string.str_0058) else "$shown") }) } }

        Text(tr(R.string.str_0228), fontWeight = FontWeight.Bold)
        ChipRow { speedPresets(listOf(0, 80, 100, 120, 140), listOf(0, 50, 60, 75, 90)).forEach { (shown, kmh) -> FilterChip(s.alertSpeed == kmh, { vm.update { it.copy(alertSpeed = kmh) } }, { Text(if (shown == 0) tr(R.string.str_0058) else "$shown") }) } }

        Text(
            tr(R.string.str_0229) +
                tr(R.string.str_0230),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** مصدر الصورة: كاميرا الموبايل أو كاميرا على الشبكة (MJPEG عبر HTTP). */
@Composable
fun SourceCard(vm: MainViewModel, s: AppSettings) {
    val status by vm.host.streamStatus.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(s.streamUrl) }
    AppCard {
        SectionTitle(Icons.Filled.Visibility, tr(R.string.str_0231))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.sourceMode == 0, { vm.update { it.copy(sourceMode = 0) } }, { Text(tr(R.string.str_0232)) })
            FilterChip(s.sourceMode == 1, { vm.update { it.copy(sourceMode = 1) } }, { Text(tr(R.string.str_0233)) })
        }
        if (s.sourceMode == 1) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(tr(R.string.str_0234)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { vm.update { it.copy(streamUrl = url.trim()) } }) { BtnText(tr(R.string.str_0235)) }
            Text(tr(R.string.str_0236) + status.ifBlank { "—" }, fontWeight = FontWeight.Bold)
            Text(
                tr(R.string.str_0237) +
                    tr(R.string.str_0238),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                tr(R.string.str_0239) +
                    tr(R.string.str_0240),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
            Text(
                tr(R.string.str_0241),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** اختيارات سريعة للسرعة: (القيمة المعروضة، القيمة المخزّنة بالكم/س). في الوضع الإنجليزي بتتعرض بالميل/س. */
private fun speedPresets(metric: List<Int>, imperial: List<Int>): List<Pair<Int, Int>> =
    if (Units.imperial) imperial.map { it to (if (it == 0) 0 else Units.toKmh(it).toInt()) } else metric.map { it to it }

/** جودة الصورة والدقة: وضوح حي، قفل تثبيت الفيديو، تثبيت الفوكس، الزووم، و1080p. */
@Composable
fun ImageQualityCard(vm: MainViewModel, s: AppSettings) {
    val caps by vm.cameraCaps.collectAsStateWithLifecycle()
    val iq by vm.engine.quality.state.collectAsStateWithLifecycle()
    val hint = MaterialTheme.typography.bodySmall
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    AppCard {
        SectionTitle(Icons.Filled.Visibility, tr(R.string.iq_card_title))
        ImageQualityBadge(iq)

        ToggleRow(tr(R.string.iq_stab_off), s.stabOff) { v -> vm.update { it.copy(stabOff = v) } }
        Text(tr(R.string.iq_stab_hint), style = hint, color = hintColor)

        ToggleRow(tr(R.string.iq_focus_lock), s.focusLock) { v -> vm.update { it.copy(focusLock = v) } }
        Button(onClick = { vm.host.refocus() }, modifier = Modifier.fillMaxWidth()) { BtnText(tr(R.string.iq_focus_now)) }
        Text(tr(R.string.iq_focus_hint), style = hint, color = hintColor)

        if (caps.maxZoom > 1.05f) {
            val z = s.zoom.coerceIn(1f, caps.maxZoom)
            Text(tr(R.string.iq_zoom, String.format(Locale.US, "%.1f", z)))
            Slider(
                value = z, valueRange = 1f..caps.maxZoom,
                onValueChange = { v -> vm.update { it.copy(zoom = (v * 10f).roundToInt() / 10f) } }
            )
            Text(tr(R.string.iq_zoom_hint), style = hint, color = hintColor)
        }

        ToggleRow(tr(R.string.iq_fullhd), s.fullHd) { v -> vm.update { it.copy(fullHd = v) } }

        if (caps.hqDistortion) {
            ToggleRow(tr(R.string.iq_isp), s.ispDistortion) { v -> vm.update { it.copy(ispDistortion = v) } }
            Text(tr(R.string.iq_isp_hint), style = hint, color = hintColor)
        }
    }
}

/** منطقة الكشف: قص تلقائي حوالين الخطين + حدود يدوية، ومؤشر كام مرة التفاصيل بتزيد. */
@Composable
fun DetectionZoneCard(vm: MainViewModel, s: AppSettings) {
    // الـ overlay بيتحدّث كل فريم: بناخد منه نسبة الصورة بس عشان الكارت ما يتعاد رسمه كل فريم
    val ovState = vm.engine.overlay.collectAsStateWithLifecycle()
    val aspect by remember { derivedStateOf { ovState.value.aspect } }
    val hint = MaterialTheme.typography.bodySmall
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    val zone = DetectionZone.compute(s, aspect)
    val gain = DetectionZone.gain(zone, aspect)

    @Composable
    fun Edge(label: String, v: Float, onChange: (Float) -> Unit) {
        Text(label + ": " + (v * 100f).roundToInt() + "%")
        Slider(value = v, valueRange = 0f..1f, onValueChange = { x -> onChange((x * 100f).roundToInt() / 100f) })
    }

    AppCard {
        SectionTitle(Icons.Filled.Visibility, tr(R.string.dz_title))
        Text(tr(R.string.dz_hint), style = hint, color = hintColor)
        ToggleRow(tr(R.string.dz_auto), s.roiAuto) { v -> vm.update { it.copy(roiAuto = v) } }
        Edge(tr(R.string.dz_left), s.zoneL) { x -> vm.update { it.copy(zoneL = minOf(x, it.zoneR - 0.2f).coerceAtLeast(0f)) } }
        Edge(tr(R.string.dz_right), s.zoneR) { x -> vm.update { it.copy(zoneR = maxOf(x, it.zoneL + 0.2f).coerceAtMost(1f)) } }
        Edge(tr(R.string.dz_top), s.zoneT) { x -> vm.update { it.copy(zoneT = minOf(x, it.zoneB - 0.2f).coerceAtLeast(0f)) } }
        Edge(tr(R.string.dz_bottom), s.zoneB) { x -> vm.update { it.copy(zoneB = maxOf(x, it.zoneT + 0.2f).coerceAtMost(1f)) } }
        Text(
            if (zone == null) tr(R.string.dz_full)
            else tr(R.string.dz_gain, DetectionZone.areaPct(zone), String.format(Locale.US, "%.1f", gain)),
            style = hint, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        OutlinedButton(
            onClick = { vm.update { it.copy(zoneL = 0f, zoneT = 0f, zoneR = 1f, zoneB = 1f) } },
            modifier = Modifier.fillMaxWidth()
        ) { BtnText(tr(R.string.dz_reset)) }
    }
}

/** بيانات التدريب: جمع لقطات مع صناديق الكاشف وتصديرها كحزمة YOLO لتدريب موديل خاص بعربيات بلدك. */
@Composable
fun TrainingDataCard(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by vm.engine.collector.state.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val hint = MaterialTheme.typography.bodySmall
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    AppCard {
        SectionTitle(Icons.Filled.Visibility, tr(R.string.tr_title))
        Text(tr(R.string.tr_hint), style = hint, color = hintColor)
        ToggleRow(tr(R.string.tr_on), s.collectOn) { v -> vm.update { it.copy(collectOn = v) } }

        Text(tr(R.string.tr_mode))
        ChipRow {
            FilterChip(s.collectMode == 1, { vm.update { it.copy(collectMode = 1) } }, { Text(tr(R.string.tr_mode_unc)) })
            FilterChip(s.collectMode == 0, { vm.update { it.copy(collectMode = 0) } }, { Text(tr(R.string.tr_mode_all)) })
        }
        Text(tr(R.string.tr_every))
        ChipRow {
            listOf(2, 5, 10, 30).forEach { v ->
                FilterChip(s.collectEverySec == v, { vm.update { it.copy(collectEverySec = v) } }, { Text("$v " + tr(R.string.tr_sec)) })
            }
        }
        Text(tr(R.string.tr_max))
        ChipRow {
            listOf(100, 300, 1000).forEach { v ->
                FilterChip(s.collectMax == v, { vm.update { it.copy(collectMax = v) } }, { Text("$v") })
            }
        }

        Text(
            tr(R.string.tr_status, st.count, String.format(Locale.US, "%.1f", st.bytes / 1048576.0)) +
                (if (st.full) "  " + tr(R.string.tr_full) else ""),
            style = hint, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = st.count > 0 && !busy, modifier = Modifier.weight(1f), contentPadding = BtnPadding,
                onClick = {
                    scope.launch {
                        busy = true
                        val f = withContext(Dispatchers.IO) { vm.engine.collector.exportZip() }
                        val ok = f != null && withContext(Dispatchers.IO) { vm.engine.collector.saveZipToDownloads(f) }
                        Toast.makeText(ctx, if (f == null) tr(R.string.tr_none) else if (ok) tr(R.string.str_0153) else tr(R.string.str_0154), Toast.LENGTH_SHORT).show()
                        busy = false
                    }
                }
            ) { BtnText(tr(R.string.tr_save)) }
            OutlinedButton(
                enabled = st.count > 0 && !busy, modifier = Modifier.weight(1f), contentPadding = BtnPadding,
                onClick = {
                    scope.launch {
                        busy = true
                        val f = withContext(Dispatchers.IO) { vm.engine.collector.exportZip() }
                        if (f != null) vm.engine.collector.shareZip(ctx, f) else Toast.makeText(ctx, tr(R.string.tr_none), Toast.LENGTH_SHORT).show()
                        busy = false
                    }
                }
            ) { BtnText(tr(R.string.tr_share)) }
        }
        OutlinedButton(
            enabled = st.count > 0 && !busy, modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!confirm) confirm = true
                else {
                    confirm = false
                    scope.launch { withContext(Dispatchers.IO) { vm.engine.collector.clear() } }
                }
            }
        ) { BtnText(if (confirm) tr(R.string.tr_delete_confirm) else tr(R.string.tr_delete)) }
    }
}

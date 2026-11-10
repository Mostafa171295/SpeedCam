package com.example.speedcam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.abs

private const val STEPS = 5
private val OkGreen = Color(0xFF2E7D32)
private val WarnAmber = Color(0xFFD98200)

/**
 * ويزارد المعايرة: 5 خطوات بالترتيب الصحيح بدل أقسام متفرقة.
 * 1 التثبيت  2 الاتجاه والخطين والمسافة  3 المنظور (اختياري)  4 عينات السرعة  5 المرجع والملخص
 * onPerspMode: بيفتح/يقفل محرر النقط فوق الفيديو (بيتفعّل في خطوة المنظور بس).
 */
@Composable
fun CalibrationWizard(
    vm: MainViewModel,
    s: AppSettings,
    fps: Int,
    onPerspMode: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    var step by rememberSaveable { mutableStateOf(0) }
    val st by vm.engine.stability.state.collectAsStateWithLifecycle()
    val iq by vm.engine.quality.state.collectAsStateWithLifecycle()

    // المراقبة اللحظية للثبات شغالة طول ما الويزارد مفتوح (حتى والقياس واقف)
    DisposableEffect(Unit) {
        vm.engine.stability.watch = true
        onDispose { vm.engine.stability.watch = false }
    }
    // محرر المنظور بيظهر فوق الفيديو في خطوة المنظور بس
    LaunchedEffect(step) { onPerspMode(step == 2) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr(R.string.str_0001, step + 1, STEPS) + stepTitle(step),
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { BtnText(tr(R.string.str_0002)) }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((step + 1) / STEPS.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }

        when (step) {
            0 -> StepMount(st, iq)
            1 -> CalibrationControls(s, vm::update, fps)
            2 -> StepPerspective(vm, s)
            3 -> StepSamples(vm, s)
            else -> StepSummary(vm, s, st)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = step > 0, onClick = { step-- },
                modifier = Modifier.weight(1f), contentPadding = BtnPadding
            ) { BtnText(tr(R.string.str_0003)) }
            if (step < STEPS - 1) {
                Button(onClick = { step++ }, modifier = Modifier.weight(1f), contentPadding = BtnPadding) {
                    BtnText(if (step == 2 && !s.homoOn) tr(R.string.str_0004) else tr(R.string.str_0005))
                }
            } else {
                Button(onClick = onClose, modifier = Modifier.weight(1f), contentPadding = BtnPadding) { BtnText(tr(R.string.str_0006)) }
            }
        }
    }
}

private fun stepTitle(i: Int) = when (i) {
    0 -> tr(R.string.str_0007)
    1 -> tr(R.string.str_0008)
    2 -> tr(R.string.str_0009)
    3 -> tr(R.string.str_0010)
    else -> tr(R.string.str_0011)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StepMount(st: CamStability, iq: ImgQuality) {
    Hint(
        tr(R.string.str_0012) +
            tr(R.string.str_0013)
    )
    if (st.hasRef) StabilityBadge(st) else Hint(tr(R.string.str_0014))
    ImageQualityBadge(iq)
}

@Composable
private fun StepPerspective(vm: MainViewModel, s: AppSettings) {
    Hint(
        tr(R.string.str_0015) +
            tr(R.string.str_0016)
    )
    PerspectivePanel(s, vm::update)
}

@Composable
private fun StepSamples(vm: MainViewModel, s: AppSettings) {
    var gps by rememberSaveable { mutableStateOf(false) }
    Hint(
        tr(R.string.str_0017) +
            tr(R.string.str_0018)
    )
    SpeedCalibrationBox(s, vm::update)
    AccuracyTestBox(vm, s)
    FilterChip(gps, { gps = !gps }, { Text(tr(R.string.str_0019)) })
    if (gps) GpsCalibCard(vm, s)
}

@Composable
private fun StepSummary(vm: MainViewModel, s: AppSettings, st: CamStability) {
    val samples = remember(s.calSamples) { Calibration.parse(s.calSamples) }
    val stats = remember(samples) { Calibration.stats(samples) }
    val linesOk = Geo.active(s) || abs(s.lineA - s.lineB) >= 0.15f
    val dist = String.format(Locale.US, "%.1f", Units.dist(s.distanceM))

    Check(linesOk, if (Geo.active(s)) tr(R.string.str_0020, dist) else tr(R.string.str_0021, dist),
        tr(R.string.str_0022))
    Check(
        samples.size >= 3,
        tr(R.string.str_0023, samples.size) + (stats?.let { " (±" + String.format(Locale.US, "%.1f", Units.spd(it.half95)) + tr(R.string.str_0024) } ?: ""),
        if (samples.isEmpty()) tr(R.string.str_0025) else tr(R.string.str_0026, samples.size)
    )
    Check(st.hasRef, tr(R.string.str_0027), tr(R.string.str_0028))
    val sc by vm.engine.scaleCheck.state.collectAsStateWithLifecycle()
    ScaleCheckBadge(sc)
    if (st.hasRef) StabilityBadge(st)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.engine.stability.captureReference() },
            modifier = Modifier.weight(1f), contentPadding = BtnPadding
        ) { BtnText(if (st.hasRef) tr(R.string.str_0029) else tr(R.string.str_0030)) }
        if (st.hasRef) OutlinedButton(
            onClick = { vm.engine.stability.clearReference() },
            modifier = Modifier.weight(1f), contentPadding = BtnPadding
        ) { BtnText(tr(R.string.str_0031)) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr(R.string.str_0032), Modifier.weight(1f))
        Switch(s.movedGuard, { v -> vm.update { it.copy(movedGuard = v) } })
    }
    Hint(tr(R.string.str_0033))
}

@Composable
private fun Check(ok: Boolean, good: String, bad: String) {
    val c = if (ok) OkGreen else WarnAmber
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (ok) "✓" else "⚠", color = c, fontWeight = FontWeight.Bold)
        Text(if (ok) good else bad, color = c, style = MaterialTheme.typography.bodyMedium)
    }
}

/** شارة ثبات ملونة: أخضر ثابت، كهرماني اهتزاز/إزاحة بسيطة، أحمر اتحركت. */
@Composable
fun StabilityBadge(st: CamStability, modifier: Modifier = Modifier) {
    val c = when (st.level) { 0 -> OkGreen; 1 -> WarnAmber; else -> MaterialTheme.colorScheme.error }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.12f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            tr(R.string.str_0034, st.score) + when (st.level) { 0 -> tr(R.string.str_0035); 1 -> tr(R.string.str_0036); else -> tr(R.string.str_0037) },
            color = c, fontWeight = FontWeight.Bold
        )
        Text(
            tr(R.string.str_0038) + String.format(Locale.US, "%.1f", st.shiftPct) + tr(R.string.str_0039) + String.format(Locale.US, "%.2f", st.jitterPct) + "%",
            style = MaterialTheme.typography.bodySmall
        )
        if (st.message.isNotEmpty()) Text(st.message, style = MaterialTheme.typography.bodySmall)
    }
}

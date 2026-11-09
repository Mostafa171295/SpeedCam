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
                "معايرة موجّهة — الخطوة ${step + 1} من $STEPS: " + stepTitle(step),
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onClose) { BtnText("إغلاق") }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth((step + 1) / STEPS.toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }

        when (step) {
            0 -> StepMount(st)
            1 -> CalibrationControls(s, vm::update, fps)
            2 -> StepPerspective(vm, s)
            3 -> StepSamples(vm, s)
            else -> StepSummary(vm, s, st)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = step > 0, onClick = { step-- },
                modifier = Modifier.weight(1f), contentPadding = BtnPadding
            ) { BtnText("السابق") }
            if (step < STEPS - 1) {
                Button(onClick = { step++ }, modifier = Modifier.weight(1f), contentPadding = BtnPadding) {
                    BtnText(if (step == 2 && !s.homoOn) "تخطي" else "التالي")
                }
            } else {
                Button(onClick = onClose, modifier = Modifier.weight(1f), contentPadding = BtnPadding) { BtnText("تم") }
            }
        }
    }
}

private fun stepTitle(i: Int) = when (i) {
    0 -> "تثبيت الموبايل"
    1 -> "الاتجاه والخطين والمسافة"
    2 -> "المنظور (اختياري)"
    3 -> "عينات السرعة"
    else -> "المرجع والملخص"
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StepMount(st: CamStability) {
    Hint(
        "ثبّت الموبايل على حامل ثابت، ومن مكان مرتفع شوية وبزاوية مايلة على الطريق، وما تحركوش بعد المعايرة خالص. " +
            "أي تحريك بعد كده بيبوّظ المسافة بين الخطين وبالتالي السرعة."
    )
    if (st.hasRef) StabilityBadge(st) else Hint("لسه مفيش مرجع محفوظ للثبات، هتحفظه في آخر خطوة.")
}

@Composable
private fun StepPerspective(vm: MainViewModel, s: AppSettings) {
    Hint(
        "لو الكاميرا بتصوّر الطريق بزاوية مايلة، تحويل المنظور بيدّي سرعة أدق ويفرّق الحارات. " +
            "لو الكاميرا من فوق الطريق مباشرة أو هتقيس حارة واحدة، اضغط \"تخطي\"."
    )
    PerspectivePanel(s, vm::update)
}

@Composable
private fun StepSamples(vm: MainViewModel, s: AppSettings) {
    var gps by rememberSaveable { mutableStateOf(false) }
    Hint(
        "ابدأ القياس بزرار \"بدء\" تحت، واعبر بعربية سرعتها معروفة. محتاج 3 عينات على الأقل بسرعات مختلفة. " +
            "تقدر تتخطى الخطوة دي وتعتمد على الحساب النظري، بس الدقة هتبقى أقل."
    )
    SpeedCalibrationBox(s, vm::update)
    FilterChip(gps, { gps = !gps }, { Text("معايرة تلقائية بالـ GPS") })
    if (gps) GpsCalibCard(vm, s)
}

@Composable
private fun StepSummary(vm: MainViewModel, s: AppSettings, st: CamStability) {
    val samples = remember(s.calSamples) { Calibration.parse(s.calSamples) }
    val stats = remember(samples) { Calibration.stats(samples) }
    val linesOk = Geo.active(s) || abs(s.lineA - s.lineB) >= 0.15f
    val dist = String.format(Locale.US, "%.1f", s.distanceM)

    Check(linesOk, if (Geo.active(s)) "المنظور شغال، المسافة بين الخطين $dist م" else "الخطان متباعدان، المسافة $dist م",
        "الخطان قريبان جداً: باعد بينهما (الخطوة 2)")
    Check(
        samples.size >= 3,
        "عينات المعايرة: ${samples.size}" + (stats?.let { " (±" + String.format(Locale.US, "%.1f", it.half95) + " كم/س)" } ?: ""),
        if (samples.isEmpty()) "مفيش عينات سرعة، الدقة نظرية بس (الخطوة 4)" else "عينات قليلة (${samples.size})، ضيف لحد 3 على الأقل (الخطوة 4)"
    )
    Check(st.hasRef, "مرجع الثبات محفوظ", "لسه ما حفظتش مرجع الثبات، اضغط الزرار تحت")
    if (st.hasRef) StabilityBadge(st)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.engine.stability.captureReference() },
            modifier = Modifier.weight(1f), contentPadding = BtnPadding
        ) { BtnText(if (st.hasRef) "حدّث المرجع" else "احفظ المرجع الآن") }
        if (st.hasRef) OutlinedButton(
            onClick = { vm.engine.stability.clearReference() },
            modifier = Modifier.weight(1f), contentPadding = BtnPadding
        ) { BtnText("امسح المرجع") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("نبّهني لو الكاميرا اتحركت", Modifier.weight(1f))
        Switch(s.movedGuard, { v -> vm.update { it.copy(movedGuard = v) } })
    }
    Hint("احفظ المرجع بعد ما تخلّص المعايرة وهي على الوضع النهائي، وبدون عربيات كتير في الكادر لو أمكن.")
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
            "ثبات الكاميرا: ${st.score}%  •  " + when (st.level) { 0 -> "ثابتة"; 1 -> "اهتزاز/إزاحة بسيطة"; else -> "اتحركت" },
            color = c, fontWeight = FontWeight.Bold
        )
        Text(
            "إزاحة عن المرجع " + String.format(Locale.US, "%.1f", st.shiftPct) + "%  •  اهتزاز " + String.format(Locale.US, "%.2f", st.jitterPct) + "%",
            style = MaterialTheme.typography.bodySmall
        )
        if (st.message.isNotEmpty()) Text(st.message, style = MaterialTheme.typography.bodySmall)
    }
}

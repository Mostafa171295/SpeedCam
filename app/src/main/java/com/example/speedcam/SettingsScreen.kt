package com.example.speedcam

import androidx.compose.foundation.layout.*
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
fun CalibrationControls(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, fps: Int = 0) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (Geo.active(s)) {
        Text(tr(R.string.str_0242), color = MaterialTheme.colorScheme.primary)
      } else {
        Text(tr(R.string.str_0243), fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.axisHorizontal, { update { it.copy(axisHorizontal = true) } }, { Text(tr(R.string.str_0244)) })
            FilterChip(!s.axisHorizontal, { update { it.copy(axisHorizontal = false) } }, { Text(tr(R.string.str_0245)) })
        }
        Text(tr(R.string.str_0246, (s.lineA * 100).roundToInt()))
        Slider(s.lineA, { v -> update { it.copy(lineA = v) } }, valueRange = 0.05f..0.95f)
        Text(tr(R.string.str_0247, (s.lineB * 100).roundToInt()))
        Slider(s.lineB, { v -> update { it.copy(lineB = v) } }, valueRange = 0.05f..0.95f)
        if (abs(s.lineA - s.lineB) < 0.05f) {
            Text(tr(R.string.str_0248), color = MaterialTheme.colorScheme.error)
        }
      }
        var txt by remember { mutableStateOf(Units.field(Units.dist(s.distanceM))) }
        OutlinedTextField(
            value = txt,
            onValueChange = { v ->
                txt = v
                v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let { d -> update { it.copy(distanceM = Units.toMeters(d).toFloat()) } }
            },
            label = { Text(tr(R.string.str_0249)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        AccuracyIndicator(s, fps)
    }
}


/**
 * معايرة السرعة بعربية سرعتها معروفة (عداد/GPS): كل عبور = عينة (حقيقية، خام).
 * عينة أو اتنين → معامل نسبة. تلات عينات فأكتر بسرعات متباعدة → انحدار خطي: حقيقية = a × خام + b.
 */
@Composable
internal fun SpeedCalibrationBox(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    var real by remember { mutableStateOf("") }
    var read by remember { mutableStateOf("") }
    val r = real.replace(',', '.').toFloatOrNull()?.let { Units.toKmh(it).toFloat() }   // بنخزّن بالكم/س دايماً
    val a = read.replace(',', '.').toFloatOrNull()?.let { Units.toKmh(it).toFloat() }
    val samples = remember(s.calSamples) { Calibration.parse(s.calSamples) }
    val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    fun commit(list: List<Calibration.Sample>) {
        val (fa, fb) = Calibration.fit(list)
        update { it.copy(speedCalib = fa, speedOffset = fb, calSamples = Calibration.encode(list)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr(R.string.str_0250) +
                tr(R.string.str_0251) +
                tr(R.string.str_0252),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(real, { real = it }, label = { Text(tr(R.string.str_0253)) }, keyboardOptions = dec, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(read, { read = it }, label = { Text(tr(R.string.str_0254)) }, keyboardOptions = dec, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(modifier = Modifier.weight(1f), contentPadding = BtnPadding, enabled = r != null && a != null && r > 0f && a > 0f, onClick = {
                // نرجّع القراءة المعروضة للقيمة الخام (قبل المعايرة الحالية)
                val raw = (a!! - s.speedOffset) / s.speedCalib
                if (raw > 0f) {
                    commit((samples + Calibration.Sample(r!!, raw)).takeLast(MAX_CAL_SAMPLES))
                    real = ""; read = ""
                }
            }) { BtnText(tr(R.string.str_0255)) }
            OutlinedButton(modifier = Modifier.weight(1f), contentPadding = BtnPadding, enabled = samples.isNotEmpty() || s.speedCalib != 1f || s.speedOffset != 0f, onClick = {
                update { it.copy(speedCalib = 1f, speedOffset = 0f, calSamples = "") }
            }) { BtnText(tr(R.string.str_0256)) }
        }
        val b = s.speedOffset
        Text(
            tr(R.string.str_0257) + String.format(Locale.US, "%.3f", s.speedCalib) + tr(R.string.str_0258) +
                (if (b < 0f) "− " else "+ ") + String.format(Locale.US, "%.2f", Units.spd(abs(b))) + "   (" + samples.size + tr(R.string.str_0259),
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
        )
        val st = remember(samples) { Calibration.stats(samples) }
        if (st != null) {
            Text(
                tr(R.string.str_0260) + String.format(Locale.US, "%.1f", Units.spd(st.half95)) + tr(R.string.str_0261) +
                    String.format(Locale.US, "%.1f", Units.spd(st.rmse)) + tr(R.string.str_0262) + (if (st.dropped > 0) tr(R.string.str_0263, st.dropped) else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold
            )
        } else if (samples.size in 1..2) {
            Text(tr(R.string.str_0264), style = MaterialTheme.typography.bodySmall)
        }
        samples.forEachIndexed { i, smp ->
            val shown = s.calibrate(smp.raw.toDouble())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(R.string.str_0265) + String.format(Locale.US, "%.1f", Units.spd(smp.real)) + tr(R.string.str_0266) + String.format(Locale.US, "%.1f", Units.spd(smp.raw)) +
                        tr(R.string.str_0267) + String.format(Locale.US, "%.1f", Units.spd(shown)),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { commit(samples.filterIndexed { j, _ -> j != i }) }) {
                    Icon(Icons.Filled.Delete, tr(R.string.str_0268))
                }
            }
        }
    }
}

internal const val MAX_CAL_SAMPLES = 30

/** تقدير نظري: الخطأ النسبي ≈ السرعة ÷ (المسافة × FPS) لأن زمن العبور يُقاس بدقة فريم تقريباً. */
@Composable
private fun AccuracyIndicator(s: AppSettings, fps: Int) {
    val assumed = fps <= 0
    val f = if (assumed) 15 else fps
    val v10 = 0.10f * s.distanceM * f * 3.6f
    val v5 = 0.05f * s.distanceM * f * 3.6f
    val moto = 1.2f * 2f * f * 3.6f
    val need80 = ceil(80f / 3.6f / (0.10f * f)).toInt()
    val color = when {
        v10 >= 80f -> Color(0xFF2E7D32)
        v10 >= 50f -> Color(0xFFD98200)
        else -> Color(0xFFD32F2F)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(tr(R.string.str_0269), fontWeight = FontWeight.Bold, color = color)
        Text(tr(R.string.str_0270, Units.spdI(v10)))
        Text(tr(R.string.str_0271, Units.spdI(v5)))
        if (s.fastMode) {
            Text(
                tr(R.string.str_0272),
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(tr(R.string.str_0273, Units.spdI(moto)))
        }
        Text(
            tr(R.string.str_0274, Units.distI(need80)),
            style = MaterialTheme.typography.bodySmall
        )
        val sigma = 1f / (f * 3.4641f)   // انحراف توقيت العبور بسبب تقطيع الفريمات: 1 ÷ (FPS × √12)
        val d5 = 80f / 3.6f * 1.4142f * sigma / 0.05f
        val d10 = 80f / 3.6f * 1.4142f * sigma / 0.10f
        Text(
            tr(R.string.str_0275, Units.distI(ceil(d5)), Units.distI(ceil(d10))),
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            (if (assumed) tr(R.string.str_0276) else tr(R.string.str_0277, f)) +
                tr(R.string.str_0278),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    // FPS بنقراه كل ثانية بس (مش كل فريم) عشان الإعدادات ما تتعيدش رسمها 30 مرة في الثانية والأرقام ما تتغيّرش بشكل غريب
    val fps by produceState(0) {
        while (true) {
            value = vm.engine.overlay.value.fps
            kotlinx.coroutines.delay(1000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        AppHeader(tr(R.string.str_0129), tr(R.string.str_0279))
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LanguageCard()
            AppCard {
                SectionTitle(Icons.Filled.Straighten, tr(R.string.str_0280))
                CalibrationControls(s, vm::update, fps)
            }
            AppCard {
                SectionTitle(Icons.Filled.Straighten, tr(R.string.str_0281))
                SpeedCalibrationBox(s, vm::update)
            }
            AppCard {
                SectionTitle(Icons.Filled.Straighten, tr(R.string.at_title))
                AccuracyTestBox(vm, s)
            }
            AppCard {
                SectionTitle(Icons.Filled.Category, tr(R.string.str_0282))
                SwitchRow(tr(R.string.str_0283), s.sizeRefine) { v -> vm.update { it.copy(sizeRefine = v) } }
                Text(
                    tr(R.string.str_0284) +
                        tr(R.string.str_0285),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(tr(R.string.str_0286, String.format(Locale.US, "%.1f", Units.dist(s.tukMaxLen))))
                Slider(s.tukMaxLen, { v -> vm.update { it.copy(tukMaxLen = v) } }, valueRange = 2f..4f)
                Text(tr(R.string.str_0287, String.format(Locale.US, "%.1f", Units.dist(s.microMinLen))))
                Slider(s.microMinLen, { v -> vm.update { it.copy(microMinLen = v) } }, valueRange = 4f..6.5f)
                Text(tr(R.string.str_0288, String.format(Locale.US, "%.1f", Units.dist(s.busMinLen))))
                Slider(s.busMinLen, { v -> vm.update { it.copy(busMinLen = v) } }, valueRange = 6f..11f)
            }
            AppCard {
                SectionTitle(Icons.Filled.Visibility, tr(R.string.str_0289))
                Text(tr(R.string.str_0290, (s.minScore * 100).roundToInt()))
                Slider(s.minScore, { v -> vm.update { it.copy(minScore = v) } }, valueRange = 0.3f..0.9f)
                Text(tr(R.string.str_0291, (s.motionThr * 100).roundToInt()))
                Slider(s.motionThr, { v -> vm.update { it.copy(motionThr = v) } }, valueRange = 0.03f..0.4f)
                SwitchRow(tr(R.string.str_0292), s.highRes) { v -> vm.update { it.copy(highRes = v) } }
                SwitchRow(tr(R.string.str_0293), s.useGpu) { v -> vm.update { it.copy(useGpu = v) } }
            }
            PerspectiveSettingsCard(vm, s)
            SourceCard(vm, s)
            DetectionZoneCard(vm, s)
            TrainingDataCard(vm, s)
            ImageQualityCard(vm, s)
            LowLightCard(vm, s)
            GpsCalibCard(vm, s)
            StabilityCard(vm, s)
            TrafficSettingsCard(vm, s)
            AppCard {
                SectionTitle(Icons.Filled.Visibility, tr(R.string.str_0294))
                SwitchRow(tr(R.string.str_0295), s.saveEvidence) { v -> vm.update { it.copy(saveEvidence = v) } }
                Text(
                    tr(R.string.str_0296) +
                        tr(R.string.str_0297) +
                        tr(R.string.str_0298),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppCard {
                SectionTitle(Icons.Filled.Videocam, tr(R.string.str_0299))
                VideoTestCard(vm)
            }
            AppCard {
                SectionTitle(Icons.Filled.Info, tr(R.string.str_0300))
                Text(
                    tr(R.string.str_0301) +
                        tr(R.string.str_0302),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppCard {
                SectionTitle(Icons.Filled.Info, tr(R.string.str_0303))
                Text(
                    tr(R.string.str_0304),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DeveloperCard()
        }
    }
}

private object Dev {
    const val NAME = "Eng. Moustafa Khalaf"
    const val EMAIL = "mostafakhalaf861@gmail.com"
    const val PHONE_DISPLAY = "+20 1030087988"
    const val PHONE_DIAL = "+201030087988"
}

@Composable
private fun ContactRow(icon: ImageVector, label: String, value: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("\u200E$value", fontWeight = FontWeight.Medium)
        }
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DeveloperCard() {
    val ctx = LocalContext.current
    fun open(i: Intent) {
        try {
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, tr(R.string.str_0305), Toast.LENGTH_SHORT).show()
        }
    }
    AppCard {
        SectionTitle(Icons.Filled.Person, tr(R.string.str_0306))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Navy), contentAlignment = Alignment.Center) {
                Text("MK", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Column {
                Text("\u200E${Dev.NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(tr(R.string.str_0307), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ContactRow(Icons.Filled.Email, tr(R.string.str_0308), Dev.EMAIL, tr(R.string.str_0309)) {
            open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Dev.EMAIL}")).apply {
                putExtra(Intent.EXTRA_SUBJECT, tr(R.string.str_0310))
            })
        }
        ContactRow(Icons.Filled.Phone, tr(R.string.str_0311), Dev.PHONE_DISPLAY, tr(R.string.str_0312)) {
            open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Dev.PHONE_DIAL}")))
        }
        Text(tr(R.string.str_0313), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * اختبار الدقة: عدّي بعربية بسرعة معروفة كذا مرة، والتطبيق بيلتقط كل عبور لوحده ويحسب الانحياز والتشتت،
 * ويقترح تضيف العبورات كعينات معايرة (لو فيه انحياز ثابت) أو تباعد الخطين (لو القراءات مشتتة).
 */
@Composable
internal fun AccuracyTestBox(vm: MainViewModel, s: AppSettings) {
    val t by vm.engine.accTest.state.collectAsStateWithLifecycle()
    var knownTxt by remember { mutableStateOf(Units.field(Units.spd(t.known))) }
    var msg by remember { mutableStateOf("") }
    val summary = remember(t.passes) { AccuracyTest.summarize(t.passes) }
    val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    val hint = MaterialTheme.typography.bodySmall
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    val sc by vm.engine.scaleCheck.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScaleCheckBadge(sc)
        Text(tr(R.string.at_hint), style = hint, color = hintColor)
        OutlinedTextField(
            knownTxt,
            { v ->
                knownTxt = v
                v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let { k -> vm.engine.accTest.setKnown(Units.toKmh(k).toFloat()) }
            },
            label = { Text(tr(R.string.at_known)) }, keyboardOptions = dec, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f), contentPadding = BtnPadding,
                onClick = { msg = ""; if (t.active) vm.engine.accTest.stop() else vm.engine.accTest.start() }
            ) { BtnText(if (t.active) tr(R.string.at_stop) else tr(R.string.at_start)) }
            OutlinedButton(
                modifier = Modifier.weight(1f), contentPadding = BtnPadding,
                enabled = t.passes.isNotEmpty() || t.ignored > 0,
                onClick = { msg = ""; vm.engine.accTest.reset() }
            ) { BtnText(tr(R.string.at_reset)) }
        }
        if (t.active) Text(tr(R.string.at_recording), style = hint, fontWeight = FontWeight.Bold)

        t.passes.forEachIndexed { i, p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr(
                        R.string.at_pass, i + 1,
                        String.format(Locale.US, "%.1f", Units.spd(p.real)),
                        String.format(Locale.US, "%.1f", Units.spd(p.cal)),
                        (if (p.err >= 0f) "+" else "−") + String.format(Locale.US, "%.1f", Units.spd(abs(p.err)))
                    ),
                    style = hint, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.engine.accTest.remove(i) }) { Icon(Icons.Filled.Delete, null) }
            }
        }
        if (t.ignored > 0) Text(tr(R.string.at_ignored, t.ignored), style = hint, color = hintColor)

        if (summary == null) {
            if (t.passes.isNotEmpty()) Text(tr(R.string.at_need_more), style = hint, color = hintColor)
        } else {
            val ok = summary.good
            val c = if (ok) Color(0xFF2E7D32) else Color(0xFFE65100)
            Text(
                tr(
                    R.string.at_summary, summary.n,
                    (if (summary.biasKmh >= 0f) "+" else "−") + String.format(Locale.US, "%.1f", Units.spd(abs(summary.biasKmh))),
                    String.format(Locale.US, "%.1f", Units.spd(summary.sdKmh)),
                    String.format(Locale.US, "%.1f", Units.spd(summary.within95Kmh))
                ),
                style = hint, fontWeight = FontWeight.Bold, color = c
            )
            if (ok) Text(tr(R.string.at_good), style = hint, color = c)
            if (summary.needsCalibration) Text(tr(R.string.at_bias, String.format(Locale.US, "%.1f", abs(summary.biasPct))), style = hint, color = c)
            if (summary.noisy) Text(tr(R.string.at_noisy, String.format(Locale.US, "%.1f", summary.sdPct)), style = hint, color = c)
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val old = Calibration.parse(s.calSamples)
                    val add = t.passes.map { Calibration.Sample(it.real, it.raw) }
                    val list = (old + add).takeLast(MAX_CAL_SAMPLES)
                    val (fa, fb) = Calibration.fit(list)
                    vm.update { it.copy(speedCalib = fa, speedOffset = fb, calSamples = Calibration.encode(list)) }
                    vm.engine.accTest.stop()
                    vm.engine.accTest.reset()
                    msg = tr(R.string.at_added, add.size)
                }
            ) { BtnText(tr(R.string.at_add_samples), maxLines = 2) }
        }
        if (msg.isNotEmpty()) Text(msg, style = hint, color = MaterialTheme.colorScheme.primary)
    }
}

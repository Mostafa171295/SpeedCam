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
        Text("تحويل المنظور شغال: الخطين A و B متحددين من النقط الأربعة (زرار \"المنظور\" في تبويب الكاميرا).", color = MaterialTheme.colorScheme.primary)
      } else {
        Text("اتجاه حركة العربيات في الصورة", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.axisHorizontal, { update { it.copy(axisHorizontal = true) } }, { Text("أفقي (يمين ↔ شمال)") })
            FilterChip(!s.axisHorizontal, { update { it.copy(axisHorizontal = false) } }, { Text("رأسي (قريب ↔ بعيد)") })
        }
        Text("الخط الأول: ${(s.lineA * 100).roundToInt()}%")
        Slider(s.lineA, { v -> update { it.copy(lineA = v) } }, valueRange = 0.05f..0.95f)
        Text("الخط الثاني: ${(s.lineB * 100).roundToInt()}%")
        Slider(s.lineB, { v -> update { it.copy(lineB = v) } }, valueRange = 0.05f..0.95f)
        if (abs(s.lineA - s.lineB) < 0.05f) {
            Text("⚠ الخطان قريبان جداً، باعد بينهما لدقة أعلى", color = MaterialTheme.colorScheme.error)
        }
      }
        var txt by remember { mutableStateOf(String.format(Locale.US, "%.1f", s.distanceM)) }
        OutlinedTextField(
            value = txt,
            onValueChange = { v ->
                txt = v
                v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let { d -> update { it.copy(distanceM = d) } }
            },
            label = { Text("المسافة الحقيقية بين الخطين على الأرض (متر)") },
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
private fun SpeedCalibrationBox(s: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    var real by remember { mutableStateOf("") }
    var read by remember { mutableStateOf("") }
    val r = real.replace(',', '.').toFloatOrNull()
    val a = read.replace(',', '.').toFloatOrNull()
    val samples = remember(s.calSamples) { Calibration.parse(s.calSamples) }
    val dec = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    fun commit(list: List<Calibration.Sample>) {
        val (fa, fb) = Calibration.fit(list)
        update { it.copy(speedCalib = fa, speedOffset = fb, calSamples = Calibration.encode(list)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "اعبر بعربية سرعتها ثابتة ومعروفة من العداد أو GPS، واكتب السرعة الحقيقية وقراءة التطبيق كما ظهرت، " +
                "ثم اضغط \"أضف عينة\". كرر بسرعات مختلفة (مثلاً 30 و50 و70): من تلات عينات متباعدة فأكتر " +
                "بيحسب التطبيق معاملين (نسبة وإزاحة). المعايرة بتطبق على القياسات الجديدة فقط.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(real, { real = it }, label = { Text("السرعة الحقيقية") }, keyboardOptions = dec, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(read, { read = it }, label = { Text("قراءة التطبيق") }, keyboardOptions = dec, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = r != null && a != null && r > 0f && a > 0f, onClick = {
                // نرجّع القراءة المعروضة للقيمة الخام (قبل المعايرة الحالية)
                val raw = (a!! - s.speedOffset) / s.speedCalib
                if (raw > 0f) {
                    commit((samples + Calibration.Sample(r!!, raw)).takeLast(MAX_CAL_SAMPLES))
                    real = ""; read = ""
                }
            }) { Text("أضف عينة") }
            OutlinedButton(enabled = samples.isNotEmpty() || s.speedCalib != 1f || s.speedOffset != 0f, onClick = {
                update { it.copy(speedCalib = 1f, speedOffset = 0f, calSamples = "") }
            }) { Text("مسح المعايرة") }
        }
        val b = s.speedOffset
        Text(
            "المعادلة الحالية: السرعة = " + String.format(Locale.US, "%.3f", s.speedCalib) + " × الخام " +
                (if (b < 0f) "− " else "+ ") + String.format(Locale.US, "%.2f", abs(b)) + "   (" + samples.size + " عينة)",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold
        )
        val st = remember(samples) { Calibration.stats(samples) }
        if (st != null) {
            Text(
                "دقة جهازك بعد المعايرة: ±" + String.format(Locale.US, "%.1f", st.half95) + " كم/س (ثقة 95%)  •  الانحراف RMSE " +
                    String.format(Locale.US, "%.1f", st.rmse) + " كم/س" + (if (st.dropped > 0) "  •  اتستبعدت ${st.dropped} عينة شاذة" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold
            )
        } else if (samples.size in 1..2) {
            Text("ضيف 3 عينات على الأقل (بسرعات مختلفة) عشان يتحسب مدى دقة جهازك.", style = MaterialTheme.typography.bodySmall)
        }
        samples.forEachIndexed { i, smp ->
            val shown = s.calibrate(smp.raw.toDouble())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "حقيقية " + String.format(Locale.US, "%.1f", smp.real) + "  •  خام " + String.format(Locale.US, "%.1f", smp.raw) +
                        "  •  بعد المعايرة " + String.format(Locale.US, "%.1f", shown),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { commit(samples.filterIndexed { j, _ -> j != i }) }) {
                    Icon(Icons.Filled.Delete, "حذف العينة")
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
        Text("حدود الدقة الحالية", fontWeight = FontWeight.Bold, color = color)
        Text("أقصى سرعة بدقة ±10%: ${v10.roundToInt()} كم/س")
        Text("أقصى سرعة بدقة ±5%: ${v5.roundToInt()} كم/س")
        if (s.fastMode) {
            Text(
                "الوضع السريع: التوقيت بدقة فريمات الكاميرا مباشرة (بدون تتبع للمركبة)، فالأرقام دي أعلى من الوضع العادي.",
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text("حد تتبع الدراجات النارية: حوالي ${moto.roundToInt()} كم/س")
        }
        Text(
            "لقياس 80 كم/س بدقة ±10% محتاج مسافة $need80 م على الأقل بين الخطين.",
            style = MaterialTheme.typography.bodySmall
        )
        val sigma = 1f / (f * 3.4641f)   // انحراف توقيت العبور بسبب تقطيع الفريمات: 1 ÷ (FPS × √12)
        val d5 = 80f / 3.6f * 1.4142f * sigma / 0.05f
        val d10 = 80f / 3.6f * 1.4142f * sigma / 0.10f
        Text(
            "بالمعادلة الإحصائية (الخطأ النسبي = √2 × σ × السرعة ÷ المسافة): لـ 80 كم/س بدقة ±5% محتاج ${ceil(d5).toInt()} م، وبدقة ±10% محتاج ${ceil(d10).toInt()} م.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            (if (assumed) "محسوبة بافتراض 15 FPS لحد ما يبدأ القياس." else "محسوبة على $f FPS الحالي.") +
                " تقدير نظري، والدقة الفعلية بتتأكد بتجربة بعربية سرعتها معروفة.",
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
        AppHeader("الإعدادات", "المعايرة وتصنيف المركبات")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AppCard {
                SectionTitle(Icons.Filled.Straighten, "المعايرة")
                CalibrationControls(s, vm::update, fps)
            }
            AppCard {
                SectionTitle(Icons.Filled.Straighten, "معايرة السرعة")
                SpeedCalibrationBox(s, vm::update)
            }
            AppCard {
                SectionTitle(Icons.Filled.Category, "تصنيف المركبات")
                SwitchRow("تمييز الميكروباص والتوك توك بالحجم (للموديل الجاهز فقط)", s.sizeRefine) { v -> vm.update { it.copy(sizeRefine = v) } }
                Text(
                    "بيحسب طول المركبة بالمتر من المعايرة. يعمل لو الحركة أفقية والمركبة كاملة في الصورة. " +
                        "لو استخدمت موديل مخصص (vehicles_custom.tflite) بيتجاهل ده تلقائياً.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("توك توك لو الطول أقل من: ${String.format(Locale.US, "%.1f", s.tukMaxLen)} م")
                Slider(s.tukMaxLen, { v -> vm.update { it.copy(tukMaxLen = v) } }, valueRange = 2f..4f)
                Text("ميكروباص لو الطول من: ${String.format(Locale.US, "%.1f", s.microMinLen)} م")
                Slider(s.microMinLen, { v -> vm.update { it.copy(microMinLen = v) } }, valueRange = 4f..6.5f)
                Text("أتوبيس لو الطول من: ${String.format(Locale.US, "%.1f", s.busMinLen)} م")
                Slider(s.busMinLen, { v -> vm.update { it.copy(busMinLen = v) } }, valueRange = 6f..11f)
            }
            AppCard {
                SectionTitle(Icons.Filled.Visibility, "الكشف")
                Text("حساسية الكشف: ${(s.minScore * 100).roundToInt()}%")
                Slider(s.minScore, { v -> vm.update { it.copy(minScore = v) } }, valueRange = 0.3f..0.9f)
                Text("حساسية الحركة (للوضع السريع): ${(s.motionThr * 100).roundToInt()}%  (أقل = أكثر حساسية)")
                Slider(s.motionThr, { v -> vm.update { it.copy(motionThr = v) } }, valueRange = 0.03f..0.4f)
                SwitchRow("دقة أعلى 720p (أدق، أبطأ)", s.highRes) { v -> vm.update { it.copy(highRes = v) } }
                SwitchRow("تسريع الكشف بالـ GPU (لو حصلت مشكلة قفّله)", s.useGpu) { v -> vm.update { it.copy(useGpu = v) } }
            }
            PerspectiveSettingsCard(vm, s)
            SourceCard(vm, s)
            LowLightCard(vm, s)
            GpsCalibCard(vm, s)
            StabilityCard(vm, s)
            TrafficSettingsCard(vm, s)
            AppCard {
                SectionTitle(Icons.Filled.Visibility, "لقطات الإثبات")
                SwitchRow("حفظ لقطة لكل مركبة تتجاوز حد السرعة", s.saveEvidence) { v -> vm.update { it.copy(saveEvidence = v) } }
                Text(
                    "اللقطة بتتحفظ داخل التطبيق بس (مش في معرض الصور)، وبتظهر في تبويب المركبات وتقدر تشاركها. " +
                        "بيحتفظ بآخر 300 لقطة وبيمسح الأقدم تلقائياً، وبتتمسح لما تمسح القياس نفسه. " +
                        "حد السرعة بتحدده من تبويب المركبات.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppCard {
                SectionTitle(Icons.Filled.Videocam, "اختبار على فيديو مسجّل")
                VideoTestCard(vm)
            }
            AppCard {
                SectionTitle(Icons.Filled.Info, "نصائح للدقة")
                Text(
                    "ثبّت الموبايل على حامل ثابت وخلي العربيات تعدي بالعرض أمامه. حدد علامتين على الأرض، قس المسافة بينهما، " +
                        "وطابق الخطين الأصفرين عليهما. جرّب بسيارة سرعتها معروفة من العداد قبل ما تعتمد على الأرقام.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AppCard {
                SectionTitle(Icons.Filled.Info, "نسخة احتياطية")
                Text(
                    "التطبيق بيحفظ نسخة من كل القياسات تلقائياً كل 10 دقايق في مجلد التنزيلات (Downloads) باسم speedcam_backup وتاريخ اليوم، وبتفتح في Excel.",
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
            Toast.makeText(ctx, "لا يوجد تطبيق مناسب على الجهاز", Toast.LENGTH_SHORT).show()
        }
    }
    AppCard {
        SectionTitle(Icons.Filled.Person, "عن المطوّر")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(Navy), contentAlignment = Alignment.Center) {
                Text("MK", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Column {
                Text("\u200E${Dev.NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("مطوّر التطبيق", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ContactRow(Icons.Filled.Email, "البريد الإلكتروني", Dev.EMAIL, "اضغط للمراسلة") {
            open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Dev.EMAIL}")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "رادار السرعة")
            })
        }
        ContactRow(Icons.Filled.Phone, "رقم الهاتف", Dev.PHONE_DISPLAY, "اضغط للاتصال") {
            open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Dev.PHONE_DIAL}")))
        }
        Text("رادار السرعة  •  الإصدار 1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

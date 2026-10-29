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
        SectionTitle(Icons.Filled.Visibility, "تحويل المنظور (دقة بين الحارات)")
        PerspectivePanel(s, vm::update, editorHint = false)
    }
}

/** البند 6: التصوير في الضوء الضعيف. */
@Composable
fun LowLightCard(vm: MainViewModel, s: AppSettings) {
    val caps by vm.cameraCaps.collectAsStateWithLifecycle()
    AppCard {
        SectionTitle(Icons.Filled.Visibility, "التصوير في الضوء الضعيف")
        Text(
            "بالليل الكاميرا بتبطّأ الفريمات عشان تدخّل ضوء أكتر، وده بيبوّظ توقيت العبور. هنا تقدر تثبّت الفريمات وتتحكم في السطوع.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ToggleRow("تثبيت معدل الفريمات", s.lockFps, enabled = !s.manualExp) { v -> vm.update { it.copy(lockFps = v) } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 24, 30).forEach { f ->
                FilterChip(s.targetFps == f, { vm.update { it.copy(targetFps = f) } }, { Text("$f FPS") })
            }
        }
        if (caps.ready && caps.fpsRanges.isNotEmpty()) {
            Text("نطاقات FPS اللي الكاميرا بتدعمها: ${caps.fpsRanges}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (caps.evMax > caps.evMin) {
            val ev = s.evIndex.coerceIn(caps.evMin, caps.evMax)
            Text("تعويض التعريض (السطوع): $ev" + if (caps.evStep > 0f) "  (كل خطوة ≈ ${String.format(Locale.US, "%.2f", caps.evStep)} EV)" else "")
            Slider(
                value = ev.toFloat(),
                onValueChange = { v -> vm.update { it.copy(evIndex = v.roundToInt()) } },
                valueRange = caps.evMin.toFloat()..caps.evMax.toFloat(),
                steps = (caps.evMax - caps.evMin - 1).coerceAtLeast(0),
                enabled = !s.manualExp
            )
        } else {
            Text("تعويض التعريض هيظهر بعد ما الكاميرا تشتغل.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        ToggleRow("تعريض يدوي (سرعة غالق + ISO)", s.manualExp, enabled = caps.manualSupported) { v -> vm.update { it.copy(manualExp = v) } }
        if (caps.ready && !caps.manualSupported) {
            Text("الكاميرا في جهازك مش بتدعم التعريض اليدوي. استخدم التثبيت والتعويض.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (s.manualExp && caps.manualSupported) {
            Text("سرعة الغالق (الأسرع = صورة أوضح للعربية المتحركة لكن أغمق)", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SHUTTERS.forEach { d -> FilterChip(s.shutterDenom == d, { vm.update { it.copy(shutterDenom = d) } }, { Text("1/$d") }) }
            }
            Text("ISO (الأعلى = أسطع لكن فيه تشويش). المدى المدعوم: ${caps.isoMin}-${caps.isoMax}", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ISOS.filter { it in caps.isoMin..caps.isoMax }.forEach { i -> FilterChip(s.isoManual == i, { vm.update { it.copy(isoManual = i) } }, { Text("$i") }) }
            }
            Text(
                "لو الصورة سودا زوّد ISO أو بطّئ الغالق. جرّب الأول 1/250 و ISO 1600 بالليل.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "قيس الفرق بتجربة: الـ FPS الحقيقي بيظهر في تبويب الكاميرا، قارنه بالنهار والليل.",
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
        SectionTitle(Icons.Filled.Info, "الثبات والتشغيل لساعات")
        ToggleRow("تشغيل مستمر في الخلفية (تكمّل والشاشة مقفولة)", s.bgService) { on ->
            vm.update { it.copy(bgService = on) }
            if (on && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
            ) notifLauncher.launch("android.permission.POST_NOTIFICATIONS")
        }
        Text(
            "بيشغّل خدمة بإشعار دايم فيه زرار إيقاف. ابدأ القياس والتطبيق ظاهر، وبعدها تقدر تقفل الشاشة. " +
                "الأفضل توصّل الجهاز بشاحن وتثبّته في مكان مهوّى.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = {
            try {
                ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
                Toast.makeText(ctx, "افتح إعدادات البطارية يدوياً واسمح للتطبيق بالعمل في الخلفية", Toast.LENGTH_LONG).show()
            }
        }) { Text("استثناء التطبيق من توفير البطارية") }

        ToggleRow("حماية الحرارة والبطارية", s.thermalGuard) { v -> vm.update { it.copy(thermalGuard = v) } }
        Text(
            "تحذير عند حرارة 42° أو بطارية 20%، وإيقاف تلقائي (مع نسخة احتياطية) عند 46° أو بطارية 7% وغير موصّل بشاحن.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (health.batteryPct >= 0) {
            Text(
                "دلوقتي: حرارة البطارية ${health.tempC.toInt()}° • الشحن ${health.batteryPct}%" + if (health.charging) " (بيشحن)" else "",
                fontWeight = FontWeight.Bold,
                color = if (health.level >= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }

        Text("أقصى عدد سجلات محفوظة", fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1000, 5000, 20000, 100000, 0).forEach { m ->
                FilterChip(s.maxRecords == m, { vm.update { it.copy(maxRecords = m) } }, { Text(if (m == 0) "بدون حد" else "$m") })
            }
        }
        Text(
            "لما العدد يعدّي الحد، الأقدم بيتحفظ في ملف speedcam_archive_… في التنزيلات وبعدين بيتمسح من التطبيق (ومعاه لقطاته).",
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
        SectionTitle(Icons.Filled.Info, "مؤشرات المرور والتنبيهات")

        Text("عدد الحارات في كل اتجاه (للحارات ومستوى الخدمة)", fontWeight = FontWeight.Bold)
        ChipRow { listOf(1, 2, 3, 4).forEach { n -> FilterChip(s.laneCount == n, { vm.update { it.copy(laneCount = n) } }, { Text("$n") }) } }

        Text("سعة الحارة (PCE/ساعة)", fontWeight = FontWeight.Bold)
        ChipRow { listOf(1500, 1800, 2000, 2200).forEach { c -> FilterChip(s.laneCapacity == c, { vm.update { it.copy(laneCapacity = c) } }, { Text("$c") }) } }

        Text("طريق باتجاه واحد؟ الاتجاه المسموح (أي مركبة عكسه تتسجل)", fontWeight = FontWeight.Bold)
        ChipRow {
            FilterChip(s.allowedDir == 0, { vm.update { it.copy(allowedDir = 0) } }, { Text("مش محدد") })
            FilterChip(s.allowedDir == 1, { vm.update { it.copy(allowedDir = 1) } }, { Text(directionLabel(1, s.axisHorizontal)) })
            FilterChip(s.allowedDir == 2, { vm.update { it.copy(allowedDir = 2) } }, { Text(directionLabel(2, s.axisHorizontal)) })
        }

        Text("تنبيه مركبة واقفة بعد (ثانية)", fontWeight = FontWeight.Bold)
        ChipRow { listOf(0, 5, 10, 20, 30).forEach { v -> FilterChip(s.stopSec == v, { vm.update { it.copy(stopSec = v) } }, { Text(if (v == 0) "إيقاف" else "$v") }) } }

        Text("تنبيه ازدحام لو وسيط سرعة آخر 6 مركبات أقل من (كم/س)", fontWeight = FontWeight.Bold)
        ChipRow { listOf(0, 10, 15, 20, 30).forEach { v -> FilterChip(s.congestionKmh == v, { vm.update { it.copy(congestionKmh = v) } }, { Text(if (v == 0) "إيقاف" else "$v") }) } }

        Text("تنبيه فوري لو سرعة مركبة وصلت (كم/س)", fontWeight = FontWeight.Bold)
        ChipRow { listOf(0, 80, 100, 120, 140).forEach { v -> FilterChip(s.alertSpeed == v, { vm.update { it.copy(alertSpeed = v) } }, { Text(if (v == 0) "إيقاف" else "$v") }) } }

        Text(
            "الحالات بتتسجل في تبويب الإحصائيات، وبيجي إشعار (لازم إذن الإشعارات). كل نوع إشعار بيتكرر مرة كل دقيقة على الأكتر. " +
                "ملحوظة: كشف المركبة الواقفة بيعتمد على إن التتبع يشوفها، فمركبة بعيدة جداً أو مخبية ممكن ماتتكشفش.",
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
        SectionTitle(Icons.Filled.Visibility, "مصدر الصورة")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(s.sourceMode == 0, { vm.update { it.copy(sourceMode = 0) } }, { Text("كاميرا الموبايل") })
            FilterChip(s.sourceMode == 1, { vm.update { it.copy(sourceMode = 1) } }, { Text("كاميرا على الشبكة") })
        }
        if (s.sourceMode == 1) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("رابط بث MJPEG (مثال: http://192.168.1.50:8080/video)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = { vm.update { it.copy(streamUrl = url.trim()) } }) { Text("حفظ واتصال") }
            Text("الحالة: " + status.ifBlank { "—" }, fontWeight = FontWeight.Bold)
            Text(
                "المدعوم: بث MJPEG عبر HTTP على نفس الشبكة (كاميرات IP كتير، أو موبايل قديم بتطبيق بيبث كاميرا). المسار بيختلف من كاميرا لكاميرا، راجع دليلها. " +
                    "ممكن تكتب المستخدم وكلمة السر في الرابط: http://user:pass@العنوان/مسار.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "تنبيه دقة: توقيت كل فريم هو وقت وصوله للموبايل مش وقت التقاطه، والشبكة بتضيف تأخير متذبذب، فدقة السرعة أقل من كاميرا الموبايل. " +
                    "استخدم واي فاي قوي أو كابل، وبعدين عاير بعربية معروفة السرعة. RTSP وكاميرات USB مش مدعومة حالياً.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
            Text(
                "التعريض والـ FPS والإضاءة بالليل بتتظبط من الكاميرا نفسها، والإضاءة تحت الحمراء حاجة في الكاميرا مش في التطبيق.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

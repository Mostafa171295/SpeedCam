package com.example.speedcam

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * معايرة تلقائية بالـ GPS:
 *  - موبايل المرجع (جوه العربية) بيسجل سرعة GPS كل ثانية في ملف.
 *  - موبايل الكاميرا بيحدد موقعه، وبيستورد الملف، وبيطابق كل مرور للعربية مع قياس التطبيق، ويضيف العينات للمعايرة.
 */
@Composable
fun GpsCalibCard(vm: MainViewModel, s: AppSettings) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val rec by GpsRef.recording.collectAsStateWithLifecycle()
    val cnt by GpsRef.count.collectAsStateWithLifecycle()
    val kmh by GpsRef.lastKmh.collectAsStateWithLifecycle()
    val acc by GpsRef.lastAcc.collectAsStateWithLifecycle()
    var msg by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<GpsMatch>>(emptyList()) }
    var pending by remember { mutableIntStateOf(0) }   // 1 = تسجيل ، 2 = تحديد الموقع
    var fixing by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    DisposableEffect(rec) {
        view.keepScreenOn = rec
        onDispose { view.keepScreenOn = false }
    }

    fun hasLoc(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun startRec() {
        msg = if (GpsRef.start(ctx)) "بدأ تسجيل GPS. سيب الشاشة مفتوحة والتطبيق ظاهر." else "فعّل الـ GPS (الموقع) في الموبايل وحاول تاني."
    }

    fun doFix() {
        fixing = true
        msg = "بحدد موقع الكاميرا... استنى لحد 40 ثانية (في مكان مفتوح)."
        GpsRef.fix(ctx) { loc ->
            fixing = false
            if (loc == null) msg = "ماقدرتش أحدد الموقع بدقة كفاية. اطلع مكان مفتوح وجرّب تاني."
            else {
                vm.update { it.copy(siteLat = loc.latitude.toString(), siteLon = loc.longitude.toString()) }
                msg = "اتحفظ موقع الكاميرا (دقة ${loc.accuracy.toInt()} متر)."
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) msg = "محتاج إذن الموقع عشان GPS."
        else if (pending == 1) startRec() else if (pending == 2) doFix()
    }

    fun ask(action: Int) {
        pending = action
        if (hasLoc()) { if (action == 1) startRec() else doFix() } else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val lat = s.siteLat.toDoubleOrNull()
        val lon = s.siteLon.toDoubleOrNull()
        if (lat == null || lon == null) { msg = "حدد موقع الكاميرا الأول."; return@rememberLauncherForActivityResult }
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val pts = GpsCalib.parseCsv(text)
            if (pts.size < 5) { msg = "الملف مش ملف GPS صالح."; return@rememberLauncherForActivityResult }
            matches = GpsCalib.match(pts, lat, lon, vm.records.value, vm.settings.value)
            msg = if (matches.isEmpty()) "ماحصلش مرور جنب موقع الكاميرا في الملف ده (النطاق 80 متر)." else "لقيت ${matches.size} مرور."
        } catch (t: Throwable) {
            msg = "فشل قراءة الملف: ${t.message ?: ""}"
        }
    }

    AppCard {
        SectionTitle(Icons.Filled.Speed, "معايرة تلقائية بالـ GPS")
        Text(
            "بدل ما تكتب العينات بإيدك: موبايل تاني جوه العربية بيسجل سرعة GPS، والتطبيق بيطابق كل مرور مع قياسه ويضيفه للمعايرة. " +
                "الموبايلين لازم يكون وقتهم مضبوط تلقائي من الشبكة.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("١) على موبايل المرجع (اللي جوه العربية)", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !rec, onClick = { ask(1) }) { Text("بدء تسجيل GPS") }
            OutlinedButton(enabled = rec, onClick = {
                val name = GpsRef.stop(ctx)
                msg = if (name != null) "اتحفظ الملف $name في التنزيلات. انقله لموبايل الكاميرا." else "التسجيل كان قصير أو فشل الحفظ."
            }) { Text("إيقاف وحفظ") }
        }
        if (rec) Text("بيسجل: $cnt قراءة • السرعة الحالية ${kmh.toInt()} كم/س • دقة الموقع ${acc.toInt()} م", style = MaterialTheme.typography.bodySmall)

        Text("٢) على موبايل الكاميرا", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !fixing, onClick = { ask(2) }) { Text("تحديد موقع الكاميرا") }
            Button(onClick = { importer.launch("*/*") }) { Text("استيراد ملف GPS ومطابقته") }
        }
        Text(
            if (s.siteLat.isNotBlank()) "موقع الكاميرا المحفوظ: ${s.siteLat.take(9)} ، ${s.siteLon.take(9)}" else "موقع الكاميرا لسه ماتحددش.",
            style = MaterialTheme.typography.bodySmall
        )
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        if (matches.isNotEmpty()) {
            val good = matches.filter { it.ok && it.rec != null && it.rawKmh != null }
            if (good.isNotEmpty()) {
                val diff = good.map { it.rec!!.speedKmh - it.gpsKmh }.average()
                Text(
                    "متوسط فرق التطبيق عن الـ GPS حالياً: " + String.format(Locale.US, "%+.1f", diff) + " كم/س (على ${good.size} مرور مقبول)",
                    fontWeight = FontWeight.Bold
                )
            }
            matches.forEach { m ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        fmt.format(m.passT) + "  GPS ${m.gpsKmh.toInt()} كم/س" +
                            (if (m.rec != null) "  ↔  التطبيق ${m.rec.speedKmh.toInt()} كم/س" else "") +
                            (if (m.ok) "  ✓" else "  ⚠"),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        color = if (m.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!m.ok) Text(m.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(enabled = good.isNotEmpty(), onClick = {
                val old = Calibration.parse(vm.settings.value.calSamples)
                val add = good.map { Calibration.Sample(it.gpsKmh.toFloat(), it.rawKmh!!.toFloat()) }
                val list = (old + add).takeLast(MAX_CAL_SAMPLES)
                val (fa, fb) = Calibration.fit(list)
                vm.update { it.copy(speedCalib = fa, speedOffset = fb, calSamples = Calibration.encode(list)) }
                matches = emptyList()
                msg = "اتضافت ${add.size} عينة للمعايرة. شوف دقة جهازك في كارت معايرة السرعة."
            }) { Text("إضافة المقبول (${good.size}) كعينات معايرة") }
            Text(
                "مهم: السرعة الخام بتتحسب بعكس معايرتك الحالية، فماتغيّرش المعايرة بين القياس والمطابقة. " +
                    "لو معايرتك الحالية مش صفر خطأ، الأفضل تعمل \"إعادة ضبط\" للمعايرة قبل ما تعمل جولة القياس.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "دقة سرعة GPS عادةً حوالي ±1 كم/س في مكان مفتوح. المرور المقبول لازم تكون سرعته ثابتة وفوق 15 كم/س، ومن غير مركبة تانية قريبة في الوقت.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

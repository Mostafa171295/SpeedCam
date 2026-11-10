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
        msg = if (GpsRef.start(ctx)) tr(R.string.str_0092) else tr(R.string.str_0093)
    }

    fun doFix() {
        fixing = true
        msg = tr(R.string.str_0094)
        GpsRef.fix(ctx) { loc ->
            fixing = false
            if (loc == null) msg = tr(R.string.str_0095)
            else {
                vm.update { it.copy(siteLat = loc.latitude.toString(), siteLon = loc.longitude.toString()) }
                msg = tr(R.string.str_0096, Units.distI(loc.accuracy))
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) msg = tr(R.string.str_0097)
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
        if (lat == null || lon == null) { msg = tr(R.string.str_0098); return@rememberLauncherForActivityResult }
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val pts = GpsCalib.parseCsv(text)
            if (pts.size < 5) { msg = tr(R.string.str_0099); return@rememberLauncherForActivityResult }
            matches = GpsCalib.match(pts, lat, lon, vm.records.value, vm.settings.value)
            msg = if (matches.isEmpty()) tr(R.string.str_0100) else tr(R.string.str_0101, matches.size)
        } catch (t: Throwable) {
            msg = tr(R.string.str_0102, t.message ?: "")
        }
    }

    AppCard {
        SectionTitle(Icons.Filled.Speed, tr(R.string.str_0019))
        Text(
            tr(R.string.str_0103) +
                tr(R.string.str_0104),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(tr(R.string.str_0105), fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !rec, onClick = { ask(1) }, modifier = Modifier.weight(1f), contentPadding = BtnPadding) { BtnText(tr(R.string.str_0106)) }
            OutlinedButton(enabled = rec, modifier = Modifier.weight(1f), contentPadding = BtnPadding, onClick = {
                val name = GpsRef.stop(ctx)
                msg = if (name != null) tr(R.string.str_0107, name) else tr(R.string.str_0108)
            }) { BtnText(tr(R.string.str_0109)) }
        }
        if (rec) Text(tr(R.string.str_0110, cnt, Units.spdI(kmh), Units.distI(acc)), style = MaterialTheme.typography.bodySmall)

        Text(tr(R.string.str_0111), fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !fixing, onClick = { ask(2) }, modifier = Modifier.fillMaxWidth()) { BtnText(tr(R.string.str_0112)) }
            Button(onClick = { importer.launch("*/*") }, modifier = Modifier.fillMaxWidth()) { BtnText(tr(R.string.str_0113)) }
        }
        Text(
            if (s.siteLat.isNotBlank()) tr(R.string.str_0114, s.siteLat.take(9), s.siteLon.take(9)) else tr(R.string.str_0115),
            style = MaterialTheme.typography.bodySmall
        )
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)

        if (matches.isNotEmpty()) {
            val good = matches.filter { it.ok && it.rec != null && it.rawKmh != null }
            if (good.isNotEmpty()) {
                val diff = good.map { it.rec!!.speedKmh - it.gpsKmh }.average()
                Text(
                    tr(R.string.str_0116) + String.format(Locale.US, "%+.1f", Units.spd(diff)) + tr(R.string.str_0117, good.size),
                    fontWeight = FontWeight.Bold
                )
            }
            matches.forEach { m ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        fmt.format(m.passT) + tr(R.string.str_0118, Units.spdI(m.gpsKmh)) +
                            (if (m.rec != null) tr(R.string.str_0119, Units.spdI(m.rec.speedKmh)) else "") +
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
                msg = tr(R.string.str_0120, add.size)
            }, modifier = Modifier.fillMaxWidth()) { BtnText(tr(R.string.str_0121, good.size), maxLines = 2) }
            Text(
                tr(R.string.str_0122) +
                    tr(R.string.str_0123),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            tr(R.string.str_0124),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

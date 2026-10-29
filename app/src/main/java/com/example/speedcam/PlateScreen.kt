package com.example.speedcam

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PlateScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by vm.records.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val dataState by vm.plateReader.dataState.collectAsStateWithLifecycle()
    var onlyPlates by remember { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM/dd  HH:mm:ss", Locale.getDefault()) }

    val withPlate = all.count { it.plateDigits.isNotEmpty() }
    val list = if (onlyPlates) all.filter { it.plateDigits.isNotEmpty() } else all

    Column(Modifier.fillMaxSize()) {
        AppHeader("اللوحات", "لوحات المركبات المسجلة مع سرعتها")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("قراءة اللوحات أثناء القياس", fontWeight = FontWeight.Bold)
                            Text(
                                "كل مركبة بتعدّي بتتسجل لوحتها مع سرعتها في نفس الصف.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = s.readPlates, onCheckedChange = { on ->
                            vm.update { it.copy(readPlates = on) }
                            if (on) vm.plateReader.ensureData()
                        })
                    }
                    when {
                        !s.readPlates -> Unit
                        dataState == PLATE_DATA_FAILED -> {
                            Text(
                                "تعذّر تجهيز اللغة العربية للقراءة. تأكد من الإنترنت ثم أعد المحاولة (أو حط ملف ara.traineddata داخل app/src/main/assets وأعد التثبيت).",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                            )
                            Button(onClick = { vm.plateReader.ensureData() }) { Text("إعادة المحاولة") }
                        }
                        dataState == PLATE_DATA_READY -> Text(
                            "جاهز. اللوحة بتتقرأ أحسن لما الكاميرا قريبة من المركبات، ومع تفعيل \"دقة عالية\" في الإعدادات.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Text(
                            if (dataState == PLATE_DATA_DOWNLOADING) "جاري تحميل ملف اللغة العربية (مرة واحدة فقط، حوالي 2 ميجا)..."
                            else "جاري التجهيز...",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { PlateTestCard(vm) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(onlyPlates, { onlyPlates = true }, { Text("اللوحات المقروءة ($withPlate)") })
                    FilterChip(!onlyPlates, { onlyPlates = false }, { Text("كل القياسات (${all.size})") })
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { CsvExporter.build(ctx, list, s.axisHorizontal) }
                            CsvExporter.share(ctx, f)
                        }
                    }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("مشاركة Excel") }
                    OutlinedButton(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                CsvExporter.saveToDownloads(ctx, CsvExporter.build(ctx, list, s.axisHorizontal))
                            }
                            Toast.makeText(ctx, if (ok) "تم الحفظ في مجلد التنزيلات" else "فشل الحفظ", Toast.LENGTH_LONG).show()
                        }
                    }) { Icon(Icons.Filled.Download, null); Spacer(Modifier.width(4.dp)); Text("حفظ في التنزيلات") }
                    IconButton(enabled = all.isNotEmpty(), onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, "مسح الكل") }
                }
            }

            if (list.isEmpty()) {
                item {
                    AppCard {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("لا توجد لوحات مسجلة بعد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                "شغّل القياس من تبويب الكاميرا وسيظهر هنا كل مركبة معدّية مع لوحتها وسرعتها.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(list.take(200), key = { it.id }) { r ->
                    val type = VehicleType.fromName(r.vehicleType)
                    Card(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(type?.color ?: 0xFF888888)))
                            Column(Modifier.weight(1f)) {
                                if (r.plateDigits.isNotEmpty()) {
                                    Text(PlateParser.display(r.plateLetters, r.plateDigits), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("لوحة غير مقروءة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    "${type?.ar ?: r.vehicleType}  •  ${fmt.format(Date(r.timestamp))}" +
                                        (if (r.plateDigits.isNotEmpty()) "  •  ثقة ${r.plateConf}%" else ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (r.speedKmh >= 0) {
                                Text("${r.speedKmh.roundToInt()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("كم/س", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("عدّ فقط", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.delete(r) }) {
                                Icon(Icons.Filled.Delete, "حذف", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("مسح كل البيانات؟") },
            text = { Text("سيتم حذف كل القراءات (السرعات واللوحات) نهائياً.") },
            confirmButton = { TextButton({ vm.clearAll(); confirmClear = false }) { Text("مسح") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("إلغاء") } }
        )
    }
}

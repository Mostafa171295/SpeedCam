package com.example.speedcam

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
        AppHeader(tr(R.string.str_0127), tr(R.string.str_0142))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(tr(R.string.str_0143), fontWeight = FontWeight.Bold)
                            Text(
                                tr(R.string.str_0144),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = s.readPlates, onCheckedChange = { on ->
                            vm.update { it.copy(readPlates = on) }
                            if (on) vm.plateReader.ensureData()
                        })
                    }
                    if (s.readPlates) {
                        Text(tr(R.string.plate_fmt_title), fontWeight = FontWeight.Bold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (listOf(PlateProfile.AUTO to tr(R.string.plate_fmt_auto)) + PlateProfile.ALL.map { it.id to tr(it.nameRes) }).forEach { (id, label) ->
                                FilterChip(s.plateFormat == id, { vm.update { it.copy(plateFormat = id) } }, { Text(label) })
                            }
                        }
                        Text(
                            tr(R.string.plate_fmt_hint),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when {
                        !s.readPlates -> Unit
                        dataState == PLATE_DATA_FAILED -> {
                            Text(
                                tr(R.string.str_0145, vm.plateReader.dataFileName()),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                            )
                            Button(onClick = { vm.plateReader.ensureData() }) { BtnText(tr(R.string.str_0146)) }
                        }
                        dataState == PLATE_DATA_READY -> Text(
                            tr(R.string.str_0147),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Text(
                            if (dataState == PLATE_DATA_DOWNLOADING) tr(R.string.str_0148)
                            else tr(R.string.str_0149),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { PlateTestCard(vm) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(onlyPlates, { onlyPlates = true }, { Text(tr(R.string.str_0150, withPlate)) })
                    FilterChip(!onlyPlates, { onlyPlates = false }, { Text(tr(R.string.str_0151, all.size)) })
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { CsvExporter.build(ctx, list, s.axisHorizontal) }
                            CsvExporter.share(ctx, f)
                        }
                    }, modifier = Modifier.weight(1f), contentPadding = BtnPadding) { Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); BtnText(tr(R.string.str_0152)) }
                    OutlinedButton(enabled = list.isNotEmpty(), onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                CsvExporter.saveToDownloads(ctx, CsvExporter.build(ctx, list, s.axisHorizontal))
                            }
                            Toast.makeText(ctx, if (ok) tr(R.string.str_0153) else tr(R.string.str_0154), Toast.LENGTH_LONG).show()
                        }
                    }, modifier = Modifier.weight(1f), contentPadding = BtnPadding) { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); BtnText(tr(R.string.str_0155)) }
                    IconButton(enabled = all.isNotEmpty(), onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, tr(R.string.str_0156)) }
                }
            }

            if (list.isEmpty()) {
                item {
                    AppCard {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(tr(R.string.str_0157), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                tr(R.string.str_0158),
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
                                    Text(tr(R.string.str_0159), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    "${type?.ar ?: r.vehicleType}  •  ${fmt.format(Date(r.timestamp))}" +
                                        (if (r.plateDigits.isNotEmpty()) tr(R.string.str_0160, r.plateConf) else ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (r.speedKmh >= 0) {
                                Text("${Units.spdI(r.speedKmh)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(tr(R.string.str_0161), style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(tr(R.string.str_0162), style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { vm.delete(r) }) {
                                Icon(Icons.Filled.Delete, tr(R.string.str_0163), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
            title = { Text(tr(R.string.str_0164)) },
            text = { Text(tr(R.string.str_0165)) },
            confirmButton = { TextButton({ vm.clearAll(); confirmClear = false }) { BtnText(tr(R.string.str_0166)) } },
            dismissButton = { TextButton({ confirmClear = false }) { BtnText(tr(R.string.str_0167)) } }
        )
    }
}

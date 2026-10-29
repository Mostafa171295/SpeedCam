package com.example.speedcam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow

private val Amber = Color(0xFFFFC107)

@Composable
fun CameraScreen(vm: MainViewModel, visible: Boolean = true) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    // الـ overlay بيتحدّث كل فريم: ما بنقراهوش إلا والشاشة ظاهرة، فلما التبويب يتخفى مفيش إعادة رسم
    val ovState = vm.engine.overlay.collectAsStateWithLifecycle()
    val aspect by remember { derivedStateOf { ovState.value.aspect } }
    // FPS لكارت الدقة بنقراه كل ثانية (مش كل فريم) عشان الأرقام ما تتغيّرش بسرعة
    val fpsSlow by produceState(0) {
        while (true) {
            value = ovState.value.fps
            kotlinx.coroutines.delay(1000)
        }
    }
    val running by vm.engine.runningFlow.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val pf by vm.engine.previewFrame.collectAsStateWithLifecycle()
    val streamStatus by vm.host.streamStatus.collectAsStateWithLifecycle()
    var showCalib by remember { mutableStateOf(false) }
    var perspMode by remember { mutableStateOf(false) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission && s.sourceMode == 0) launcher.launch(Manifest.permission.CAMERA) }
    // الكاميرا نفسها بتتدار من CameraHost؛ هنا بس بنوصّل المعاينة بالشاشة
    LaunchedEffect(hasPermission) { if (hasPermission) vm.onCameraPermission() }

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            keepScreenOn = true
        }
    }
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 30f
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    val abPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#0B1F3A")
            textSize = 26f
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
    }

    DisposableEffect(previewView) {
        vm.host.attachPreview(previewView)
        onDispose { vm.host.detachPreview(previewView) }
    }

    if (!hasPermission && s.sourceMode == 0) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Videocam, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("نحتاج إذن الكاميرا", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("الكاميرا هي مصدر القياس الوحيد. لا يتم حفظ صور إلا لقطات إثبات المتجاوزين (ويمكن إيقافها من الإعدادات).", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("منح الإذن") }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelMax = maxHeight * 0.36f
        Column(Modifier.fillMaxSize()) {
            // منطقة الفيديو: المعاينة في النص، والمعلومات فوق الشاشة كلها (مش فوق الفيديو بس) عشان ما تتراكبش
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                Box(Modifier.align(Alignment.Center).aspectRatio(aspect)) {
                    if (s.sourceMode == 1) {
                        if (visible) pf?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    } else {
                        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    }
                    if (visible) {
                        val ov = ovState.value
                        Canvas(Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height
                            val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                            val segA = Geo.segment(s, 0)
                            val segB = Geo.segment(s, 1)
                            if (perspMode) {
                                // أثناء ضبط المنظور المحرر هو اللي بيرسم الخطين
                            } else if (segA != null && segB != null) {
                                listOf(segA to "A", segB to "B").forEach { (g, name) ->
                                    drawLine(Amber, Offset(g[0] * w, g[1] * h), Offset(g[2] * w, g[3] * h), 4f, pathEffect = dash)
                                    val c = Offset((g[0] + g[2]) / 2f * w, (g[1] + g[3]) / 2f * h)
                                    drawCircle(Amber, 18f, c)
                                    drawIntoCanvas { it.nativeCanvas.drawText(name, c.x, c.y + 9f, abPaint) }
                                }
                            } else listOf(s.lineA to "A", s.lineB to "B").forEach { (pos, name) ->
                                val c: Offset
                                if (s.axisHorizontal) {
                                    drawLine(Amber, Offset(pos * w, 0f), Offset(pos * w, h), 4f, pathEffect = dash)
                                    c = Offset(pos * w, 34f)
                                } else {
                                    drawLine(Amber, Offset(0f, pos * h), Offset(w, pos * h), 4f, pathEffect = dash)
                                    c = Offset(34f, pos * h)
                                }
                                drawCircle(Amber, 18f, c)
                                drawIntoCanvas { it.nativeCanvas.drawText(name, c.x, c.y + 9f, abPaint) }
                            }
                            ov.boxes.forEach { b ->
                                val col = Color(b.type.color)
                                val bx = b.box.l * w
                                val by = b.box.t * h
                                drawRoundRect(
                                    col, Offset(bx, by), GSize(b.box.w * w, b.box.h * h), CornerRadius(8f),
                                    style = Stroke(if (b.state == 1) 5f else 3f, pathEffect = if (b.state == 0) dash else null)
                                )
                                val tw = labelPaint.measureText(b.label)
                                val chipH = 44f
                                val cy = (by - chipH).coerceAtLeast(0f)
                                drawRoundRect(col, Offset(bx, cy), GSize(tw + 22f, chipH), CornerRadius(10f))
                                drawIntoCanvas { it.nativeCanvas.drawText(b.label, bx + 11f, cy + chipH - 13f, labelPaint) }
                            }
                        }
                        if (perspMode) {
                            PerspectiveEditor(
                                initial = Geo.parsePts(s.homoPts) ?: Geo.defaultPts(),
                                onCommit = { p -> vm.update { it.copy(homoPts = Geo.encodePts(p)) } },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                if (visible) {
                    val ov = ovState.value
                    // شريط المعلومات: سطر أساسي واحد + سطر تفاصيل واحد + تحذير (لو فيه)، كله سطر واحد مقصوص
                    Column(
                        Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Pill(if (running) "يعمل" else "متوقف", if (running) Color(0xFF2E7D32) else Color(0xFF616161))
                            Pill("FPS ${ov.fps}", Color(0xFF263238))
                            Pill("عدّ ${ov.sessionCount}", Color(0xFF263238))
                        }
                        val info = buildList {
                            add("كشف ${ov.detMs}ms")
                            add(if (ov.gpu) "GPU" else "CPU")
                            if (Geo.active(s)) add("منظور")
                            if (s.sourceMode == 1) add("شبكة: " + streamStatus.ifBlank { "—" })
                        }.joinToString(" • ")
                        Pill(info, Color(0xFF263238))
                        if (health.message.isNotEmpty()) Pill("⚠ ${health.message}", Color(if (health.level >= 2) 0xFFB71C1C else 0xFFE65100))
                    }
                    if (ov.modelMissing) {
                        Text(
                            "ملف الموديل غير موجود\nضع efficientdet_lite0.tflite أو vehicles_custom.tflite في app/src/main/assets",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                                .clip(RoundedCornerShape(12.dp)).background(Color(0xE6B71C1C)).padding(14.dp)
                        )
                    }
                    if (ov.lastEvent.isNotEmpty() && !perspMode) {
                        Text(
                            ov.lastEvent,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)
                                .clip(RoundedCornerShape(16.dp)).background(Color(0xE6102A43))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // لوحة التحكم: صف الأزرار الأساسي ثابت دايماً، واللوحات الإضافية ليها أقصى ارتفاع وبتتمرر جواها
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(!s.countAll && !s.fastMode, { vm.update { it.copy(countAll = false, fastMode = false) } }, { Text("قياس السرعة") })
                        FilterChip(s.countAll && !s.fastMode, { vm.update { it.copy(countAll = true, fastMode = false) } }, { Text("عدّ كل العربيات") })
                        FilterChip(s.fastMode, { vm.update { it.copy(fastMode = true, countAll = false) } }, { Text("وضع سريع") })
                    }
                    if (s.countAll || s.fastMode) {
                        Text(
                            if (s.fastMode) "بيراقب الحركة عند الخطين بسرعة الكاميرا، والموديل بيحدد النوع لحظة العبور بس. الأفضل اتجاه واحد وموبايل ثابت."
                            else "بتتعدّ العربية أول ما تعدّي أي خط من الخطين (بدون حساب سرعة).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp)
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { vm.setRunning(!running) },
                            colors = if (running) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            else ButtonDefaults.buttonColors(),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (running) "إيقاف" else "بدء", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = { showCalib = !showCalib; if (showCalib) perspMode = false },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (showCalib) "إخفاء" else "معايرة", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = { perspMode = !perspMode; if (perspMode) showCalib = false },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.GridOn, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (perspMode) "تم" else "منظور", maxLines = 1, softWrap = false)
                        }
                    }
                    if (perspMode) {
                        Column(
                            Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        ) {
                            PerspectivePanel(s, vm::update)
                        }
                    }
                    if (showCalib) {
                        Column(
                            Modifier.heightIn(max = panelMax).verticalScroll(rememberScrollState())
                                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        ) {
                            CalibrationControls(s, vm::update, fpsSlow)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelSmall,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

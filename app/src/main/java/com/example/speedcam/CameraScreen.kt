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
    val stab by vm.engine.stability.state.collectAsStateWithLifecycle()
    val iq by vm.engine.quality.state.collectAsStateWithLifecycle()
    val accTest by vm.engine.accTest.state.collectAsStateWithLifecycle()
    val scale by vm.engine.scaleCheck.state.collectAsStateWithLifecycle()
    val coll by vm.engine.collector.state.collectAsStateWithLifecycle()
    var moveDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(stab.level) { if (stab.level < 2) moveDismissed = false }
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
            Text(tr(R.string.str_0040), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(tr(R.string.str_0041), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { BtnText(tr(R.string.str_0042)) }
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
                            // منطقة الكشف (لو القص شغّال): إطار سماوي رفيع
                            if (!perspMode) DetectionZone.compute(s, ov.aspect)?.let { z ->
                                drawRect(
                                    Color(0xAA00E5FF), Offset(z.l * w, z.t * h), androidx.compose.ui.geometry.Size(z.w * w, z.h * h),
                                    style = Stroke(2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f)))
                                )
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
                            Pill(if (running) tr(R.string.str_0043) else tr(R.string.str_0044), if (running) Color(0xFF2E7D32) else Color(0xFF616161))
                            Pill("FPS ${ov.fps}", Color(0xFF263238))
                            Pill(tr(R.string.str_0045, ov.sessionCount), Color(0xFF263238))
                            if (stab.hasRef && s.movedGuard) Pill(
                                tr(R.string.str_0046, stab.score),
                                when (stab.level) { 0 -> Color(0xFF2E7D32); 1 -> Color(0xFFE65100); else -> Color(0xFFB71C1C) }
                            )
                        }
                        val info = buildList {
                            add(tr(R.string.str_0047, ov.detMs))
                            add(if (ov.gpu) "GPU" else "CPU")
                            if (Geo.active(s)) add(tr(R.string.str_0048))
                            if (s.sourceMode == 1) add(tr(R.string.str_0049) + streamStatus.ifBlank { "—" })
                            if (iq.ready && iq.sharp >= 0) add(tr(R.string.iq_pill, iq.sharp))
                            if (accTest.active) add(tr(R.string.at_pill, accTest.passes.size))
                            if (s.collectOn) add(tr(R.string.tr_pill, coll.count))
                        }.joinToString(" • ")
                        Pill(info, Color(0xFF263238))
                        if (health.message.isNotEmpty()) Pill("⚠ ${health.message}", Color(if (health.level >= 2) 0xFFB71C1C else 0xFFE65100))
                        iq.message?.let { m -> if (iq.issue != 0) Pill("⚠ " + tr(m), imgQualityColor(iq)) }
                        if (scale.applicable && scale.level >= 1 && scale.n >= ScaleCheck.MIN_N) Pill(
                            "⚠ " + tr(
                                R.string.sc_pill,
                                String.format(java.util.Locale.US, "%.1f", Units.dist(scale.medianM)),
                                String.format(java.util.Locale.US, "%.1f", Units.dist(ScaleCheck.EXPECTED_M))
                            ),
                            if (scale.level >= 2) Color(0xFFB71C1C) else Color(0xFFE65100)
                        )
                    }
                    if (ov.modelMissing) {
                        Text(
                            tr(R.string.str_0050),
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp)
                                .clip(RoundedCornerShape(12.dp)).background(Color(0xE6B71C1C)).padding(14.dp)
                        )
                    }
                    if (stab.level == 2 && s.movedGuard && !moveDismissed && !perspMode) {
                        Column(
                            Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 56.dp)
                                .clip(RoundedCornerShape(14.dp)).background(Color(0xF0B71C1C)).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("⚠ " + stab.message, color = Color.White, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.engine.stability.captureReference() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C)),
                                    contentPadding = BtnPadding, modifier = Modifier.weight(1f)
                                ) { BtnText(tr(R.string.str_0051)) }
                                OutlinedButton(
                                    onClick = { moveDismissed = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    contentPadding = BtnPadding, modifier = Modifier.weight(1f)
                                ) { BtnText(tr(R.string.str_0052)) }
                            }
                        }
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
                        FilterChip(!s.countAll && !s.fastMode, { vm.update { it.copy(countAll = false, fastMode = false) } }, { Text(tr(R.string.str_0053)) })
                        FilterChip(s.countAll && !s.fastMode, { vm.update { it.copy(countAll = true, fastMode = false) } }, { Text(tr(R.string.str_0054)) })
                        FilterChip(s.fastMode, { vm.update { it.copy(fastMode = true, countAll = false) } }, { Text(tr(R.string.str_0055)) })
                    }
                    if (s.countAll || s.fastMode) {
                        Text(
                            if (s.fastMode) tr(R.string.str_0056)
                            else tr(R.string.str_0057),
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
                            Text(if (running) tr(R.string.str_0058) else tr(R.string.str_0059), maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = { showCalib = !showCalib; perspMode = false },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Tune, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (showCalib) tr(R.string.str_0060) else tr(R.string.str_0061), maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = { perspMode = !perspMode; if (perspMode) showCalib = false },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.GridOn, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (perspMode) tr(R.string.str_0006) else tr(R.string.str_0048), maxLines = 1, softWrap = false)
                        }
                    }
                    if (perspMode && !showCalib) {
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
                            CalibrationWizard(
                                vm, s, fpsSlow,
                                onPerspMode = { perspMode = it },
                                onClose = { showCalib = false; perspMode = false }
                            )
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

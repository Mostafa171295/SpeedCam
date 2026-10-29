package com.example.speedcam

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.Locale

private val ColA = Color(0xFFFFA000)
private val ColB = Color(0xFF00C853)

/**
 * شاشة ضبط المنظور فوق المعاينة: 4 نقط بتتسحب بالصباع على أركان مستطيل على الطريق.
 *  النقطتين 1 و2 = الخط A (من جنب الطريق للجنب التاني)، والنقطتين 4 و3 = الخط B (بنفس الترتيب).
 * initial = 8 أرقام مُطبّعة. onCommit بيتنادى لما تسيب الصباع.
 */
@Composable
fun PerspectiveEditor(initial: FloatArray, onCommit: (FloatArray) -> Unit, modifier: Modifier = Modifier) {
    var pts by remember(initial.joinToString(",")) { mutableStateOf(initial.copyOf()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableIntStateOf(-1) }
    val commit by rememberUpdatedState(onCommit)
    val numPaint = remember {
        Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 30f
            isAntiAlias = true
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
    }

    Canvas(
        modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { o ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        var best = -1
                        var bd = 90f * 90f
                        for (i in 0 until 4) {
                            val dx = pts[2 * i] * w - o.x
                            val dy = pts[2 * i + 1] * h - o.y
                            val d = dx * dx + dy * dy
                            if (d < bd) { bd = d; best = i }
                        }
                        active = best
                    },
                    onDrag = { change, drag ->
                        val a = active
                        if (a >= 0 && size.width > 0 && size.height > 0) {
                            change.consume()
                            val np = pts.copyOf()
                            np[2 * a] = (np[2 * a] + drag.x / size.width).coerceIn(0.01f, 0.99f)
                            np[2 * a + 1] = (np[2 * a + 1] + drag.y / size.height).coerceIn(0.01f, 0.99f)
                            pts = np
                        }
                    },
                    onDragEnd = {
                        if (active >= 0) commit(pts)
                        active = -1
                    },
                    onDragCancel = { active = -1 }
                )
            }
    ) {
        val w = size.width
        val h = size.height
        val p = pts
        val valid = Homography.isConvex(p)
        val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))

        val quad = Path().apply {
            moveTo(p[0] * w, p[1] * h)
            lineTo(p[2] * w, p[3] * h)
            lineTo(p[4] * w, p[5] * h)
            lineTo(p[6] * w, p[7] * h)
            close()
        }
        drawPath(quad, if (valid) Color(0x3300C853) else Color(0x33FF1744))
        drawPath(quad, if (valid) Color(0xAAFFFFFF) else Color(0xFFFF1744), style = Stroke(2f, pathEffect = dash))
        // الخط A (1→2) والخط B (4→3)
        drawLine(ColA, Offset(p[0] * w, p[1] * h), Offset(p[2] * w, p[3] * h), 6f)
        drawLine(ColB, Offset(p[6] * w, p[7] * h), Offset(p[4] * w, p[5] * h), 6f)

        for (i in 0 until 4) {
            val c = Offset(p[2 * i] * w, p[2 * i + 1] * h)
            val col = if (i < 2) ColA else ColB
            drawCircle(Color.White, if (i == active) 34f else 28f, c)
            drawCircle(col, if (i == active) 28f else 22f, c)
            drawIntoCanvas { it.nativeCanvas.drawText("${i + 1}", c.x, c.y + 10f, numPaint) }
        }
        // تسمية الخطين
        drawIntoCanvas {
            numPaint.color = android.graphics.Color.WHITE
            it.nativeCanvas.drawText("A", (p[0] + p[2]) / 2f * w, (p[1] + p[3]) / 2f * h - 18f, numPaint)
            it.nativeCanvas.drawText("B", (p[6] + p[4]) / 2f * w, (p[7] + p[5]) / 2f * h - 18f, numPaint)
            numPaint.color = android.graphics.Color.BLACK
        }
    }
}

@Composable
private fun PerspSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Bold)
        Switch(checked, onChange)
    }
}

/**
 * لوحة تحكم تحويل المنظور: تفعيل/إيقاف، المسافة بين الخطين على الأرض، وإعادة النقط للوضع الافتراضي.
 * (ضبط النقط نفسها من تبويب الكاميرا ← زرار "المنظور".)
 */
@Composable
fun PerspectivePanel(
    s: AppSettings,
    update: ((AppSettings) -> AppSettings) -> Unit,
    editorHint: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PerspSwitchRow("استخدام تحويل المنظور (يلغي فرق السرعة بين الحارات)", s.homoOn) { on ->
            update {
                it.copy(
                    homoOn = on,
                    homoPts = if (on && Geo.parsePts(it.homoPts) == null) Geo.encodePts(Geo.defaultPts()) else it.homoPts
                )
            }
        }
        Text(
            if (editorHint)
                "1) علّم على الطريق 4 علامات (مثلاً 4 قمع) على أركان مستطيل: خطين متوازيين عرض الطريق كله.\n" +
                    "2) قس المسافة بين الخطين على طول الطريق واكتبها تحت.\n" +
                    "3) اسحب النقط 1 و2 على الخط الأول (A) والنقط 4 و3 على الخط التاني (B)، كل نقطة قصاد اللي بعدها."
            else
                "ضبط النقط من تبويب الكاميرا ← زرار \"المنظور\".",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var txt by remember { mutableStateOf(String.format(Locale.US, "%.1f", s.distanceM)) }
        OutlinedTextField(
            value = txt,
            onValueChange = { v ->
                txt = v
                v.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let { d -> update { it.copy(distanceM = d) } }
            },
            label = { Text("المسافة الحقيقية بين الخطين A و B على طول الطريق (متر)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (s.homoOn && Geo.invalid(s)) {
            Text(
                "⚠ النقط مش مكوّنة شكل رباعي صالح (لازم محدّب وغير منبعج). عدّلها، ولحد ما تتظبط التطبيق بيستخدم الخطين العاديين.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
            )
        } else if (s.homoOn) {
            Text(
                "✓ تحويل المنظور شغال. السرعة بتتحسب على الأرض من الخط A للخط B، فالحارة القريبة والبعيدة بيطلعوا بنفس الدقة.",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall
            )
        }
        Text("تصحيح تشوه العدسة (k1): " + String.format(Locale.US, "%.2f", s.lensK1), fontWeight = FontWeight.Bold)
        Slider(
            value = s.lensK1,
            onValueChange = { v -> update { it.copy(lensK1 = (Math.round(v * 100f) / 100f)) } },
            valueRange = -0.30f..0.30f
        )
        Text(
            "سيبه على 0 لو مش متأكد. التشوه بيأثر على الأطراف، فالأسهل إن الخطين A وB يبقوا في الثلثين الأوسطين من الصورة. " +
                "لو قيست دقة أحسن بقيمة معينة في المعايرة بعربية معروفة السرعة، استخدمها.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { update { it.copy(homoPts = Geo.encodePts(Geo.defaultPts())) } }) { Text("إعادة النقط") }
        }
    }
}

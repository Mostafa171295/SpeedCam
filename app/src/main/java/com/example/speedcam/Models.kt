package com.example.speedcam

enum class VehicleType(val ar: String, val color: Long) {
    CAR("ملاكي", 0xFF2979FF), MICROBUS("ميكروباص", 0xFF00BFA5), TUKTUK("توك توك", 0xFFF59E0B),
    MOTORCYCLE("دراجة نارية", 0xFFAB47BC), BUS("أتوبيس", 0xFFF4511E), TRUCK("نقل", 0xFF6D4C41);

    companion object {
        /** يدعم أسماء COCO الجاهزة وأسماء الموديل المخصص. */
        fun fromLabel(label: String): VehicleType? =
            when (label.lowercase().trim().replace('-', '_').replace(' ', '_')) {
                "car", "sedan", "taxi" -> CAR
                "microbus", "micro_bus", "minibus", "mini_bus", "van", "minivan" -> MICROBUS
                "tuktuk", "tuk_tuk", "toktok", "tricycle", "rickshaw", "auto_rickshaw" -> TUKTUK
                "motorcycle", "motorbike" -> MOTORCYCLE
                "bus" -> BUS
                "truck", "pickup", "lorry" -> TRUCK
                else -> null
            }

        fun fromName(name: String): VehicleType? = entries.firstOrNull { it.name == name }
    }
}

/** مربع بإحداثيات مُطبّعة (0..1) */
data class NBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
}

data class Det(val type: VehicleType, val box: NBox, val score: Float)

/** قيمة السرعة للمركبة المعدودة فقط (وضع عدّ كل العربيات) بدون قياس سرعة. */
const val NO_SPEED = -1.0

/** direction: 1 = الحركة في اتجاه زيادة الإحداثي (يمين/أسفل الصورة)، 2 = العكس */
/** conf: ثقة قراءة السرعة 1..100 (0 = غير معروفة). errKmh: هامش الخطأ المتوقع ± بالكم/س (0 = غير معروف). */
data class SpeedEvent(val trackId: Int, val type: VehicleType, val speedKmh: Double, val direction: Int, val conf: Int = 0, val errKmh: Float = 0f, val lane: Int = 0)

data class Tracked(val id: Int, val type: VehicleType, val box: NBox, val speedKmh: Double?, val a: Boolean = false, val b: Boolean = false)

/** state: 0 = قيد القياس، 1 = تم القياس */
data class OverlayBox(val box: NBox, val label: String, val state: Int, val type: VehicleType)

data class Overlay(
    val boxes: List<OverlayBox> = emptyList(),
    val aspect: Float = 16f / 9f,
    val fps: Int = 0,
    val modelMissing: Boolean = false,
    val lastEvent: String = "",
    val sessionCount: Int = 0,
    val prepMs: Int = 0,
    val detMs: Int = 0,
    val gpu: Boolean = false
)

fun directionLabel(direction: Int, axisHorizontal: Boolean): String =
    if (DirectionMode.homo) { if (direction == 1) "من A إلى B" else "من B إلى A" }
    else if (axisHorizontal) { if (direction == 1) "لليمين →" else "← لليسار" }
    else { if (direction == 1) "ناحية الكاميرا ↓" else "بعيداً عن الكاميرا ↑" }

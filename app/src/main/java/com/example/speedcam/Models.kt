package com.example.speedcam

enum class VehicleType(private val nameRes: Int, val color: Long) {
    CAR(R.string.vt_car, 0xFF2979FF), MICROBUS(R.string.vt_microbus, 0xFF00BFA5), TUKTUK(R.string.vt_tuktuk, 0xFFF59E0B),
    MOTORCYCLE(R.string.vt_motorcycle, 0xFFAB47BC), BUS(R.string.vt_bus, 0xFFF4511E), TRUCK(R.string.vt_truck, 0xFF6D4C41);

    /** الاسم المعروض بلغة التطبيق الحالية (بيتقرأ وقت الاستخدام عشان تغيير اللغة يشتغل فوراً). */
    val ar: String get() = tr(nameRes)

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
    if (DirectionMode.homo) { if (direction == 1) tr(R.string.dir_a_to_b) else tr(R.string.dir_b_to_a) }
    else if (axisHorizontal) { if (direction == 1) tr(R.string.dir_right) else tr(R.string.dir_left) }
    else { if (direction == 1) tr(R.string.dir_toward) else tr(R.string.dir_away) }

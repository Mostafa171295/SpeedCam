package com.example.speedcam

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Comparator
import java.util.Date
import java.util.Locale
import java.util.TreeMap

/** عدد المركبات من كل نوع داخل فترة (ساعة أو يوم). counts مرتبة بترتيب VehicleType.entries */
class Bucket(val startMs: Long, val counts: IntArray) {
    val total: Int get() = counts.sum()
}

/** تجميع السجلات في فترات، الأحدث أولاً. */
fun bucketize(records: List<SpeedRecord>, byDay: Boolean): List<Bucket> {
    val map = TreeMap<Long, IntArray>(Comparator.reverseOrder<Long>())
    val cal = Calendar.getInstance()
    for (r in records) {
        val idx = VehicleType.fromName(r.vehicleType)?.ordinal ?: continue
        cal.timeInMillis = r.timestamp
        if (byDay) cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        map.getOrPut(cal.timeInMillis) { IntArray(VehicleType.entries.size) }[idx]++
    }
    return map.map { Bucket(it.key, it.value) }
}

fun bucketLabel(startMs: Long, byDay: Boolean): String {
    val day = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(startMs))
    if (byDay) return day
    val cal = Calendar.getInstance().apply { timeInMillis = startMs }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    return String.format(Locale.US, "%s   %02d:00 - %02d:00", day, h, (h + 1) % 24)
}

package com.example.speedcam

import android.app.Application
import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * نظام الوحدات. كل الحسابات والتخزين الداخلي بالكم/س والمتر، والتحويل بيحصل وقت العرض ووقت إدخال المستخدم بس.
 * AUTO = إنجليزي (ميل/س وقدم) في أمريكا وليبيريا وميانمار، ومتري في الباقي.
 */
object Units {
    const val AUTO = "auto"
    const val METRIC = "metric"
    const val IMPERIAL = "imperial"

    private const val KMH_PER_MPH = 1.609344
    private const val FT_PER_M = 3.280839895

    private const val PREFS = "app_units"
    private lateinit var app: Application

    @Volatile var mode: String = AUTO
        private set

    fun init(a: Application) {
        app = a
        mode = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", AUTO) ?: AUTO
    }

    fun set(newMode: String) {
        mode = newMode
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", newMode).apply()
    }

    val imperial: Boolean
        get() = when (mode) {
            IMPERIAL -> true
            METRIC -> false
            else -> Locale.getDefault().country in setOf("US", "LR", "MM")
        }

    // ---------- سرعة ----------
    fun spd(kmh: Number): Double = if (imperial) kmh.toDouble() / KMH_PER_MPH else kmh.toDouble()
    fun spdI(kmh: Number): Int = spd(kmh).roundToInt()
    fun toKmh(shown: Number): Double = if (imperial) shown.toDouble() * KMH_PER_MPH else shown.toDouble()

    // ---------- مسافة ----------
    fun dist(m: Number): Double = if (imperial) m.toDouble() * FT_PER_M else m.toDouble()
    fun distI(m: Number): Int = dist(m).roundToInt()
    fun toMeters(shown: Number): Double = if (imperial) shown.toDouble() / FT_PER_M else shown.toDouble()

    /** رقم مختصر للعرض: صحيح لو قريب من صحيح، وغير كده خانة عشرية واحدة. */
    fun fmt(v: Double): String =
        if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

    /** نص حقل الإدخال: القيمة بالوحدة المعروضة بخانة عشرية واحدة. */
    fun field(shown: Double): String = String.format(Locale.US, "%.1f", shown)

    fun speedLabel(): String = AppLang.context().getString(if (imperial) R.string.unit_mph else R.string.unit_kmh)
    fun distLabel(): String = AppLang.context().getString(if (imperial) R.string.unit_ft else R.string.unit_m)
}

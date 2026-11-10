package com.example.speedcam

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * لغة التطبيق: "auto" (لغة الجهاز لو عربي، غير كده إنجليزي) أو "ar" أو "en".
 * الاختيار بيتحفظ، والنصوص كلها بتتقرأ من الموارد عن طريق tr() حتى من خارج الـ Compose (المحرك، الإشعارات، التصدير).
 */
object AppLang {
    const val AUTO = "auto"
    const val AR = "ar"
    const val EN = "en"

    private const val PREFS = "app_lang"
    private const val KEY = "code"

    private lateinit var app: Application
    @Volatile var code: String = AUTO
        private set
    @Volatile private var localized: Context? = null

    fun init(a: Application) {
        app = a
        code = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO
        rebuild()
    }

    /** اللغة الفعلية بعد حل "تلقائي". */
    fun locale(): Locale = when (code) {
        AR -> Locale(AR)
        EN -> Locale(EN)
        else -> if (Locale.getDefault().language == AR) Locale(AR) else Locale(EN)
    }

    fun isRtl(): Boolean = locale().language == AR

    /** Context بلغة التطبيق، للنصوص اللي بتتقرأ خارج الـ Activity. */
    fun context(): Context = localized ?: app

    /** للـ Activity.attachBaseContext عشان الـ Compose والـ Views يمشوا بنفس اللغة. */
    fun wrap(base: Context): Context {
        val loc = locale()
        Locale.setDefault(loc)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        return base.createConfigurationContext(cfg)
    }

    /** يحفظ اللغة الجديدة ويحدّث الـ context. بعدها الـ Activity لازم تعمل recreate(). */
    fun set(newCode: String) {
        code = newCode
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, newCode).apply()
        rebuild()
    }

    private fun rebuild() {
        localized = wrap(app)
    }
}

// {spd} / {dst} = اسم وحدة السرعة / المسافة الحالية. {spd:80} / {dst:80} = رقم ثابت (بالكم/س أو المتر) متحوّل ومعاه الوحدة.
private val UNIT_TOKEN = Regex("\\{(spd|dst)(?::([0-9.]+))?\\}")

private fun applyUnits(s: String): String = UNIT_TOKEN.replace(s) { m ->
    val speed = m.groupValues[1] == "spd"
    val label = if (speed) Units.speedLabel() else Units.distLabel()
    val num = m.groupValues[2]
    if (num.isEmpty()) label
    else Units.fmt(if (speed) Units.spd(num.toDouble()) else Units.dist(num.toDouble())) + " " + label
}

/** نص مترجم من الموارد. مع args بيطبّق String.format (وكمان لو النص فيه % عشان %% تطلع %)، وبعدها بيحوّل رموز الوحدات. */
fun tr(@StringRes id: Int, vararg args: Any?): String {
    val raw = AppLang.context().getString(id)
    val formatted = if (args.isEmpty() && !raw.contains('%')) raw else String.format(AppLang.locale(), raw, *args)
    return if (formatted.contains('{')) applyUnits(formatted) else formatted
}

package com.example.speedcam

import java.util.Locale

/**
 * صيغة لوحات بلد معيّن: لغة الـ OCR (Tesseract)، الحروف المسموحة، وقاعدة التحقق من النص المقروء.
 * القراءات اللي ما بتطابقش الصيغة المختارة بتتهمل، وده بيقلل القراءات الغلط (أسماء الماركات، كتابات على الشاسيه، إلخ).
 *
 * التخزين: اللوحة العربية (مصر) بتتخزن حروف في plateLetters وأرقام في plateDigits.
 * الصيغ اللاتينية بتتخزن كاملة (حروف وأرقام مع بعض) في plateDigits وplateLetters فاضي،
 * عشان قاعدة البيانات والتجميع والتصدير يفضلوا شغالين من غير تغيير في الجداول.
 */
class PlateProfile(
    val id: String,
    val nameRes: Int,
    /** لغة Tesseract: ملف <lang>.traineddata */
    val ocrLang: String,
    /** الحروف اللي المحرك يقتصر عليها (null = من غير تقييد) */
    val whitelist: String?,
    /** قاعدة التحقق للصيغ اللاتينية (null = القاعدة العامة: 4 إلى 8 خانات فيها حرف ورقم) */
    private val pattern: Regex?,
    private val arabicStyle: Boolean = false
) {
    fun parse(raw: String): PlateParser.Plate? =
        if (arabicStyle) PlateParser.parse(raw) else parseLatin(raw)

    private fun clean(tok: String): String =
        tok.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }

    /**
     * بنجرّب كل كلمة، وكل كلمتين وتلاتة ورا بعض (اللوحة غالباً بتتقسم لجزئين زي "AB12 CDE")،
     * ونختار أطول نص بيطابق الصيغة.
     */
    private fun parseLatin(raw: String): PlateParser.Plate? {
        val toks = raw.split(Regex("[\\s\\-·•.,_|/\\\\]+")).map { clean(it) }.filter { it.isNotEmpty() }
        var best: String? = null
        for (i in toks.indices) {
            var acc = ""
            for (j in i until minOf(toks.size, i + 3)) {
                acc += toks[j]
                if (valid(acc) && (best == null || acc.length > best.length)) best = acc
            }
        }
        return best?.let { PlateParser.Plate("", it) }
    }

    private fun valid(s: String): Boolean {
        if (pattern != null) return pattern.matches(s)
        // عامة: 4 إلى 8 خانات وفيها حرف ورقم على الأقل (يستبعد أرقام العناوين وأسماء الماركات)
        return s.length in 4..8 && s.any { it in '0'..'9' } && s.any { it in 'A'..'Z' }
    }

    companion object {
        const val AUTO = "auto"
        private const val LATIN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        val EG = PlateProfile("eg", R.string.plate_fmt_eg, "ara", null, null, arabicStyle = true)
        val LATIN = PlateProfile("latin", R.string.plate_fmt_latin, "eng", LATIN_CHARS, null)
        val UK = PlateProfile("uk", R.string.plate_fmt_uk, "eng", LATIN_CHARS, Regex("[A-Z]{2}[0-9]{2}[A-Z]{3}"))
        val FR = PlateProfile("fr", R.string.plate_fmt_fr, "eng", LATIN_CHARS, Regex("[A-Z]{2}[0-9]{3}[A-Z]{2}"))
        val DE = PlateProfile("de", R.string.plate_fmt_de, "eng", LATIN_CHARS, Regex("[A-Z]{1,3}[A-Z]{1,2}[0-9]{1,4}[EH]?"))
        val ES = PlateProfile("es", R.string.plate_fmt_es, "eng", LATIN_CHARS, Regex("[0-9]{4}[A-Z]{3}"))
        val IT = PlateProfile("it", R.string.plate_fmt_it, "eng", LATIN_CHARS, Regex("[A-Z]{2}[0-9]{3}[A-Z]{2}"))

        /** الصيغ المتاحة للاختيار (ترتيب العرض). لإضافة بلد جديد: عرّف صيغة هنا وضيف اسمها في strings.xml. */
        val ALL: List<PlateProfile> = listOf(EG, LATIN, UK, FR, DE, ES, IT)

        /** "auto": لو لغة الجهاز عربي نستخدم الصيغة العربية، غير كده اللاتينية العامة. */
        fun resolve(id: String): PlateProfile =
            ALL.firstOrNull { it.id == id } ?: if (Locale.getDefault().language == "ar") EG else LATIN
    }
}

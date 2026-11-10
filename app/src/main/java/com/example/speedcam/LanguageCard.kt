package com.example.speedcam

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** اختيار لغة التطبيق. التغيير بيعيد رسم الشاشة فوراً. */
@Composable
fun LanguageCard() {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(AppLang.code) }
    AppCard {
        SectionTitle(Icons.Filled.Info, tr(R.string.lang_title))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                AppLang.AUTO to tr(R.string.lang_auto),
                AppLang.AR to tr(R.string.lang_ar),
                AppLang.EN to tr(R.string.lang_en)
            ).forEach { (code, label) ->
                FilterChip(current == code, {
                    if (current != code) {
                        current = code
                        AppLang.set(code)
                        (ctx as? Activity)?.recreate()
                    }
                }, { Text(label) })
            }
        }
    }
    var units by remember { mutableStateOf(Units.mode) }
    AppCard {
        SectionTitle(Icons.Filled.Info, tr(R.string.units_title))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Units.AUTO to tr(R.string.units_auto),
                Units.METRIC to tr(R.string.units_metric),
                Units.IMPERIAL to tr(R.string.units_imperial)
            ).forEach { (mode, label) ->
                FilterChip(units == mode, {
                    if (units != mode) {
                        units = mode
                        Units.set(mode)
                        (ctx as? Activity)?.recreate()   // عشان كل الشاشات تتعرض بالوحدة الجديدة
                    }
                }, { Text(label) })
            }
        }
    }
}

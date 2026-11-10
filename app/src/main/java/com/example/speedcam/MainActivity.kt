package com.example.speedcam

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    // الكاميرا شغالة طول ما التطبيق ظاهر (أي تبويب)، ولو الخدمة الخلفية مفعّلة بتكمّل بعد كده
    override fun onStart() {
        super.onStart()
        Core.get(this).host.setActivityVisible(true)
    }

    override fun onStop() {
        super.onStop()
        Core.get(this).host.setActivityVisible(false)
    }

    // اللغة المختارة (ومعها اتجاه الشاشة) بتتطبق هنا على الـ Activity كلها
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpeedCamTheme {
                CompositionLocalProvider(LocalLayoutDirection provides if (AppLang.isRtl()) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    AppRoot(vm)
                }
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Videocam, null) }, label = { Text(tr(R.string.str_0125)) })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Speed, null) }, label = { Text(tr(R.string.str_0126)) })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.CreditCard, null) }, label = { Text(tr(R.string.str_0127)) })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.BarChart, null) }, label = { Text(tr(R.string.str_0128)) })
                NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Filled.Settings, null) }, label = { Text(tr(R.string.str_0129)) })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            // شاشة الكاميرا بتفضل موجودة تحت باقي التبويبات (مش بتتمسح) عشان المعاينة ماتتقطعش وما يبقاش فيه رعشة وانت بتتنقل
            CameraScreen(vm, visible = tab == 0)
            if (tab != 0) {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(Unit) { detectTapGestures { } }
                ) {
                    when (tab) {
                        1 -> VehiclesScreen(vm)
                        2 -> PlateScreen(vm)
                        3 -> StatsScreen(vm)
                        else -> SettingsScreen(vm)
                    }
                }
            }
        }
    }
}

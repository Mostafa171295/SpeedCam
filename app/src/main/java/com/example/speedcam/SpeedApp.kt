package com.example.speedcam

import android.app.Application

class SpeedApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLang.init(this)
        Units.init(this)
    }
}

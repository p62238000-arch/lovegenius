package com.lovegenius.assistant

import android.app.Application
import com.lovegenius.assistant.data.Prefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}

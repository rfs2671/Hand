package com.hand.bridge

import android.app.Application

class HandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

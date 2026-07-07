package com.migu.cloudconsole

import android.app.Application

class MiguApplication : Application() {
    lateinit var automationEngine: AutomationEngine
        private set

    override fun onCreate() {
        super.onCreate()
        automationEngine = AutomationEngine(this)
    }
}

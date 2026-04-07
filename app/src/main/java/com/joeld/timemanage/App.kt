package com.joeld.timemanage

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        TimerStore.load(this)
        LocationStore.load(this)
    }

    companion object {
        var instance: App? = null
            private set
    }
}

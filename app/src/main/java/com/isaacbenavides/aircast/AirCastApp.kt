package com.isaacbenavides.aircast

import android.app.Application
import com.isaacbenavides.aircast.core.di.ServiceLocator

class AirCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

package dev.romirom.tivibro

import android.app.Application

class TiViBroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        L10n.init(this)
    }
}

package dev.defter.app

import android.app.Application
import dev.defter.app.localization.AppLanguage

open class DefterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
    }
}

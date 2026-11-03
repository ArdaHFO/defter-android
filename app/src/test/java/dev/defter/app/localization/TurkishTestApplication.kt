package dev.defter.app.localization

import dev.defter.app.DefterApplication

/** Existing Turkish scenarios explicitly choose Turkish; production always defaults to English. */
class TurkishTestApplication : DefterApplication() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.set(this, "tr")
    }
}

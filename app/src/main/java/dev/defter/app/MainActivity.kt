package dev.defter.app

import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import dev.defter.app.ui.DefterApp
import dev.defter.app.audio.AudioViewModel
import dev.defter.app.localization.AppLanguage

class MainActivity : ComponentActivity() {
    private var attachedLanguage = "en"
    private val model: DefterViewModel by viewModels()
    private val audioModel: AudioViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        attachedLanguage = AppLanguage.tag(newBase)
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        // Another Activity (for example a share target) may have changed the preference.
        if (attachedLanguage != AppLanguage.tag(this) && !isFinishing && !isChangingConfigurations) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        if (savedInstanceState == null) model.acceptShare(intent)
        setContent { DefterApp(model, audioModel) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.acceptShare(intent)
    }
}

package dev.defter.app.localization

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Application language is an explicit preference, independent of the device language. */
object AppLanguage {
    private const val PREFERENCES = "defter_language"
    private const val KEY = "language"
    private var application: Context? = null
    private var localized: Context? = null
    private var localizedTag: String? = null
    var selectedTag by mutableStateOf("en")
        private set

    fun tag(context: Context): String = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getString(KEY, "en").let { if (it == "tr") "tr" else "en" }

    fun initialize(context: Context) {
        application = context.applicationContext ?: context
        localized = null
        selectedTag = tag(context)
    }

    fun set(context: Context, tag: String) {
        require(tag in setOf("en", "tr")) { "Unsupported application language" }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        application = context.applicationContext ?: context
        localized = null
        selectedTag = tag
    }

    fun wrap(context: Context): Context {
        val locale = Locale.forLanguageTag(tag(context))
        // Override only language. Copying the full configuration freezes font scale and window size.
        return context.createConfigurationContext(Configuration().apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
        })
    }

    internal fun text(key: String, args: Array<out Any>): String {
        val entry = TranslationCatalog.entries[key]
        val resources = localizedContext()
        val template = if (entry != null && resources != null) resources.getString(entry.resource)
            else entry?.english ?: key
        return if (args.isEmpty()) template else String.format(Locale.forLanguageTag(selectedTag), template, *args)
    }

    internal fun quantity(key: String, count: Int, args: Array<out Any>): String {
        val entry = QuantityCatalog.entries[key] ?: return text(key, arrayOf(count, *args))
        val template = localizedContext()?.resources?.getQuantityString(entry.resource, count)
            ?: if (count == 1) entry.englishOne else entry.englishOther
        return String.format(Locale.forLanguageTag(selectedTag), template, count, *args)
    }

    private fun localizedContext(): Context? {
        val context = application
        val language = selectedTag
        return if (context != null) synchronized(this) {
            if (localized == null || localizedTag != language) {
                localized = wrap(context)
                localizedTag = language
            }
            localized
        } else null
    }
}

/** Translate application-owned text only. Note titles, handwriting and transcripts are user data. */
fun tr(key: String, vararg args: Any): String = AppLanguage.text(key, args)

/** Android chooses the correct grammatical form; the first format argument is always the count. */
fun quantity(key: String, count: Int, vararg args: Any): String = AppLanguage.quantity(key, count, args)

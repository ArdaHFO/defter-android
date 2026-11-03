package dev.defter.app.localization

import android.content.Context
import dev.defter.app.DefterApplication
import dev.defter.app.data.DocumentJson
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** A synthetic clean install, without changing the production default or system language. */
class FreshInstallApplication : DefterApplication() {
    var incomingDeviceLanguage: String? = null
        private set
    override fun attachBaseContext(base: Context) {
        incomingDeviceLanguage = base.resources.configuration.locales[0].language
        base.getSharedPreferences("defter_language", Context.MODE_PRIVATE).edit().clear().commit()
        super.attachBaseContext(base)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "tr-rTR", application = FreshInstallApplication::class)
class LocalizationTest {
    private fun app() = RuntimeEnvironment.getApplication() as FreshInstallApplication

    @Before fun describeResourceConfiguration() {
        val wrapped = AppLanguage.wrap(app())
        println("Locale fixture device=${app().incomingDeviceLanguage}, preference=${AppLanguage.tag(app())}, " +
            "application=${app().resources.configuration.locales}, base=${app().baseContext.resources.configuration.locales}, " +
            "wrapped=${wrapped.resources.configuration.locales}, sameResources=${wrapped.resources === app().resources}, save=${tr("Kaydet")}")
    }

    @Test fun cleanInstallUsesEnglishEvenWhenDeviceLanguageIsTurkish() {
        assertEquals("tr", app().incomingDeviceLanguage)
        assertEquals("en", AppLanguage.tag(app()))
        // Application's raw context belongs to Android. App text uses a localized context,
        // while MainActivity supplies that locale to platform and Compose controls as well.
        assertEquals("en", AppLanguage.wrap(app()).resources.configuration.locales[0].language)
        assertEquals("Save", tr("Kaydet"))
        assertEquals("Pen", DrawingTool.PEN.label)
        assertEquals("Lined", PaperTemplate.LINED.label)
    }

    @Test fun explicitSelectionPersistsAndAllLabelsUseTheSelectedLanguage() {
        AppLanguage.set(app(), "tr")
        AppLanguage.initialize(app())
        assertEquals("tr", AppLanguage.tag(app()))
        assertEquals("tr", AppLanguage.wrap(app()).resources.configuration.locales[0].language)
        assertEquals("Kaydet", tr("Kaydet"))
        assertEquals("Kalem", DrawingTool.PEN.label)
        assertEquals("2 seçildi", tr("%1\$d seçildi", 2))
        AppLanguage.set(app(), "en")
        assertEquals("Save", tr("Kaydet"))
        assertEquals("2 selected", tr("%1\$d seçildi", 2))
        assertThrows(IllegalArgumentException::class.java) { AppLanguage.set(app(), "de") }
        assertEquals("en", AppLanguage.tag(app()))
    }

    @Test fun changingLanguageNeverRewritesUserTextOrDocumentValues() {
        val document = PageDocument(elements = listOf(TextElement(text = "Kaydet · İŞARET · English notes", x = 10f, y = 20f)))
        val original = DocumentJson.pageToJson(document).toString()
        AppLanguage.set(app(), "tr")
        assertEquals(original, DocumentJson.pageToJson(document).toString())
        AppLanguage.set(app(), "en")
        assertEquals(original, DocumentJson.pageToJson(document).toString())
        assertEquals("Kaydet · İŞARET · English notes", (document.elements.single() as TextElement).text)
    }

    @Test fun everyCatalogEntryHasMatchingAndroidResourcesAndFormatArguments() {
        val formats = Regex("%(\\d+)\\\$[a-zA-Z]")
        for (tag in listOf("en", "tr")) {
            AppLanguage.set(app(), tag)
            val resources = AppLanguage.wrap(app()).resources
            for ((key, entry) in TranslationCatalog.entries) {
                val expected = if (tag == "en") entry.english else key
                assertEquals("Resource mismatch for $tag / $key", expected, resources.getString(entry.resource))
                assertEquals("Format mismatch for $key", formats.findAll(key).map { it.value }.toList().sorted(),
                    formats.findAll(entry.english).map { it.value }.toList().sorted())
            }
        }
    }

    @Test fun countResourcesUseEnglishSingularAndPluralWithoutChangingTurkish() {
        for (tag in listOf("en", "tr")) {
            AppLanguage.set(app(), tag)
            for ((key, entry) in QuantityCatalog.entries) {
                val extra: Array<out Any> = if (key.contains("%2\$s")) arrayOf("3 Nov") else emptyArray()
                for (count in listOf(1, 2)) {
                    val expected = if (tag == "tr") key else if (count == 1) entry.englishOne else entry.englishOther
                    assertEquals("Quantity mismatch for $tag / $count / $key",
                        String.format(Locale.ROOT, expected, count, *extra), quantity(key, count, *extra))
                }
            }
        }
        AppLanguage.set(app(), "en")
        assertEquals("1 notebook", quantity("%1\$s defter", 1))
        assertEquals("2 notebooks", quantity("%1\$s defter", 2))
        assertEquals("1 page", quantity("%1\$s sayfa", 1))
        assertEquals("2 pages", quantity("%1\$s sayfa", 2))
        assertEquals("1 folder", quantity("%1\$s klasör", 1))
        assertEquals("2 objects", quantity("%1\$s nesne", 2))
    }
}

package dev.defter.app.data

import android.content.Context
import dev.defter.app.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class UiPreferencesStoreTest {
    private fun context() = RuntimeEnvironment.getApplication()
    @Test fun firstLaunchFollowsSystemAndKeepsBalancedPanelDefaults() {
        assertEquals(UiPreferences(), UiPreferencesStore(context()).load())
    }
    @Test fun choicesSurviveNewStoreInstancesAndNeverTouchStoredNotes() {
        val note = File(context().filesDir, "defter/pages/preserved.json").apply { parentFile!!.mkdirs(); writeText("existing note bytes") }
        val value = UiPreferences(ThemeMode.DARK, LibraryLayout.LIST, 400f, 520f, .65f)
        UiPreferencesStore(context()).save(value)
        assertEquals(value, UiPreferencesStore(context()).load())
        assertEquals("existing note bytes", note.readText())
    }
    @Test fun corruptAndNonfiniteValuesFallBackWithoutBreakingAppStartup() {
        context().getSharedPreferences("workspace_appearance", Context.MODE_PRIVATE).edit()
            .putString("theme", "unknown").putString("library_layout", "unknown")
            .putFloat("pages_width", Float.NaN).putFloat("audio_width", Float.POSITIVE_INFINITY)
            .putString("reference_share", "wrong type").commit()
        assertEquals(UiPreferences(), UiPreferencesStore(context()).load())
    }
    @Test fun outOfRangeValuesAreBoundedBeforePersistence() {
        UiPreferencesStore(context()).save(UiPreferences(pagesWidth = -10f, audioWidth = 10_000f, referenceShare = -1f))
        val value = UiPreferencesStore(context()).load()
        assertEquals(240f, value.pagesWidth, 0f); assertEquals(560f, value.audioWidth, 0f); assertEquals(.3f, value.referenceShare, 0f)
    }
}

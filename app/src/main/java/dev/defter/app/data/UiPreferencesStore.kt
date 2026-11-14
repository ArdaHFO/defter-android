package dev.defter.app.data

import android.content.Context
import dev.defter.app.model.*

/** Called on IO. Appearance never modifies the note format or stored paper/ink colors. */
internal class UiPreferencesStore(context: Context) {
    private val app = context.applicationContext
    fun load(): UiPreferences {
        val preferences = app.getSharedPreferences("workspace_appearance", Context.MODE_PRIVATE)
        return UiPreferences(
            theme = runCatching { ThemeMode.valueOf(preferences.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
            libraryLayout = runCatching { LibraryLayout.valueOf(preferences.getString("library_layout", "GRID")!!) }.getOrDefault(LibraryLayout.GRID),
            pagesWidth = runCatching { preferences.getFloat("pages_width", 304f) }.getOrDefault(304f),
            audioWidth = runCatching { preferences.getFloat("audio_width", 380f) }.getOrDefault(380f),
            referenceShare = runCatching { preferences.getFloat("reference_share", .5f) }.getOrDefault(.5f)
        ).normalized()
    }
    fun save(value: UiPreferences) {
        val next = value.normalized()
        check(app.getSharedPreferences("workspace_appearance", Context.MODE_PRIVATE).edit()
            .putString("theme", next.theme.name).putString("library_layout", next.libraryLayout.name)
            .putFloat("pages_width", next.pagesWidth).putFloat("audio_width", next.audioWidth)
            .putFloat("reference_share", next.referenceShare).commit()) { "Appearance preferences could not be saved" }
    }
}

package dev.defter.app.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class LibraryLayout { GRID, LIST }

data class UiPreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val libraryLayout: LibraryLayout = LibraryLayout.GRID,
    val pagesWidth: Float = 304f,
    val audioWidth: Float = 380f,
    val referenceShare: Float = .5f
) {
    fun normalized() = copy(
        pagesWidth = if (pagesWidth.isFinite()) pagesWidth.coerceIn(240f, 420f) else 304f,
        audioWidth = if (audioWidth.isFinite()) audioWidth.coerceIn(320f, 560f) else 380f,
        referenceShare = if (referenceShare.isFinite()) referenceShare.coerceIn(.3f, .7f) else .5f
    )
}

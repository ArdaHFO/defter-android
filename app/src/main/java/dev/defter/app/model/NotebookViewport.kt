package dev.defter.app.model

/** Geometry-only snapshot. Never contains documents, ink or bitmap references. */
data class NotebookViewport(
    val centerId: String?,
    val pages: List<PageViewportSlice>,
    val left: Float = 0f,
    val right: Float = 1f
)

data class PageViewportSlice(val id: String, val from: Float, val to: Float)

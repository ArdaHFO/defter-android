package dev.defter.app.model

import java.util.UUID
import dev.defter.app.localization.tr

fun newId(): String = UUID.randomUUID().toString()

enum class PaperTemplate(private val labelKey: String) {
    BLANK("Boş"), LINED("Çizgili"), GRID("Kareli"), DOTS("Noktalı"), CORNELL("Cornell");
    val label: String get() = tr(labelKey)
}

enum class InkKind { PEN, HIGHLIGHTER }
enum class ShapeKind { LINE, ARROW, RECTANGLE, ELLIPSE }
enum class EraserMode { STROKE, PARTIAL }
enum class EraserFilter { ALL, PEN, HIGHLIGHTER, SHAPES }
enum class DrawingTool(private val labelKey: String) {
    PEN("Kalem"), HIGHLIGHTER("Fosforlu"), ERASER("Çizgi silgisi"),
    LINE("Çizgi"), ARROW("Ok"), RECTANGLE("Dikdörtgen"), ELLIPSE("Elips"),
    SELECT("Seç / taşı"), LASSO("Kement"), HAND("Gezin");
    val label: String get() = tr(labelKey)
}

data class InkPoint(val x: Float, val y: Float, val pressure: Float = 1f)

sealed interface PageElement { val id: String }

data class StrokeElement(
    override val id: String = newId(),
    val points: List<InkPoint>,
    val color: Int = 0xFF24344B.toInt(),
    val width: Float = 3.5f,
    val kind: InkKind = InkKind.PEN,
    val isPolyline: Boolean = false,
    val isShapeFragment: Boolean = false
) : PageElement

data class ShapeElement(
    override val id: String = newId(),
    val shape: ShapeKind,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val color: Int = 0xFF24344B.toInt(),
    val width: Float = 3.5f
) : PageElement

data class ImageElement(
    override val id: String = newId(),
    val assetId: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f
) : PageElement

data class TextElement(
    override val id: String = newId(),
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float = 30f,
    val color: Int = 0xFF24344B.toInt()
) : PageElement

data class PdfBackground(val assetId: String, val pageIndex: Int, val widthPoints: Float, val heightPoints: Float)

data class PageDocument(
    val id: String = newId(),
    val template: PaperTemplate = PaperTemplate.DOTS,
    val width: Float = 1240f,
    val height: Float = 1754f,
    val backgroundColor: Int = 0xFFFFFEF9.toInt(),
    val elements: List<PageElement> = emptyList(),
    val revision: Long = 0,
    val pdfBackground: PdfBackground? = null,
    val textRecognition: String = "",
    val recognitionRevision: Long? = null,
    val recognitionLanguage: String? = null
)

data class Folder(
    val id: String = newId(),
    val name: String,
    val parentId: String? = null
)

data class Notebook(
    val id: String = newId(),
    val title: String,
    val folderId: String? = null,
    val coverColor: Int = 0xFF3D7566.toInt(),
    val pageIds: List<String>,
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    val trashedPageIds: List<String> = emptyList()
)

data class LibraryData(
    val version: Int = 3,
    val folders: List<Folder> = emptyList(),
    val notebooks: List<Notebook> = emptyList(),
    val audioItems: List<AudioItem> = emptyList()
)

data class DrawingConfig(
    val tool: DrawingTool = DrawingTool.PEN,
    val color: Int = 0xFF24344B.toInt(),
    val width: Float = 3.5f,
    val touchDrawing: Boolean = true,
    val eraserMode: EraserMode = EraserMode.STROKE,
    val eraserFilter: EraserFilter = EraserFilter.ALL,
    val shapeSnap: Boolean = false,
    val snapAngleDegrees: Float = 15f
)

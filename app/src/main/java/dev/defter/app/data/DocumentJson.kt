package dev.defter.app.data

import dev.defter.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Versioned, explicit codec. Unsupported or damaged documents are never interpreted as empty. */
internal object DocumentJson {
    const val VERSION = 3
    private val idPattern = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,100}")

    fun validId(id: String): String {
        require(idPattern.matches(id)) { "Geçersiz belge kimliği." }
        return id
    }

    fun libraryToJson(library: LibraryData): JSONObject {
        validateLibrary(library)
        return JSONObject().put("version", VERSION)
            .put("folders", JSONArray().apply { library.folders.forEach { folder ->
                put(JSONObject().put("id", folder.id).put("name", folder.name)
                    .put("parentId", folder.parentId ?: JSONObject.NULL))
            } })
            .put("audioItems", JSONArray().apply { library.audioItems.forEach { put(AudioJson.itemToJson(it)) } })
            .put("notebooks", JSONArray().apply { library.notebooks.forEach { notebook ->
                put(JSONObject().put("id", notebook.id).put("title", notebook.title)
                    .put("folderId", notebook.folderId ?: JSONObject.NULL)
                    .put("coverColor", notebook.coverColor).put("pageIds", JSONArray(notebook.pageIds))
                    .put("trashedPageIds", JSONArray(notebook.trashedPageIds))
                    .put("updatedAt", notebook.updatedAt)
                    .put("deletedAt", notebook.deletedAt ?: JSONObject.NULL))
            } })
    }

    fun libraryFromJson(json: JSONObject): LibraryData = decode {
        checkVersion(json)
        val folders = json.getJSONArray("folders").objects().map {
            Folder(it.getString("id"), it.getString("name"), it.nullableString("parentId"))
        }
        val notebooks = json.getJSONArray("notebooks").objects().map {
            Notebook(
                id = it.getString("id"), title = it.getString("title"),
                folderId = it.nullableString("folderId"), coverColor = it.getInt("coverColor"),
                pageIds = it.getJSONArray("pageIds").strings(),
                updatedAt = it.getLong("updatedAt"),
                deletedAt = if (it.isNull("deletedAt")) null else it.getLong("deletedAt"),
                trashedPageIds = it.optJSONArray("trashedPageIds")?.strings() ?: emptyList()
            )
        }
        val audioItems = if (json.getInt("version") >= 3) json.getJSONArray("audioItems").objects().map(AudioJson::itemFromJson) else emptyList()
        LibraryData(VERSION, folders, notebooks, audioItems).also(::validateLibrary)
    }

    fun pageToJson(page: PageDocument): JSONObject {
        validatePage(page)
        return JSONObject().put("version", VERSION).put("id", page.id)
            .put("template", page.template.name).put("width", page.width.toDouble())
            .put("height", page.height.toDouble()).put("backgroundColor", page.backgroundColor)
            .put("revision", page.revision)
            .put("pdfBackground", page.pdfBackground?.let { background ->
                JSONObject().put("assetId", background.assetId).put("pageIndex", background.pageIndex)
                    .put("widthPoints", background.widthPoints.toDouble()).put("heightPoints", background.heightPoints.toDouble())
            } ?: JSONObject.NULL)
            .put("textRecognition", page.textRecognition)
            .put("recognitionRevision", page.recognitionRevision ?: JSONObject.NULL)
            .put("recognitionLanguage", page.recognitionLanguage ?: JSONObject.NULL)
            .put("elements", JSONArray().apply { page.elements.forEach { put(elementToJson(it)) } })
    }

    fun pageFromJson(json: JSONObject): PageDocument = decode {
        checkVersion(json)
        PageDocument(
            id = json.getString("id"), template = PaperTemplate.valueOf(json.getString("template")),
            width = json.number("width"), height = json.number("height"),
            backgroundColor = json.getInt("backgroundColor"), revision = json.getLong("revision"),
            elements = json.getJSONArray("elements").objects().map(::elementFromJson),
            pdfBackground = json.optJSONObject("pdfBackground")?.let {
                PdfBackground(it.getString("assetId"), it.getInt("pageIndex"), it.number("widthPoints"), it.number("heightPoints"))
            },
            textRecognition = json.optString("textRecognition", ""),
            recognitionRevision = if (json.isNull("recognitionRevision")) null else json.getLong("recognitionRevision"),
            recognitionLanguage = json.nullableString("recognitionLanguage")
        ).also(::validatePage)
    }

    private fun elementToJson(element: PageElement): JSONObject = JSONObject().put("id", element.id).apply {
        when (element) {
            is StrokeElement -> {
                put("type", "stroke").put("color", element.color).put("width", element.width.toDouble())
                    .put("kind", element.kind.name)
                    .put("isPolyline", element.isPolyline).put("isShapeFragment", element.isShapeFragment)
                    .put("points", JSONArray().apply { element.points.forEach { point ->
                        put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()).put(point.pressure.toDouble()))
                    } })
            }
            is ShapeElement -> put("type", "shape").put("shape", element.shape.name)
                .put("startX", element.startX.toDouble()).put("startY", element.startY.toDouble())
                .put("endX", element.endX.toDouble()).put("endY", element.endY.toDouble())
                .put("color", element.color).put("width", element.width.toDouble())
            is ImageElement -> put("type", "image").put("assetId", element.assetId)
                .put("x", element.x.toDouble()).put("y", element.y.toDouble())
                .put("width", element.width.toDouble()).put("height", element.height.toDouble())
                .put("rotationDegrees", element.rotationDegrees.toDouble())
                .put("cropLeft", element.cropLeft.toDouble()).put("cropTop", element.cropTop.toDouble())
                .put("cropRight", element.cropRight.toDouble()).put("cropBottom", element.cropBottom.toDouble())
            is TextElement -> put("type", "text").put("text", element.text)
                .put("x", element.x.toDouble()).put("y", element.y.toDouble())
                .put("size", element.size.toDouble()).put("color", element.color)
        }
    }

    private fun elementFromJson(json: JSONObject): PageElement {
        val id = json.getString("id")
        return when (json.getString("type")) {
            "stroke" -> StrokeElement(id, json.getJSONArray("points").let { points ->
                require(points.length() <= 500_000) { "Çizgi çok büyük." }
                List(points.length()) { index ->
                    val point = points.getJSONArray(index)
                    require(point.length() == 3) { "Geçersiz çizgi noktası." }
                    InkPoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.getDouble(2).toFloat())
                }
            }, json.getInt("color"), json.number("width"), InkKind.valueOf(json.getString("kind")),
                json.optBoolean("isPolyline", false), json.optBoolean("isShapeFragment", false))
            "shape" -> ShapeElement(id, ShapeKind.valueOf(json.getString("shape")),
                json.number("startX"), json.number("startY"), json.number("endX"), json.number("endY"),
                json.getInt("color"), json.number("width"))
            "image" -> ImageElement(id, json.getString("assetId"), json.number("x"), json.number("y"),
                json.number("width"), json.number("height"), json.optDouble("rotationDegrees", 0.0).toFloat(),
                json.optDouble("cropLeft", 0.0).toFloat(), json.optDouble("cropTop", 0.0).toFloat(),
                json.optDouble("cropRight", 1.0).toFloat(), json.optDouble("cropBottom", 1.0).toFloat())
            "text" -> TextElement(id, json.getString("text"), json.number("x"), json.number("y"),
                json.number("size"), json.getInt("color"))
            else -> throw IOException("Bu nesne türü desteklenmiyor.")
        }
    }

    fun validateLibrary(library: LibraryData) {
        require(library.version == VERSION) { "Desteklenmeyen kütüphane sürümü." }
        require(library.folders.map { it.id }.distinct().size == library.folders.size) { "Tekrarlanan klasör kimliği." }
        require(library.notebooks.map { it.id }.distinct().size == library.notebooks.size) { "Tekrarlanan defter kimliği." }
        val folders = library.folders.associateBy { it.id }
        val resolvedParents = mutableSetOf<String>()
        library.folders.forEach { folder ->
            validId(folder.id)
            validateName(folder.name)
            val path = mutableSetOf<String>()
            var currentId: String? = folder.id
            while (currentId != null && currentId !in resolvedParents) {
                require(path.add(currentId)) { "Klasör döngüsü bulundu." }
                val current = requireNotNull(folders[currentId]) { "Üst klasör eksik." }
                currentId = current.parentId
            }
            resolvedParents.addAll(path)
        }
        val pages = mutableSetOf<String>()
        require(library.audioItems.map { it.id }.distinct().size == library.audioItems.size) { "Tekrarlanan ses kimliği." }
        val notebookIds = library.notebooks.map { it.id }.toSet()
        library.audioItems.forEach {
            AudioJson.validateItem(it)
            require(it.folderId == null || it.folderId in folders) { "Ses kaydının klasörü eksik." }
            require(it.notebookId == null || it.notebookId in notebookIds) { "Ses kaydının defteri eksik." }
        }
        library.notebooks.forEach { notebook ->
            validId(notebook.id)
            validateName(notebook.title)
            require(notebook.folderId == null || folders.containsKey(notebook.folderId)) { "Defterin klasörü eksik." }
            require(notebook.pageIds.isNotEmpty()) { "Defterde en az bir sayfa olmalı." }
            require(notebook.updatedAt >= 0 && (notebook.deletedAt == null || notebook.deletedAt >= 0)) { "Geçersiz tarih." }
            (notebook.pageIds + notebook.trashedPageIds).forEach {
                validId(it)
                require(pages.add(it)) { "Sayfa birden fazla kez kullanılıyor." }
            }
        }
    }

    fun validatePage(page: PageDocument) {
        validId(page.id)
        size(page.width, 100_000f)
        size(page.height, 100_000f)
        require(page.revision >= 0) { "Geçersiz sayfa sürümü." }
        require(page.textRecognition.length <= 100_000) { "Tanınan metin çok uzun." }
        require(page.recognitionRevision == null || page.recognitionRevision in 0..page.revision) { "Geçersiz tanıma sürümü." }
        require(page.recognitionLanguage == null || page.recognitionLanguage.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "Geçersiz tanıma dili." }
        page.pdfBackground?.let {
            validId(it.assetId)
            require(it.pageIndex in 0 until 2000) { "Geçersiz PDF sayfası." }
            size(it.widthPoints, 100_000f); size(it.heightPoints, 100_000f)
        }
        require(page.elements.size <= 100_000) { "Sayfa çok büyük." }
        val ids = mutableSetOf<String>()
        page.elements.forEach { element ->
            validId(element.id)
            require(ids.add(element.id)) { "Tekrarlanan nesne kimliği." }
            when (element) {
                is StrokeElement -> {
                    require(element.points.isNotEmpty() && element.points.size <= 500_000) { "Geçersiz çizgi." }
                    size(element.width, 10_000f)
                    element.points.forEach { point ->
                        coordinate(point.x); coordinate(point.y)
                        require(point.pressure.isFinite() && point.pressure in 0f..10f) { "Geçersiz basınç." }
                    }
                }
                is ShapeElement -> {
                    coordinate(element.startX); coordinate(element.startY)
                    coordinate(element.endX); coordinate(element.endY); size(element.width, 10_000f)
                }
                is ImageElement -> {
                    validId(element.assetId); coordinate(element.x); coordinate(element.y)
                    size(element.width, 100_000f); size(element.height, 100_000f)
                    require(element.rotationDegrees.isFinite() && element.rotationDegrees in -360f..360f) { "Geçersiz döndürme açısı." }
                    require(listOf(element.cropLeft, element.cropTop, element.cropRight, element.cropBottom).all { it.isFinite() && it in 0f..1f } &&
                        element.cropLeft < element.cropRight && element.cropTop < element.cropBottom) { "Geçersiz kırpma alanı." }
                }
                is TextElement -> {
                    require(element.text.length <= 100_000) { "Metin çok uzun." }
                    coordinate(element.x); coordinate(element.y); size(element.size, 10_000f)
                }
            }
        }
    }

    fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= 200) { "Ad 1–200 karakter olmalı." }
    }

    private fun coordinate(value: Float) = require(value.isFinite() && value in -1_000_000f..1_000_000f) { "Geçersiz koordinat." }
    private fun size(value: Float, maximum: Float) = require(value.isFinite() && value > 0 && value <= maximum) { "Geçersiz boyut." }
    private fun JSONObject.number(key: String): Float = getDouble(key).toFloat()
    private fun JSONObject.nullableString(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
    private fun checkVersion(json: JSONObject) = require(json.getInt("version") in 1..VERSION) { "Desteklenmeyen belge sürümü." }
    private inline fun <T> decode(block: () -> T): T = try { block() } catch (e: Exception) {
        if (e is IOException) throw e
        throw IOException("Belge okunamadı. Mevcut dosya korunuyor.", e)
    }
}

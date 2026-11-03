package dev.defter.app

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.*
import dev.defter.app.ink.PageRenderer
import dev.defter.app.ink.InkEditing
import dev.defter.app.ink.PageEdgeDirection
import dev.defter.app.localization.tr
import dev.defter.app.localization.quantity
import dev.defter.app.recognition.HandwritingRecognizer
import dev.defter.app.recognition.MlKitHandwritingRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class PageSearchResult(val notebookId: String, val pageId: String, val pageNumber: Int, val title: String, val excerpt: String)

class DefterViewModel @JvmOverloads constructor(application: Application, private val state: SavedStateHandle = SavedStateHandle()) : AndroidViewModel(application) {
    private val repository = DocumentRepository(application)
    var library by mutableStateOf(LibraryData()); private set
    var folderId by mutableStateOf<String?>(null); private set
    var notebookId by mutableStateOf<String?>(null); private set
    var page by mutableStateOf<PageDocument?>(null); private set
    var assets by mutableStateOf<Map<String, Bitmap>>(emptyMap()); private set
    private val editorPreferences = application.getSharedPreferences("editor_preferences", Context.MODE_PRIVATE)
    private var drawingConfig by mutableStateOf(DrawingConfig(touchDrawing = editorPreferences.getBoolean("finger_draws", false)))
    var config: DrawingConfig
        get() = drawingConfig
        set(value) {
            val modeChanged = drawingConfig.touchDrawing != value.touchDrawing
            drawingConfig = value
            if (modeChanged) editorPreferences.edit().putBoolean("finger_draws", value.touchDrawing).apply()
        }
    var loading by mutableStateOf(true); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var pendingSaves by mutableIntStateOf(0); private set
    var unsaved by mutableStateOf(false); private set
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set
    var pendingSharedImage by mutableStateOf<Uri?>(null); private set
    var pendingSharedText by mutableStateOf<String?>(null); private set
    var openTabs by mutableStateOf<List<String>>(emptyList()); private set
    var referenceNotebookId by mutableStateOf<String?>(null); private set
    var referencePage by mutableStateOf<PageDocument?>(null); private set
    var referenceAssets by mutableStateOf<Map<String, Bitmap>>(emptyMap()); private set
    var searchResults by mutableStateOf<List<PageSearchResult>>(emptyList()); private set
    var searching by mutableStateOf(false); private set
    var recognitionCandidates by mutableStateOf<List<String>>(emptyList()); private set
    var recognitionLanguage by mutableStateOf("tr-TR")
    var progress by mutableStateOf(""); private set
    private var recognizerInstance: HandwritingRecognizer? = null
    private val recognizer: HandwritingRecognizer get() = recognizerInstance ?: MlKitHandwritingRecognizer(getApplication()).also { recognizerInstance = it }
    private val committedRevisions = mutableMapOf<String, Long>()
    private val latestSnapshots = mutableMapOf<String, PageDocument>()
    private var recognitionSourcePage: String? = null
    private var recognitionSourceRevision: Long? = null
    private var recognitionStrokeIds = emptySet<String>()
    private var searchJob: Job? = null
    private var searchGeneration = 0L
    private val tabPages = mutableMapOf<String, String>()
    private val undo = ArrayDeque<PageDocument>()
    private val redo = ArrayDeque<PageDocument>()
    private val failedSaves = mutableMapOf<String, PageDocument>()
    private var lastSave: Job? = null
    private var copiedElements: List<PageElement> = emptyList()
    private var copiedFromNotebook: String? = null
    private var clipboardToken: String? = null
    val notebook: Notebook? get() = library.notebooks.firstOrNull { it.id == notebookId }
    val pageIndex: Int get() = notebook?.pageIds?.indexOf(page?.id) ?: -1

    private val startup: Job
    init {
        startup = viewModelScope.launch {
            try {
                library = repository.loadLibrary()
                // Tab activation persists the new session, so capture both reference keys first.
                val restoredReferenceBook = state.get<String>("referenceBook")
                val restoredReferencePage = state.get<String>("referencePage")
                folderId = state.get<String>("folder")?.takeIf { id -> library.folders.any { it.id == id } }
                openTabs = (state.get<ArrayList<String>>("tabs") ?: arrayListOf()).filter { id -> library.notebooks.any { it.id == id && it.deletedAt == null } }
                openTabs.forEach { id -> state.get<String>("tab-$id")?.let { tabPages[id] = it } }
                pendingSharedImage = state.get<String>("shareUri")?.let(Uri::parse)
                pendingSharedText = state.get<String>("shareText")
                state.get<String>("notebook")?.let { id ->
                    library.notebooks.firstOrNull { it.id == id && it.deletedAt == null }?.let { book ->
                        activatePage(repository.loadPage(state.get<String>("page")?.takeIf { it in book.pageIds } ?: book.pageIds.first()))
                        notebookId = id
                        trackTab(id)
                    }
                }
                restoredReferenceBook?.let { id ->
                    library.notebooks.firstOrNull { it.id == id && it.deletedAt == null }?.let { book ->
                        val doc = repository.loadPage(restoredReferencePage?.takeIf { it in book.pageIds } ?: book.pageIds.first())
                        referenceAssets = decodeAssets(doc); referencePage = doc; referenceNotebookId = id
                    }
                }
            }
            catch (e: Exception) { error = message(e) }
            finally { loading = false; persistSession() }
        }
    }

    private fun message(e: Exception) = e.message?.take(350) ?: tr("İşlem tamamlanamadı. Lütfen yeniden dene.")
    fun dismissError() { error = null }
    fun reportEditingError(value: String) { error = value.take(350) }
    fun dismissNotice() { notice = null }
    fun reportFilePickerUnavailable() {
        notice = tr("Bu cihazda Android dosya seçicisi bulunamadı. Defterlerini kullanmaya devam edebilirsin; dosya alışverişi için sistem dosya seçicisi gerekir.")
    }
    fun navigateFolder(id: String?) { folderId = id; persistSession() }

    private fun persistSession() {
        state["folder"] = folderId; state["notebook"] = notebookId; state["page"] = page?.id
        state["tabs"] = ArrayList(openTabs)
        tabPages.forEach { (book, page) -> state["tab-$book"] = page }
        state["referenceBook"] = referenceNotebookId; state["referencePage"] = referencePage?.id
        state["shareUri"] = pendingSharedImage?.toString(); state["shareText"] = pendingSharedText
    }

    private fun trackTab(id: String) {
        if (id !in openTabs) openTabs = openTabs + id
        page?.id?.let { tabPages[id] = it }
        persistSession()
    }

    private fun operation(block: suspend () -> Unit) {
        if (loading) { viewModelScope.launch { startup.join(); operation(block) }; return }
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) { error = message(e) }
            finally { busy = false; progress = ""; persistSession() }
        }
    }

    private suspend fun awaitSaves() {
        lastSave?.join()
        check(failedSaves.isEmpty()) { tr("Son değişiklikler kaydedilemedi. Önce kaydı yeniden dene.") }
    }

    private fun save(snapshot: PageDocument) {
        if ((latestSnapshots[snapshot.id]?.revision ?: -1) > snapshot.revision) return
        latestSnapshots[snapshot.id] = snapshot
        val previous = lastSave
        pendingSaves++
        lastSave = viewModelScope.launch {
            previous?.join()
            try {
                if ((latestSnapshots[snapshot.id]?.revision ?: -1) > snapshot.revision) return@launch
                repository.savePage(snapshot, committedRevisions[snapshot.id] ?: (snapshot.revision - 1).coerceAtLeast(0))
                committedRevisions[snapshot.id] = snapshot.revision
                failedSaves.remove(snapshot.id)
                if (latestSnapshots[snapshot.id] == snapshot) latestSnapshots.remove(snapshot.id)
            } catch (e: Exception) {
                // A page write may have completed before its library timestamp failed.
                runCatching { repository.loadPage(snapshot.id) }.getOrNull()?.takeIf { it == snapshot }?.let { committedRevisions[it.id] = it.revision }
                if ((latestSnapshots[snapshot.id]?.revision ?: -1) <= snapshot.revision) {
                    failedSaves[snapshot.id] = snapshot
                    error = tr("Not henüz kaydedilemedi. %1\$s", message(e))
                }
            } finally {
                pendingSaves--
                unsaved = failedSaves.isNotEmpty()
            }
        }
    }

    fun retrySave() { failedSaves.keys.toList().mapNotNull { latestSnapshots[it] ?: failedSaves[it] }.forEach(::save) }

    fun changePage(changed: PageDocument) {
        val current = page ?: return
        if (busy || changed.id != current.id) return
        undo.addLast(current)
        while (undo.size > 60) undo.removeFirst()
        redo.clear()
        val next = changed.copy(revision = current.revision + 1, textRecognition = "", recognitionRevision = null)
        page = next
        canUndo = undo.isNotEmpty()
        canRedo = false
        save(next)
        refreshReferenceIfSame(next)
    }

    fun undo() {
        val current = page ?: return
        if (busy || undo.isEmpty()) return
        redo.addLast(current)
        val next = undo.removeLast().copy(revision = current.revision + 1)
        page = next
        canUndo = undo.isNotEmpty(); canRedo = true
        save(next)
        refreshAssets(next)
        refreshReferenceIfSame(next)
    }

    fun redo() {
        val current = page ?: return
        if (busy || redo.isEmpty()) return
        undo.addLast(current)
        val next = redo.removeLast().copy(revision = current.revision + 1)
        page = next
        canUndo = true; canRedo = redo.isNotEmpty()
        save(next)
        refreshAssets(next)
        refreshReferenceIfSame(next)
    }

    fun createFolder(name: String) = operation {
        library = repository.createFolder(name.trim(), folderId)
    }

    fun createNotebook(title: String, template: PaperTemplate, color: Int) = operation {
        awaitSaves()
        val book = repository.createNotebook(title.trim(), folderId, template, color)
        library = repository.loadLibrary()
        loadPage(book.pageIds.first())
        notebookId = book.id
        trackTab(book.id)
    }

    fun openNotebook(id: String) = operation {
        if (notebookId == id && page != null) return@operation
        awaitSaves()
        val book = library.notebooks.first { it.id == id }
        val firstPage = repository.loadPage(tabPages[id]?.takeIf { it in book.pageIds } ?: book.pageIds.first())
        activatePage(firstPage)
        notebookId = id
        trackTab(id)
    }

    fun closeNotebook() = operation {
        awaitSaves()
        library = repository.loadLibrary()
        notebookId = null; page = null; assets = emptyMap()
        resetHistory()
    }

    fun renameNotebook(id: String, title: String) = operation {
        awaitSaves()
        library = repository.renameNotebook(id, title.trim())
    }

    fun trashNotebook(id: String, deleted: Boolean) = operation {
        awaitSaves()
        library = repository.setNotebookDeleted(id, deleted)
        if (deleted) { openTabs = openTabs - id; tabPages.remove(id); state.remove<String>("tab-$id"); if (referenceNotebookId == id) closeReference() }
    }

    fun permanentlyDeleteNotebooks(ids: Set<String>) = operation {
        awaitSaves()
        library = repository.permanentlyDeleteNotebooks(ids)
        openTabs = openTabs.filterNot { it in ids }
        ids.forEach { id -> tabPages.remove(id); state.remove<String>("tab-$id") }
        if (referenceNotebookId in ids) closeReference()
        if (notebookId in ids) { notebookId = null; page = null; assets = emptyMap(); resetHistory() }
        if (copiedFromNotebook in ids) {
            // A purge may clear our selection, never a newer clipboard from another app.
            runCatching {
                val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                if (clipboardToken != null && clipboard.primaryClip?.description?.extras?.getString("defter_selection") == clipboardToken)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Defter", ""))
            }
            copiedElements = emptyList(); clipboardToken = null; copiedFromNotebook = null
        }
        searchResults = searchResults.filterNot { it.notebookId in ids }
        notice = quantity("%1\$d defter kalıcı olarak silindi.", ids.size)
    }

    private suspend fun loadPage(id: String) { activatePage(repository.loadPage(id)) }
    private suspend fun activatePage(next: PageDocument) {
        val decoded = withContext(Dispatchers.IO) { decodeAssets(next) }
        page = next; assets = decoded
        committedRevisions[next.id] = next.revision
        latestSnapshots.remove(next.id)
        resetHistory()
    }
    private fun resetHistory() { undo.clear(); redo.clear(); canUndo = false; canRedo = false }

    fun selectPage(id: String) = operation {
        awaitSaves(); require(id in (notebook?.pageIds ?: emptyList())) { tr("Sayfa bu defterde değil.") }
        loadPage(id); notebookId?.let(::trackTab)
    }

    /** One release at a page edge is one operation; stale callbacks cannot append extra pages. */
    fun navigatePageBySwipe(sourcePageId: String, direction: PageEdgeDirection) = operation {
        if (page?.id != sourcePageId) return@operation
        awaitSaves()
        val book = notebook ?: return@operation
        val index = book.pageIds.indexOf(sourcePageId)
        if (index < 0) return@operation
        val nextId = book.pageIds.getOrNull(index + if (direction == PageEdgeDirection.NEXT) 1 else -1)
        if (nextId != null) loadPage(nextId)
        else if (direction == PageEdgeDirection.NEXT && index == book.pageIds.lastIndex) {
            val next = repository.addPage(book.id, page?.template ?: PaperTemplate.DOTS, sourcePageId)
            library = repository.loadLibrary()
            activatePage(next)
        } else return@operation
        trackTab(book.id)
    }
    fun addPage(template: PaperTemplate) = operation {
        awaitSaves()
        val id = notebookId ?: return@operation
        val next = repository.addPage(id, template, page?.id)
        library = repository.loadLibrary()
        activatePage(next)
        trackTab(id)
    }
    fun duplicatePage() = operation {
        awaitSaves()
        val id = notebookId ?: return@operation
        val current = page ?: return@operation
        val next = repository.duplicatePage(id, current.id)
        library = repository.loadLibrary()
        activatePage(next)
        trackTab(id)
    }
    fun setTemplate(template: PaperTemplate) { page?.let { changePage(it.copy(template = template)) } }
    fun setPaperColor(color: Int) { page?.let { changePage(it.copy(backgroundColor = color)) } }
    fun renameFolder(id: String, name: String) = operation {
        library = repository.renameFolder(id, name.trim())
    }
    fun moveNotebook(id: String, destination: String?) = operation {
        awaitSaves()
        library = repository.moveNotebook(id, destination)
    }

    fun addText(text: String, atX: Float? = null, atY: Float? = null) {
        if (text.isBlank()) return
        page?.let { current ->
            changePage(current.copy(elements = current.elements + TextElement(
                text = text.take(10_000), x = (atX ?: 100f).coerceIn(0f, current.width - 24f),
                y = (atY ?: 180f).coerceIn(0f, current.height - 40f), color = config.color
            )))
            config = config.copy(tool = DrawingTool.SELECT)
        }
    }

    fun importImage(uri: Uri, atX: Float? = null, atY: Float? = null) = operation {
        val current = page ?: error(tr("Resim eklemek için bir defter aç."))
        val imported = repository.importImage(uri)
        val imageWidth = minOf(current.width * .65f, imported.width.toFloat())
        val ratio = imported.height.toFloat() / imported.width
        val imageHeight = minOf(imageWidth * ratio, current.height * .75f)
        val finalWidth = imageHeight / ratio
        val next = current.copy(elements = current.elements + ImageElement(
            assetId = imported.assetId, x = (atX ?: ((current.width - finalWidth) / 2)).coerceIn(0f, current.width - finalWidth),
            y = (atY ?: 160f).coerceIn(0f, current.height - imageHeight), width = finalWidth, height = imageHeight
        ))
        val decoded = withContext(Dispatchers.IO) { decodeAssets(next) }
        // The operation lock remains held during import, so commit explicitly here.
        undo.addLast(current); while (undo.size > 60) undo.removeFirst()
        redo.clear(); canUndo = true; canRedo = false
        val committed = next.copy(revision = current.revision + 1, textRecognition = "", recognitionRevision = null)
        page = committed
        assets = decoded
        save(committed)
        config = config.copy(tool = DrawingTool.SELECT)
        pendingSharedImage = null
        persistSession()
        refreshReferenceIfSame(committed)
    }

    private suspend fun decodeAssets(document: PageDocument, maxDimension: Int = 2048): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        val decoded = mutableMapOf<String, Bitmap>()
        val imageIds = document.elements.filterIsInstance<ImageElement>().map { it.assetId }.distinct()
        val assetCount = imageIds.size + if (document.pdfBackground != null) 1 else 0
        val totalPixels = maxDimension.toLong() * maxDimension
        val pdfDimension = if (document.pdfBackground != null) kotlin.math.sqrt(totalPixels.toDouble() / assetCount.coerceAtLeast(1)).toInt().coerceAtLeast(64) else 0
        val imageBudget = (totalPixels - pdfDimension.toLong() * pdfDimension).coerceAtLeast(imageIds.size.toLong())
        val imageDimension = kotlin.math.sqrt(imageBudget.toDouble() / imageIds.size.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        try {
        imageIds.forEach { id ->
            val file = repository.assetFile(id)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { tr("Defterdeki bir görsel okunamadı.") }
            var sample = 1
            while (kotlin.math.ceil(bounds.outWidth.toDouble() / sample) > imageDimension || kotlin.math.ceil(bounds.outHeight.toDouble() / sample) > imageDimension) {
                require(sample <= Int.MAX_VALUE / 2) { tr("Görsel boyutları desteklenen sınırı aşıyor.") }
                sample *= 2
            }
            decoded[id] = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: error(tr("Defterdeki bir görsel okunamadı."))
        }
        document.pdfBackground?.let { background -> decoded["pdf:${background.assetId}:${background.pageIndex}"] = repository.renderPdfBackground(background, pdfDimension) }
        decoded
        } catch (e: Exception) { decoded.values.forEach(Bitmap::recycle); throw e }
    }

    private fun refreshAssets(document: PageDocument) {
        viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) { decodeAssets(document) }
                if (page?.id == document.id && page?.revision == document.revision) assets = decoded
                else decoded.values.forEach(Bitmap::recycle)
            } catch (e: Exception) { error = message(e) }
        }
    }

    fun copySelection(ids: Set<String>): Boolean {
        if (busy || loading) return false
        val selected = page?.elements?.filter { it.id in ids }.orEmpty()
        if (selected.isEmpty()) return false
        return try {
            val token = newId()
            val description = ClipDescription("Defter", arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN, "application/vnd.defter.selection"))
            description.extras = PersistableBundle().apply { putString("defter_selection", token) }
            val text = selected.filterIsInstance<TextElement>().joinToString("\n") { it.text }
            val clip = ClipData(description, ClipData.Item(text))
            (getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            copiedElements = selected.map { if (it is StrokeElement) it.copy(points = it.points.toList()) else it }
            copiedFromNotebook = notebookId
            clipboardToken = token
            true
        } catch (e: Exception) { error = message(e); false }
    }

    fun paste(atX: Float? = null, atY: Float? = null) {
        if (busy || loading || page == null) return
        try {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) { notice = tr("Panoda içerik yok."); return }
            val token = clip.description.extras?.getString("defter_selection")
            if (token != null && token == clipboardToken && copiedElements.isNotEmpty()) {
                val current = page ?: return
                val bounds = InkEditing.groupBounds(copiedElements) ?: return
                val defaultOffset = InkEditing.copyOffset(copiedElements, current.width, current.height)
                fun position(requested: Float?, start: Float, end: Float, size: Float, fallback: Float): Float {
                    if (requested == null || !requested.isFinite()) return fallback
                    return if (end - start <= size) (requested - start).coerceIn(-start, size - end)
                        else requested.coerceIn(0f, size) - start
                }
                val dx = position(atX, bounds.left, bounds.right, current.width, defaultOffset.first)
                val dy = position(atY, bounds.top, bounds.bottom, current.height, defaultOffset.second)
                val copies = copiedElements.map { InkEditing.copied(it, dx, dy) }
                changePage(current.copy(elements = current.elements + copies))
                page?.let(::refreshAssets)
                return
            }
            val item = clip.getItemAt(0)
            if (item.uri != null) importImage(item.uri, atX, atY)
            else if (!item.text.isNullOrBlank()) addText(item.text.toString(), atX, atY)
            else if (clip.description.hasMimeType("application/vnd.defter.selection")) notice = tr("Kopyalanan çizim artık bu oturumda yok. İçeriği yeniden kopyala.")
            else notice = tr("Bu içerik yapıştırılamıyor. Görseli Paylaş menüsünden veya dosyadan ekleyebilirsin.")
        } catch (e: Exception) { error = message(e) }
    }

    @Suppress("DEPRECATION")
    fun acceptShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        pendingSharedImage = null; pendingSharedText = null
        try {
            if (intent.type?.startsWith("image/") == true) {
                val value = intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
                val uri = value as? Uri ?: error(tr("Paylaşılan görselin dosya adresi geçersiz."))
                require(uri.scheme == "content" || uri.scheme == "file") { tr("Desteklenmeyen dosya adresi.") }
                pendingSharedImage = uri
            } else if (intent.type?.startsWith("text/") == true) {
                pendingSharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(10_000)
                    ?.takeIf(String::isNotBlank) ?: error(tr("Paylaşılan metin boş."))
            }
        } catch (e: Exception) { error = message(e) }
        persistSession()
    }
    fun insertSharedContent() {
        pendingSharedImage?.let { importImage(it); return }
        if (busy || loading || page == null) return
        pendingSharedText?.let { addText(it); pendingSharedText = null; persistSession() }
    }

    fun closeTab(id: String) = operation {
        awaitSaves()
        val remaining = openTabs - id
        if (notebookId == id) {
            val other = remaining.lastOrNull()?.let { next -> library.notebooks.firstOrNull { it.id == next && it.deletedAt == null } }
            if (other != null) {
                activatePage(repository.loadPage(tabPages[other.id]?.takeIf { it in other.pageIds } ?: other.pageIds.first()))
                notebookId = other.id
            } else { notebookId = null; page = null; assets = emptyMap(); resetHistory() }
        }
        openTabs = remaining; tabPages.remove(id); state.remove<String>("tab-$id")
    }

    fun openPage(bookId: String, pageId: String) = operation {
        awaitSaves()
        val book = library.notebooks.first { it.id == bookId && it.deletedAt == null }
        require(pageId in book.pageIds) { tr("Sayfa bulunamadı.") }
        activatePage(repository.loadPage(pageId)); notebookId = bookId; trackTab(bookId)
    }

    fun openReference(bookId: String) = operation {
        awaitSaves()
        val book = library.notebooks.first { it.id == bookId && it.deletedAt == null }
        val doc = repository.loadPage(tabPages[bookId]?.takeIf { it in book.pageIds } ?: book.pageIds.first())
        val decoded = decodeAssets(doc)
        referenceNotebookId = bookId; referencePage = doc; referenceAssets = decoded
    }

    fun closeReference() { referenceNotebookId = null; referencePage = null; referenceAssets = emptyMap(); persistSession() }

    fun stepReference(delta: Int) = operation {
        awaitSaves()
        val book = library.notebooks.firstOrNull { it.id == referenceNotebookId } ?: return@operation
        val index = book.pageIds.indexOf(referencePage?.id)
        val id = book.pageIds.getOrNull(index + delta) ?: return@operation
        val doc = repository.loadPage(id); val decoded = decodeAssets(doc)
        referencePage = doc; referenceAssets = decoded
    }

    fun swapPanes() = operation {
        awaitSaves()
        val oldBook = notebookId ?: return@operation
        val oldPage = page ?: return@operation
        val refBook = referenceNotebookId ?: return@operation
        val ref = referencePage ?: return@operation
        val currentRef = repository.loadPage(ref.id)
        val oldAssets = assets
        activatePage(currentRef); notebookId = refBook; trackTab(refBook)
        referenceNotebookId = oldBook; referencePage = oldPage; referenceAssets = oldAssets
    }

    private fun refreshReferenceIfSame(next: PageDocument) {
        if (referencePage?.id == next.id) {
            referencePage = next
            viewModelScope.launch {
                val decoded = runCatching { decodeAssets(next) }.getOrNull() ?: return@launch
                if (referencePage?.id == next.id && referencePage?.revision == next.revision) referenceAssets = decoded
                else decoded.values.forEach(Bitmap::recycle)
            }
        }
    }

    fun importPdf(uri: Uri) = operation {
        awaitSaves(); progress = tr("PDF sayfaları hazırlanıyor…")
        val name = runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0).substringBeforeLast('.').take(120) else null
            }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: tr("PDF defteri")
        val book = repository.importPdf(uri, name, folderId)
        library = repository.loadLibrary(); activatePage(repository.loadPage(book.pageIds.first())); notebookId = book.id; trackTab(book.id)
    }

    fun reorderPage(id: String, offset: Int) = operation {
        awaitSaves()
        val book = notebook ?: return@operation
        val ids = book.pageIds.toMutableList(); val index = ids.indexOf(id); val target = index + offset
        if (index < 0 || target !in ids.indices) return@operation
        ids.removeAt(index); ids.add(target, id)
        library = repository.reorderPages(book.id, ids)
    }

    fun reorderPages(ids: List<String>) = operation {
        awaitSaves(); library = repository.reorderPages(notebookId ?: return@operation, ids)
    }

    fun deletePage(id: String) = operation {
        awaitSaves()
        val book = notebook ?: return@operation
        val oldIndex = book.pageIds.indexOf(id)
        library = repository.deletePage(book.id, id)
        if (page?.id == id) { val ids = notebook!!.pageIds; activatePage(repository.loadPage(ids[oldIndex.coerceIn(0, ids.lastIndex)])); trackTab(book.id) }
        if (referencePage?.id == id) closeReference()
    }

    fun restorePage(id: String) = operation {
        awaitSaves(); library = repository.restorePage(notebookId ?: return@operation, id, page?.id)
    }

    fun transferPage(id: String, destination: String, copy: Boolean) = operation {
        awaitSaves()
        val source = notebook ?: return@operation
        repository.transferPage(source.id, id, destination, copy)
        library = repository.loadLibrary()
        if (!copy && page?.id == id) { activatePage(repository.loadPage(notebook!!.pageIds.first())); trackTab(source.id) }
        if (!copy && referencePage?.id == id) closeReference()
    }

    suspend fun pageThumbnail(id: String): Bitmap {
        val visibleSnapshot = page?.takeIf { it.id == id }
        return withContext(Dispatchers.IO) {
        val doc = visibleSnapshot ?: repository.loadPage(id)
        val decoded = decodeAssets(doc, 384)
        try {
            val width = if (doc.width > doc.height) 210 else maxOf(1, (210 * doc.width / doc.height).toInt())
            val height = if (doc.height >= doc.width) 210 else maxOf(1, (210 * doc.height / doc.width).toInt())
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                Canvas(bitmap).apply { scale(width / doc.width, height / doc.height); PageRenderer.draw(this, doc, decoded) }
            }
        } finally { decoded.values.forEach(Bitmap::recycle) }
        }
    }

    fun recoverUnsavedAsCopies() = operation {
        lastSave?.join()
        for (failed in failedSaves.values.toList()) {
            val snapshot = latestSnapshots[failed.id] ?: failed
            val owner = repository.loadLibrary().notebooks.firstOrNull { snapshot.id in it.pageIds || snapshot.id in it.trashedPageIds }
            val recovered = repository.recoverPageCopy(snapshot, owner?.id)
            failedSaves.remove(failed.id); latestSnapshots.remove(failed.id)
            if (page?.id == failed.id) { activatePage(recovered.page); notebookId = recovered.notebookId; trackTab(recovered.notebookId) }
        }
        library = repository.loadLibrary(); unsaved = failedSaves.isNotEmpty(); error = null
        notice = tr("Kaydedilemeyen değişiklikler ayrı sayfalarda kurtarıldı. Diğer kayıtlar korundu.")
    }

    fun searchPages(query: String) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchResults = emptyList()
        if (query.isBlank()) { searchResults = emptyList(); searching = false; return }
        searching = true
        val books = library.notebooks.filter { it.deletedAt == null }
        searchJob = viewModelScope.launch {
            try {
                val results = mutableListOf<PageSearchResult>()
                for (book in books) for ((index, id) in book.pageIds.withIndex()) {
                    val doc = repository.loadPage(id)
                    val text = doc.elements.filterIsInstance<TextElement>().joinToString("\n") { it.text } + "\n" +
                        (if (doc.recognitionRevision == doc.revision) doc.textRecognition else "")
                    // Unicode case matching also keeps English I searchable alongside Turkish text.
                    val at = text.indexOf(query, ignoreCase = true).takeIf { it >= 0 }
                        ?: text.lowercase(Locale.forLanguageTag("tr")).indexOf(query.lowercase(Locale.forLanguageTag("tr")))
                    if (at >= 0) results += PageSearchResult(book.id, id, index + 1, book.title, text.substring(maxOf(0, at - 35), minOf(text.length, at + query.length + 90)).trim())
                }
                if (generation == searchGeneration) searchResults = results
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = message(e) }
            finally { if (generation == searchGeneration) searching = false }
        }
    }

    fun downloadHandwritingModel() = operation {
        progress = tr("El yazısı modeli indiriliyor…")
        recognizer.downloadModel(recognitionLanguage)
        notice = tr("El yazısı modeli hazır. Tanıma artık cihazında çalışabilir.")
    }

    fun recognizeHandwriting(selectedIds: Set<String> = emptySet()) = operation {
        awaitSaves()
        require(recognizer.isModelDownloaded(recognitionLanguage)) { tr("Önce el yazısı dil modelini indir.") }
        val doc = page ?: return@operation
        val strokes = doc.elements.filterIsInstance<StrokeElement>().filter { it.kind == InkKind.PEN && !it.isShapeFragment && (selectedIds.isEmpty() || it.id in selectedIds) }
        require(strokes.isNotEmpty()) { tr("Tanınabilecek kalem yazısı yok.") }
        progress = tr("El yazısı okunuyor…")
        val candidates = recognizer.recognize(strokes, recognitionLanguage).filter(String::isNotBlank)
        require(candidates.isNotEmpty()) { tr("Yazı tanınamadı. Daha küçük bir bölge seçmeyi dene.") }
        recognitionCandidates = candidates; recognitionSourcePage = doc.id; recognitionSourceRevision = doc.revision; recognitionStrokeIds = strokes.map { it.id }.toSet()
        if (selectedIds.isEmpty()) {
            val indexed = doc.copy(revision = doc.revision + 1, recognitionRevision = doc.revision + 1, textRecognition = candidates.first(), recognitionLanguage = recognitionLanguage)
            repository.savePage(indexed, doc.revision); committedRevisions[doc.id] = indexed.revision; page = indexed
            recognitionSourceRevision = indexed.revision; refreshReferenceIfSame(indexed)
        }
    }

    fun dismissRecognition() { recognitionCandidates = emptyList(); recognitionStrokeIds = emptySet() }

    fun useRecognizedText(text: String, replaceInk: Boolean) {
        val doc = page ?: return
        if (busy || doc.id != recognitionSourcePage || doc.revision != recognitionSourceRevision || text.isBlank()) return
        val strokes = doc.elements.filterIsInstance<StrokeElement>().filter { it.id in recognitionStrokeIds }
        val points = strokes.flatMap { it.points }
        val x = points.minOfOrNull { it.x } ?: 100f
        val y = if (replaceInk) points.minOfOrNull { it.y } ?: 180f else ((points.maxOfOrNull { it.y } ?: 140f) + 40f).coerceAtMost(doc.height - 50f)
        changePage(doc.copy(elements = doc.elements.filterNot { replaceInk && it.id in recognitionStrokeIds } + TextElement(text = text.take(10_000), x = x, y = y, color = config.color)))
        config = config.copy(tool = DrawingTool.SELECT); dismissRecognition()
    }

    fun indexHandwriting() = operation {
        awaitSaves()
        require(recognizer.isModelDownloaded(recognitionLanguage)) { tr("Önce el yazısı dil modelini indir.") }
        val ids = library.notebooks.filter { it.deletedAt == null }.flatMap { it.pageIds }
        for ((index, id) in ids.withIndex()) {
            progress = tr("El yazısı aranabilir hâle getiriliyor · %1\$s/%2\$s", index + 1, ids.size)
            val doc = repository.loadPage(id)
            if (doc.recognitionRevision == doc.revision && doc.recognitionLanguage == recognitionLanguage) continue
            val strokes = doc.elements.filterIsInstance<StrokeElement>().filter { it.kind == InkKind.PEN && !it.isShapeFragment }
            if (strokes.isEmpty()) continue
            val text = recognizer.recognize(strokes, recognitionLanguage).firstOrNull().orEmpty()
            val indexed = doc.copy(revision = doc.revision + 1, recognitionRevision = doc.revision + 1, textRecognition = text, recognitionLanguage = recognitionLanguage)
            repository.savePage(indexed, doc.revision); committedRevisions[id] = indexed.revision
            if (page?.id == id) page = indexed
            refreshReferenceIfSame(indexed)
        }
        library = repository.loadLibrary(); notice = tr("El yazısı dizini güncellendi. Kütüphaneden içerik arayabilirsin.")
    }

    override fun onCleared() { recognizerInstance?.close(); super.onCleared() }

    fun exportBackup(uri: Uri) = operation {
        awaitSaves()
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                repository.exportBackup(it)
            } ?: error(tr("Yedek dosyası açılamadı."))
        }
        notice = tr("Tam yedek kaydedildi. Dosyayı cihazının dışında da saklayabilirsin.")
    }

    fun importBackup(uri: Uri) = operation {
        awaitSaves()
        library = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                repository.importBackup(it)
            } ?: error(tr("Yedek dosyası açılamadı."))
        }
        notice = tr("Yedek içe aktarıldı. Mevcut defterlerin korundu.")
    }

    fun exportPdf(uri: Uri) = operation {
        awaitSaves()
        val book = notebook ?: error(tr("Önce bir defter aç."))
        withContext(Dispatchers.IO) {
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                book.pageIds.forEachIndexed { index, id ->
                    val doc = repository.loadPage(id)
                    val pageAssets = decodeAssets(doc)
                    val pdfWidth = doc.pdfBackground?.widthPoints?.toInt()?.coerceIn(1, 14400) ?: 595
                    val pdfHeight = doc.pdfBackground?.heightPoints?.toInt()?.coerceIn(1, 14400) ?: (pdfWidth * doc.height / doc.width).toInt().coerceIn(1, 14400)
                    val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(pdfWidth, pdfHeight, index + 1).create()
                    val outputPage = pdf.startPage(info)
                    val scale = minOf(pdfWidth / doc.width, pdfHeight / doc.height)
                    outputPage.canvas.save()
                    outputPage.canvas.scale(scale, scale)
                    try {
                        dev.defter.app.ink.PageRenderer.draw(outputPage.canvas, doc, pageAssets)
                    } finally {
                        outputPage.canvas.restore()
                        pdf.finishPage(outputPage)
                        pageAssets.values.forEach { it.recycle() }
                    }
                }
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use(pdf::writeTo)
                    ?: error(tr("PDF dosyası açılamadı."))
            } finally { pdf.close() }
        }
        notice = tr("PDF kaydedildi.")
    }
}

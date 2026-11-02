package dev.defter.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProViewModelMemoryTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()
    private lateinit var app: Application
    private lateinit var repository: DocumentRepository
    private lateinit var vm: DefterViewModel

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        app = RuntimeEnvironment.getApplication()
        repository = DocumentRepository(app)
        check(repository.root.canonicalFile == File(app.filesDir, "defter").canonicalFile)
        check(!repository.root.exists() || repository.root.deleteRecursively())
    }

    @After fun teardown() { store.clear(); scheduler.runCurrent(); Dispatchers.resetMain() }

    @Test fun manyImagesRespectAggregatePixelBudgetInBothPanesWithoutShrinkingStoredAssets() {
        val source = File(app.cacheDir, "many-images.png")
        Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(0xFF357ACC.toInt())
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        val assetIds = mutableListOf<String>()
        val book = runBlocking {
            val book = repository.createNotebook("Çok görselli ders", null, PaperTemplate.BLANK, 0)
            repeat(16) { assetIds += repository.importImage(Uri.fromFile(source)).assetId }
            val page = repository.loadPage(book.pageIds.single())
            repository.savePage(page.copy(revision = 1, elements = assetIds.mapIndexed { index, asset ->
                ImageElement(assetId = asset, x = (index % 4) * 240f, y = (index / 4) * 240f, width = 220f, height = 220f)
            }), 0)
            book
        }
        vm = DefterViewModel(app); store.put("memory", vm)
        awaitIdle()
        vm.openNotebook(book.id); awaitIdle()
        assertEquals(16, vm.assets.size)
        assertTrue("All page images together must respect the screen pixel budget", pixels(vm.assets.values) <= 4_194_304L)
        vm.openReference(book.id); awaitIdle()
        assertEquals(16, vm.referenceAssets.size)
        assertTrue("Reference pane must have its own bounded decode budget", pixels(vm.referenceAssets.values) <= 4_194_304L)
        for (asset in assetIds) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(repository.assetFile(asset).path, bounds)
            assertEquals(1024, bounds.outWidth); assertEquals(1024, bounds.outHeight)
        }
        assertFalse(vm.unsaved)
    }

    private fun pixels(images: Collection<Bitmap>) = images.sumOf { it.width.toLong() * it.height }
    private fun awaitIdle() {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.runCurrent()
            if (!vm.loading && !vm.busy && vm.pendingSaves == 0) { assertNull(vm.error); return }
            Thread.sleep(5)
        }
        fail("Memory regression operation timed out: ${vm.error}")
    }
}

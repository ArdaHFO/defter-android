package dev.defter.app

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.model.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Two persisted older-format pages with real ink; exercises the production draw path. */
@RunWith(AndroidJUnit4::class)
class LegacyNotebookDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun oldTwoPageInkRendersAndScrollsWithoutBlockingMainOrChangingFiles() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        lateinit var model: DefterViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[DefterViewModel::class.java] }; await(model)
        val oldBook = model.notebookId; val oldPage = model.page?.id; val oldConfig = model.config
        val files = mutableListOf<File>(); val before = mutableListOf<String>()
        try {
            compose.runOnIdle { model.createNotebook("Older two-page ink verification", PaperTemplate.LINED, 0xff005ac1.toInt()) }; await(model)
            val book = requireNotNull(model.notebookId)
            val ids = mutableListOf<String>()
            repeat(2) { index ->
                if (index > 0) { compose.runOnIdle { model.addPage(PaperTemplate.DOTS) }; await(model) }
                val points = List(50_000) { i -> InkPoint(64f + i % 1000, 100f + (i / 1000) * 15f, .6f) }
                compose.runOnIdle { model.changePage(model.page!!.copy(elements = listOf(StrokeElement(points = points, width = 5f)))) }
                await(model); ids += model.page!!.id
            }
            compose.runOnIdle { model.closeNotebook() }; await(model)
            for ((index, id) in ids.withIndex()) {
                val file = File(app.filesDir, "defter/pages/$id.json")
                val json = JSONObject(file.readText()).put("version", index + 1)
                file.writeText(json.toString()); files += file; before += sha(file)
            }
            compose.runOnIdle { model.openPage(book, ids[0]) }; await(model)
            lateinit var view: NotebookCanvas
            compose.runOnIdle { view = requireNotNull(findCanvas(compose.activity.window.decorView)) }
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val times = mutableListOf<Long>()
            val heartbeat = CountDownLatch(1)
            compose.runOnIdle {
                val start = SystemClock.elapsedRealtimeNanos()
                view.draw(Canvas(bitmap)); times += SystemClock.elapsedRealtimeNanos() - start
                Handler(Looper.getMainLooper()).post { heartbeat.countDown() }
            }
            assertTrue("Main must accept another message while old ink is rendered", heartbeat.await(2, TimeUnit.SECONDS))
            compose.waitUntil(30_000) { var done = false; compose.runOnIdle { done = view.pendingPageRenders == 0 }; done }
            compose.runOnIdle { model.openPage(book, ids[1]) }; await(model)
            fun send(action: Int, y: Float) = compose.runOnIdle {
                val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                val coords = arrayOf(MotionEvent.PointerCoords().apply { x = view.width / 2f; this.y = y; pressure = 1f })
                val event = MotionEvent.obtain(100, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                val start = SystemClock.elapsedRealtimeNanos()
                try { assertTrue(view.dispatchTouchEvent(event)); view.draw(Canvas(bitmap)) } finally { event.recycle() }
                times += SystemClock.elapsedRealtimeNanos() - start
            }
            compose.runOnIdle { model.config = model.config.copy(touchDrawing = false) }
            send(MotionEvent.ACTION_DOWN, view.height * .2f)
            repeat(30) { send(MotionEvent.ACTION_MOVE, view.height * (.2f + it * .02f)) }
            send(MotionEvent.ACTION_CANCEL, view.height * .8f)
            compose.waitUntil(30_000) { var done = false; compose.runOnIdle { done = view.pendingPageRenders == 0 }; done }
            compose.runOnIdle {
                view.draw(Canvas(bitmap))
                assertTrue("Both old pages must be rasterized off Main", view.completedPageRenders >= 2)
                assertEquals(2, model.notebook!!.pageIds.size)
                assertTrue("A scroll must move the first page", view.pageBoundsInView(ids[0])!!.top < 0f)
                assertTrue("Previous page must become visible while scrolling", view.pageBoundsInView(ids[0])!!.bottom > 0f)
            }
            await(model)
            assertTrue("Each manual input/draw must stay below the conservative 2s ANR regression budget", times.all { it < 2_000_000_000L })
            files.forEachIndexed { index, file -> assertEquals("Read/scroll cannot rewrite legacy ink", before[index], sha(file)) }
            val dir = File(requireNotNull(app.getExternalFilesDir(null)), "verification").apply { mkdirs() }
            val result = JSONObject().put("tests", 1).put("legacyVersions", JSONArray(listOf(1, 2))).put("pages", 2)
                .put("inkPointsPerPage", 50_000).put("samePageFileHashes", true).put("mainHeartbeatReceived", true)
                .put("manualDrawAndInputNs", JSONArray(times)).put("physicalTabletTimingClaim", false)
            File(dir, "legacy-notebook-063.json").writeText(result.toString(2))
            File(dir, "legacy-notebook-063.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally {
            compose.runOnIdle {
                model.config = oldConfig
                if (oldBook != null && oldPage != null) model.openPage(oldBook, oldPage) else model.closeNotebook()
            }; await(model)
        }
    }
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun await(model: DefterViewModel) {
        compose.waitUntil(30_000) { !model.loading && !model.busy && !model.pageWindowLoading && model.pendingSaves == 0 }
        compose.runOnIdle { assertNull(model.error) }
    }
    private fun findCanvas(view: View): NotebookCanvas? {
        if (view is NotebookCanvas && !view.isReadOnly) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findCanvas(view.getChildAt(i))?.let { return it }
        return null
    }
}

package dev.defter.app

import android.content.pm.ApplicationInfo
import android.os.*
import android.view.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.defter.app.ink.NotebookCanvas
import dev.defter.app.localization.tr
import dev.defter.app.model.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the shipped variant; emulator timings are diagnostics, not tablet FPS. */
@RunWith(AndroidJUnit4::class)
class GeneralUiPerformanceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun colorButtonsScrollAndIdleSurfaceStayResponsiveInRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val debuggable = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (InstrumentationRegistry.getArguments().getString("requireOptimized") == "true") assertFalse(debuggable)
        lateinit var model: DefterViewModel
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[DefterViewModel::class.java] }
        await(model)
        var verifiedUpgradeFiles = 0
        InstrumentationRegistry.getArguments().getString("baselineBase64")?.let { encoded ->
            val expected = JSONObject(String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8))
            for (path in expected.keys()) {
                require(path.startsWith("files/defter/") && !path.contains(".."))
                val file = File(app.dataDir, path)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals("Upgrade must preserve existing content: $path", expected.getString(path), hash)
                verifiedUpgradeFiles++
            }
        }
        val oldBook = model.notebookId; val oldPage = model.page?.id; val oldConfig = model.config
        val frameTimes = ConcurrentLinkedQueue<Long>()
        val violations = ConcurrentLinkedQueue<String>()
        val thread = HandlerThread("defter-frame-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frameTimes.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)) }
        var policy: StrictMode.ThreadPolicy? = null
        try {
            compose.runOnIdle { model.createNotebook("UI responsiveness verification ${newId().take(8)}", PaperTemplate.DOTS, 0xff005ac1.toInt()) }
            await(model)
            compose.runOnIdle { model.addPage(PaperTemplate.DOTS) }; await(model)
            compose.activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            compose.waitForIdle(); SystemClock.sleep(500)
            lateinit var view: NotebookCanvas
            var before = 0L
            compose.runOnIdle {
                view = requireNotNull(findCanvas(compose.activity.window.decorView))
                before = view.renderedFrames
                repeat(100) {
                    view.setPageWindow(model.notebook!!.pageIds, model.loadedPages, model.loadedPageAssets)
                    view.document = model.page; view.assets = model.assets; view.config = model.config
                    view.isEnabled = true; view.isReadOnly = false
                }
            }
            SystemClock.sleep(250); compose.waitForIdle()
            var redraws = 0L
            compose.runOnIdle {
                redraws = view.renderedFrames - before
                assertTrue("Unchanged bindings must not cause a sustained redraw loop", redraws <= 1)
                policy = StrictMode.getThreadPolicy()
                if (Build.VERSION.SDK_INT >= 28) StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().penaltyListener(Executor { it.run() }) { violations.add(it.javaClass.simpleName) }.build())
            }
            compose.onNodeWithContentDescription(tr("Renk ve kalınlık")).performTouchInput { click() }
            val buttonTimes = mutableListOf<Long>()
            repeat(6) { index ->
                val blue = index % 2 == 0
                val label = tr("%1\$s renk", tr(if (blue) "Mavi" else "Yeşil"))
                val start = SystemClock.elapsedRealtimeNanos()
                compose.onNodeWithContentDescription(label).performTouchInput { click() }.assertIsSelected()
                compose.runOnIdle { assertEquals(if (blue) 0xff397ac6.toInt() else 0xff356859.toInt(), model.config.color) }
                buttonTimes += SystemClock.elapsedRealtimeNanos() - start
            }
            compose.onNodeWithText(tr("Kalınlık ayarı")).performClick()
            compose.onNodeWithText(tr("Uygula")).performClick()
            compose.runOnIdle { model.config = model.config.copy(touchDrawing = false); view.resetViewport() }
            val x = view.width / 2f; val startY = view.height * .3f
            val down = SystemClock.uptimeMillis()
            fun send(action: Int, y: Float) = compose.runOnIdle {
                val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
                val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f })
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(view.dispatchTouchEvent(e)) } finally { e.recycle() }
            }
            send(MotionEvent.ACTION_DOWN, startY)
            repeat(20) { send(MotionEvent.ACTION_MOVE, startY + (it + 1) * view.height * .02f) }
            send(MotionEvent.ACTION_CANCEL, startY + view.height * .4f)
            await(model)
            compose.runOnIdle { policy?.let(StrictMode::setThreadPolicy); policy = null }
            assertTrue("Button/scroll handlers must not perform disk IO on Main: $violations", violations.isEmpty())
            val sorted = frameTimes.sorted()
            val report = JSONObject().put("debuggable", debuggable).put("verifiedUpgradeContentFiles", verifiedUpgradeFiles).put("buttonTouchToTestIdleNs", org.json.JSONArray(buttonTimes))
                .put("frameMetricsCount", sorted.size).put("frameP95Ns", if (sorted.isEmpty()) JSONObject.NULL else sorted[((sorted.size - 1) * .95).toInt()])
                .put("unchangedBindingRedraws", redraws).put("mainThreadDiskViolations", org.json.JSONArray(violations.toList()))
                .put("physicalTabletTimingClaim", false).put("scope", "Synthetic emulator touch-to-test-idle; not physical pen latency or FPS")
            val dir = File(requireNotNull(app.getExternalFilesDir(null)), "verification").apply { mkdirs() }
            File(dir, "ui-performance.json").writeText(report.toString(2))
        } finally {
            compose.runOnIdle {
                policy?.let(StrictMode::setThreadPolicy)
                model.config = oldConfig
                if (oldBook != null && oldPage != null) model.openPage(oldBook, oldPage) else model.closeNotebook()
            }
            await(model)
            compose.activity.window.removeOnFrameMetricsAvailableListener(listener); thread.quitSafely()
        }
    }
    private fun await(model: DefterViewModel) {
        compose.waitUntil(30_000) { !model.loading && !model.busy && !model.pageWindowLoading && model.pendingSaves == 0 }
        compose.runOnIdle { assertNull(model.error) }
    }
    private fun findCanvas(v: View): NotebookCanvas? {
        if (v is NotebookCanvas && !v.isReadOnly) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findCanvas(v.getChildAt(i))?.let { return it }
        return null
    }
}

package dev.defter.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only inventory, also runnable against the previously installed signed target. */
@RunWith(AndroidJUnit4::class)
class UpgradeInventoryDeviceTest {
    @Test fun captureExistingContentHashes() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val result = JSONObject()
        fun visit(file: File, path: String) {
            if (file.isDirectory) for (child in file.listFiles().orEmpty()) visit(child, "$path/${child.name}")
            else if (file.isFile) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                result.put(path, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
            }
        }
        visit(File(app.filesDir, "defter"), "files/defter")
        val out = File(requireNotNull(app.getExternalFilesDir(null)), "verification").apply { mkdirs() }
        File(out, "inventory-before-063.json").writeText(result.toString(2))
    }
}

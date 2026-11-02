package dev.defter.app.ui

import android.app.Application
import dev.defter.app.data.DocumentRepository
import dev.defter.app.MainActivity
import dev.defter.app.DefterViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.robolectric.util.ReflectionHelpers
import java.io.File

/**
 * Robolectric may reuse its synthetic app files between test methods/classes.
 * Call on the idle UI thread after the Activity's initial load, then await its
 * reload. Delete the exact store captured by the ViewModel, not a new repository
 * whose Context filesDir can differ after Robolectric swaps its Application.
 * This helper never runs in the APK or on a user's device.
 */
fun resetSyntheticLibrary(activity: MainActivity) {
    val model = ViewModelProvider(activity)[DefterViewModel::class.java]
    check(!model.loading && !model.busy && model.pendingSaves == 0)
    val repository = ReflectionHelpers.getField<DocumentRepository>(model, "repository")
    val testRoot = repository.root
    val modelFiles = model.getApplication<Application>().filesDir.canonicalFile
    val activityRoot = DocumentRepository(activity).root
    println("UI fixture actualRoot=${testRoot.canonicalPath}; activityRoot=${activityRoot.canonicalPath}; modelFiles=$modelFiles; beforeBooks=${model.library.notebooks.size}")
    check(testRoot.canonicalFile == File(modelFiles, "defter").canonicalFile) { "ViewModel store must stay inside synthetic application files" }
    check(!testRoot.exists() || testRoot.deleteRecursively()) { "Cannot reset synthetic UI test storage" }
    val empty = runBlocking { repository.loadLibrary() }
    check(empty.notebooks.isEmpty() && empty.folders.isEmpty()) { "Exact synthetic store was not empty after reset" }
    println("UI fixture actual store verified empty")
    model.navigateFolder(null)
    model.closeNotebook()
}

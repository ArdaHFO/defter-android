package dev.defter.app.audio

import android.app.Application
import androidx.lifecycle.ViewModelStore
import dev.defter.app.data.DocumentRepository
import dev.defter.app.model.AudioRecordingState
import dev.defter.app.model.TranscriptSegment
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
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AudioSearchViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val store = ViewModelStore()
    private lateinit var repository: DocumentRepository
    private lateinit var app: Application
    private lateinit var model: AudioViewModel
    private val originalLocale = Locale.getDefault()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRuntime.transcription.value = TranscriptionWorkState()
        app = RuntimeEnvironment.getApplication()
        repository = DocumentRepository(app)
        repository.root.deleteRecursively()
    }

    @After fun cleanup() {
        store.clear()
        scheduler.runCurrent()
        AudioRecorderSession.mutableState.value = AudioRecorderState()
        AudioRuntime.transcription.value = TranscriptionWorkState()
        Locale.setDefault(originalLocale)
        Dispatchers.resetMain()
    }

    @Test fun englishTitleAndTranscriptSearchAlsoPreservesTurkishDottedIMatchesRegardlessOfLocale() {
        val englishTitle = seed("INTEGRAL techniques", "An introductory lesson.")
        val englishTranscript = seed("Calculus lecture", "An Integral measures the area below a curve.")
        val turkishTranscript = seed("Matematik dersi", "İntegral alanı hesaplar.")
        val distractor = seed("Geometry", "Angles and triangles.")
        model = AudioViewModel(app)
        store.put("audio-search", model)
        await { !model.busy && model.library.audioItems.size == 4 }
        val expected = setOf(englishTitle, englishTranscript, turkishTranscript)

        for (locale in listOf(Locale.US, Locale.forLanguageTag("tr"))) {
            Locale.setDefault(locale)
            for (query in listOf("integral", "INTEGRAL", "İNTEGRAL")) {
                model.search(query)
                scheduler.runCurrent()
                await { !model.searching }
                assertEquals("Search '$query' under $locale must cover titles and transcript text", expected, model.matches)
                assertFalse(distractor in model.matches)
                assertNull(model.error)
            }
        }
        model.search("not present anywhere")
        scheduler.runCurrent()
        await { !model.searching }
        assertTrue(model.matches.isEmpty())
        assertNull(model.error)
    }

    private fun seed(title: String, text: String): String = runBlocking {
        val recording = repository.createAudioRecording(title)
        repository.setAudioState(recording.id, AudioRecordingState.READY)
        // Search needs stored transcript data, not playback or an artificial audio decoder.
        repository.updateTranscript(recording.id, listOf(TranscriptSegment(startMs = 0, endMs = 0, text = text)))
        recording.id
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            scheduler.advanceTimeBy(300)
            scheduler.runCurrent()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Audio search did not settle: ${model.error}")
    }
}

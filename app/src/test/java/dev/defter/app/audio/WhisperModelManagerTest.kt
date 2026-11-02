package dev.defter.app.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class WhisperModelManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "verified multilingual model fixture".toByteArray()
    private val spec get() = WhisperModelSpec("https://models.example/base.bin", bytes.size.toLong(), hash(bytes))
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun response(data: ByteArray, code: Int = 200, range: String? = null, length: Long = data.size.toLong(), input: InputStream = ByteArrayInputStream(data)) = object : WhisperDownloadResponse {
        override val status = code
        override val length = length
        override val contentRange = range
        override val stream = input
        override fun close() { input.close() }
    }

    @Test fun constructionAndRefreshNeverDownloadAndExistingModelMustMatchHash() = runBlocking {
        val folder = temporary.newFolder()
        val manager = WhisperModelManager(folder, spec) { _, _ -> error("No network expected") }
        assertFalse(manager.refresh())
        val model = File(folder, "ggml-base.bin")
        model.writeBytes(ByteArray(bytes.size))
        assertFalse(manager.refresh())
        assertNull(manager.verifiedModelFile())
        model.writeBytes(bytes)
        assertTrue(manager.refresh())
        assertEquals(model, manager.verifiedModelFile())
        assertEquals(WhisperModelStatus.READY, manager.state.value.status)
    }

    @Test fun interruptedDownloadResumesAtExactByteAndVerifiesWholeFile() = runBlocking {
        val folder = temporary.newFolder()
        val requests = mutableListOf<Long>()
        val firstInput = object : InputStream() {
            var done = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (done) throw IOException("simulated connection loss")
                done = true
                bytes.copyInto(buffer, offset, 0, 5)
                return 5
            }
        }
        val manager = WhisperModelManager(folder, spec) { _, offset ->
            requests += offset
            if (requests.size == 1) response(bytes, input = firstInput)
            else response(bytes.copyOfRange(offset.toInt(), bytes.size), 206, "bytes $offset-${bytes.lastIndex}/${bytes.size}")
        }
        try { manager.download(); fail("Expected interrupted transfer") } catch (_: IOException) { }
        assertEquals(5, File(folder, "ggml-base.bin.part").length())
        assertNull(manager.verifiedModelFile())
        manager.download()
        assertEquals(listOf(0L, 5L), requests)
        assertArrayEquals(bytes, manager.verifiedModelFile()!!.readBytes())
        assertFalse(File(folder, "ggml-base.bin.part").exists())
    }

    @Test fun ignoredRangeRestartsInsteadOfAppendingFullResponse() = runBlocking {
        val folder = temporary.newFolder()
        File(folder, "ggml-base.bin.part").writeBytes(bytes.copyOf(7))
        val manager = WhisperModelManager(folder, spec) { _, offset ->
            assertEquals(7, offset)
            response(bytes) // Server ignores Range.
        }
        manager.download()
        assertArrayEquals(bytes, manager.verifiedModelFile()!!.readBytes())
    }

    @Test fun wrongRangeCannotActivateOrOverwritePartialData() = runBlocking {
        val folder = temporary.newFolder()
        val partial = File(folder, "ggml-base.bin.part").apply { writeBytes(bytes.copyOf(5)) }
        val manager = WhisperModelManager(folder, spec) { _, _ -> response(bytes, 206, "bytes 0-${bytes.lastIndex}/${bytes.size}") }
        try { manager.download(); fail("Wrong range accepted") } catch (_: IOException) { }
        assertEquals(5, partial.length())
        assertNull(manager.verifiedModelFile())
        assertEquals(WhisperModelStatus.ERROR, manager.state.value.status)
    }

    @Test fun equalLengthCorruptModelFailsChecksumAndIsNotActivated() = runBlocking {
        val folder = temporary.newFolder()
        val manager = WhisperModelManager(folder, spec) { _, _ -> response(ByteArray(bytes.size)) }
        try { manager.download(); fail("Corrupt model accepted") } catch (_: IOException) { }
        assertNull(manager.verifiedModelFile())
        assertFalse(File(folder, "ggml-base.bin").exists())
        assertFalse(File(folder, "ggml-base.bin.part").exists())
        assertTrue(manager.state.value.message.orEmpty().contains("SHA-256"))
    }

    @Test fun oversizedUnknownLengthBodyCannotGrowBeyondPinnedSize() = runBlocking {
        val folder = temporary.newFolder()
        val manager = WhisperModelManager(folder, spec) { _, _ -> response(ByteArray(bytes.size + 1), length = -1) }
        try { manager.download(); fail("Oversized body accepted") } catch (_: IOException) { }
        assertTrue(File(folder, "ggml-base.bin.part").length() <= bytes.size)
        assertNull(manager.verifiedModelFile())
    }

    @Test fun completedPartialCanBeVerifiedWithoutAnotherNetworkRequest() = runBlocking {
        val folder = temporary.newFolder()
        File(folder, "ggml-base.bin.part").writeBytes(bytes)
        val manager = WhisperModelManager(folder, spec) { _, _ -> error("No network required") }
        manager.download()
        assertArrayEquals(bytes, manager.verifiedModelFile()!!.readBytes())
    }

    @Test fun cancellationRetainsPartialForResumeAndNeverPublishesReady() = runBlocking {
        val folder = temporary.newFolder()
        lateinit var manager: WhisperModelManager
        val input = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                bytes.copyInto(buffer, offset, 0, 5)
                manager.cancelDownload()
                return 5
            }
        }
        manager = WhisperModelManager(folder, spec) { _, _ -> response(bytes, input = input) }
        try { manager.download(); fail("Cancellation ignored") } catch (_: CancellationException) { }
        assertEquals(WhisperModelStatus.MISSING, manager.state.value.status)
        assertNull(manager.verifiedModelFile())
        assertEquals(5, File(folder, "ggml-base.bin.part").length())
    }

    @Test fun insecureModelLocationsAndInvalidPinnedDigestsAreRejected() {
        try { WhisperModelSpec("http://example.org/model", 1, hash(bytes)); fail() } catch (_: IllegalArgumentException) { }
        try { WhisperModelSpec("https://example.org/model", 1, "not-a-digest"); fail() } catch (_: IllegalArgumentException) { }
    }
}

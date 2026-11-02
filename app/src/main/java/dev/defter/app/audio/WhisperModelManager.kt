package dev.defter.app.audio

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class WhisperModelStatus { MISSING, CHECKING, DOWNLOADING, READY, ERROR }

data class WhisperModelState(
    val status: WhisperModelStatus = WhisperModelStatus.MISSING,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 147_951_465,
    val message: String? = null,
)

internal data class WhisperModelSpec(val url: String, val bytes: Long, val sha256: String) {
    init {
        require(URL(url).protocol == "https") { "Model adresi HTTPS olmalı." }
        require(bytes > 0 && sha256.matches(Regex("[a-f0-9]{64}")))
    }
    companion object {
        val BASE = WhisperModelSpec(
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin",
            147_951_465,
            "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        )
    }
}

internal interface WhisperDownloadResponse : Closeable {
    val status: Int
    val length: Long
    val contentRange: String?
    val stream: InputStream
}

internal fun interface WhisperDownloadTransport {
    fun open(url: String, offset: Long): WhisperDownloadResponse
}

/** Public, unauthenticated HTTPS only. The final bytes must match the pinned SHA-256. */
internal class HttpsWhisperDownloadTransport : WhisperDownloadTransport {
    override fun open(url: String, offset: Long): WhisperDownloadResponse {
        var location = URL(url)
        repeat(6) {
            require(location.protocol == "https") { "Güvensiz model yönlendirmesi reddedildi." }
            val connection = location.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val target = connection.getHeaderField("Location")
                        ?: throw IOException("Model yönlendirme adresi eksik.")
                    location = URL(location, target)
                    connection.disconnect()
                } else {
                    return object : WhisperDownloadResponse {
                        override val status = code
                        override val length = connection.contentLengthLong
                        override val contentRange = connection.getHeaderField("Content-Range")
                        override val stream: InputStream get() = connection.inputStream
                        override fun close() = connection.disconnect()
                    }
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Model için çok fazla yönlendirme alındı.")
    }
}

/** Does not access the network until download() is explicitly invoked. */
class WhisperModelManager internal constructor(
    private val directory: File,
    private val specification: WhisperModelSpec = WhisperModelSpec.BASE,
    private val transport: WhisperDownloadTransport = HttpsWhisperDownloadTransport(),
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "speech-models"))

    private val model = File(directory, "ggml-base.bin")
    private val partial = File(directory, "ggml-base.bin.part")
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(WhisperModelState(totalBytes = specification.bytes))
    val state: StateFlow<WhisperModelState> = mutableState.asStateFlow()
    @Volatile private var activeDownload: Job? = null
    @Volatile private var activeResponse: WhisperDownloadResponse? = null

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableState.value = state(WhisperModelStatus.CHECKING)
            try {
                val ready = verified(model)
                mutableState.value = state(if (ready) WhisperModelStatus.READY else WhisperModelStatus.MISSING)
                ready
            } catch (cancelled: CancellationException) {
                mutableState.value = state(WhisperModelStatus.MISSING)
                throw cancelled
            } catch (error: IOException) {
                mutableState.value = state(WhisperModelStatus.ERROR, message = error.message)
                false
            }
        }
    }

    fun verifiedModelFile(): File? = model.takeIf {
        mutableState.value.status == WhisperModelStatus.READY && it.isFile && it.length() == specification.bytes
    }

    fun cancelDownload() {
        activeDownload?.cancel(CancellationException("Model indirmesi duraklatıldı."))
        activeResponse?.close()
    }

    suspend fun download(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            activeDownload = currentCoroutineContext()[Job]
            try {
                check(directory.isDirectory || directory.mkdirs()) { "Model klasörü oluşturulamadı." }
                if (verified(model)) {
                    mutableState.value = state(WhisperModelStatus.READY)
                    return@withLock
                }
                if (partial.length() > specification.bytes) Files.delete(partial.toPath())
                var offset = partial.length()
                mutableState.value = state(WhisperModelStatus.DOWNLOADING, offset)
                if (offset < specification.bytes) {
                    transport.open(specification.url, offset).use { response ->
                        activeResponse = response
                        currentCoroutineContext().ensureActive()
                        if (response.status == 200) offset = 0 // Server may ignore Range. Never append full content.
                        else if (response.status == 206) validateRange(response.contentRange, offset, specification.bytes)
                        else throw IOException("Model indirilemedi (HTTP ${response.status}).")
                        val remaining = specification.bytes - offset
                        if (response.length > remaining) throw IOException("Model dosyası beklenen boyutu aşıyor.")
                        FileOutputStream(partial, offset > 0).use { output ->
                            response.stream.use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var lastPublished = offset
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) continue
                                    if (offset + count > specification.bytes) throw IOException("Model boyutu doğrulanamadı.")
                                    output.write(buffer, 0, count)
                                    offset += count
                                    if (offset - lastPublished >= 256 * 1024 || offset == specification.bytes) {
                                        mutableState.value = state(WhisperModelStatus.DOWNLOADING, offset)
                                        lastPublished = offset
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (partial.length() != specification.bytes) throw IOException("İndirme yarım kaldı. Yeniden başlatarak sürdürebilirsin.")
                mutableState.value = state(WhisperModelStatus.CHECKING, specification.bytes)
                if (!verified(partial)) {
                    Files.deleteIfExists(partial.toPath())
                    throw IOException("Model SHA-256 doğrulaması başarısız. Dosya kullanılmadı.")
                }
                currentCoroutineContext().ensureActive()
                Files.move(partial.toPath(), model.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                mutableState.value = state(WhisperModelStatus.READY)
            } catch (error: Exception) {
                if (error is CancellationException || !currentCoroutineContext().isActive) {
                    mutableState.value = state(WhisperModelStatus.MISSING, message = "İndirme duraklatıldı. Yeniden başlatarak sürdürebilirsin.")
                    throw CancellationException("Model indirmesi duraklatıldı.", error)
                }
                mutableState.value = state(WhisperModelStatus.ERROR, message = error.message ?: "Model indirilemedi.")
                throw error
            } finally {
                activeResponse = null
                activeDownload = null
            }
        }
    }

    private fun state(status: WhisperModelStatus, bytes: Long? = null, message: String? = null) = WhisperModelState(
        status, bytes ?: if (status == WhisperModelStatus.READY) specification.bytes else partial.length().coerceAtMost(specification.bytes),
        specification.bytes, message,
    )

    private suspend fun verified(file: File): Boolean {
        if (!file.isFile || file.length() != specification.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == specification.sha256
    }

    internal companion object {
        fun validateRange(header: String?, offset: Long, total: Long) {
            val values = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header.orEmpty())?.groupValues
                ?: throw IOException("Model devam indirmesi doğrulanamadı.")
            val start = values[1].toLongOrNull()
            val end = values[2].toLongOrNull()
            val length = values[3].toLongOrNull()
            if (start != offset || end == null || end < offset || end >= total || length != total) {
                throw IOException("Model devam indirmesinin aralığı geçersiz.")
            }
        }
    }
}

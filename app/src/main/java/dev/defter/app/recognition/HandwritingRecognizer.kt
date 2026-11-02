package dev.defter.app.recognition

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import dev.defter.app.model.InkKind
import dev.defter.app.model.StrokeElement
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The model download is a separate, explicit user action. Recognition never uploads ink. */
interface HandwritingRecognizer : Closeable {
    suspend fun isModelDownloaded(languageTag: String = "tr-TR"): Boolean
    suspend fun downloadModel(languageTag: String = "tr-TR")
    suspend fun recognize(strokes: List<StrokeElement>, languageTag: String = "tr-TR", preContext: String = ""): List<String>
}

class MlKitHandwritingRecognizer(context: Context) : HandwritingRecognizer {
    private val appContext = context.applicationContext
    private var closed = false
    private fun manager(): RemoteModelManager {
        check(!closed) { "El yazısı tanıyıcı kapatıldı." }
        MlKit.initialize(appContext)
        return RemoteModelManager.getInstance()
    }

    override suspend fun isModelDownloaded(languageTag: String): Boolean = manager().isModelDownloaded(model(languageTag)).awaitResult()

    override suspend fun downloadModel(languageTag: String) {
        manager().download(model(languageTag), DownloadConditions.Builder().build()).awaitResult()
    }

    override suspend fun recognize(strokes: List<StrokeElement>, languageTag: String, preContext: String): List<String> {
        val ink = buildInk(strokes)
        val model = model(languageTag)
        check(manager().isModelDownloaded(model).awaitResult()) { "Önce seçili dilin el yazısı modelini indir." }
        check(!closed) { "El yazısı tanıyıcı kapatıldı." }
        val recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        return try {
            val context = RecognitionContext.builder().setPreContext(preContext.takeLast(20)).build()
            recognizer.recognize(ink, context).awaitResult().candidates.map { it.text }.filter { it.isNotBlank() }.distinct().take(5)
        } finally { recognizer.close() }
    }

    override fun close() { closed = true }

    companion object {
        val supportedLanguages = linkedMapOf("tr-TR" to "Türkçe", "en-US" to "English")

        private fun model(languageTag: String): DigitalInkRecognitionModel {
            require(languageTag in supportedLanguages) { "Desteklenmeyen el yazısı dili." }
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(languageTag)
                ?: error("Bu dil için el yazısı modeli bulunamadı.")
            return DigitalInkRecognitionModel.builder(id).build()
        }

        internal fun buildInk(strokes: List<StrokeElement>): Ink {
            val pens = strokes.filter { it.kind == InkKind.PEN && !it.isShapeFragment && it.points.isNotEmpty() }
            require(pens.isNotEmpty()) { "Tanımak için kalemle yazılmış bir alan seç veya sayfaya yaz." }
            require(pens.sumOf { it.points.size.toLong() } <= 200_000L) { "Tek seferde daha küçük bir yazı alanı seç." }
            val builder = Ink.builder()
            pens.forEach { stroke ->
                val inkStroke = Ink.Stroke.builder()
                stroke.points.forEach { point ->
                    require(point.x.isFinite() && point.y.isFinite()) { "El yazısı koordinatları geçersiz." }
                    // Existing notes have no timestamps. ML Kit explicitly supports untimed points.
                    inkStroke.addPoint(Ink.Point.create(point.x, point.y))
                }
                builder.addStroke(inkStroke.build())
            }
            return builder.build()
        }
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(Executor { it.run() }) { task ->
        if (continuation.isActive) {
            if (task.isSuccessful) continuation.resume(task.result)
            else continuation.resumeWithException(task.exception ?: IllegalStateException("El yazısı işlemi iptal edildi."))
        }
    }
}

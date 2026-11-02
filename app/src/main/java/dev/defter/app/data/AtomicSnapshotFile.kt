package dev.defter.app.data

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * A checked atomic replacement on the app's private, same-filesystem storage (API 26+).
 * Android AtomicFile.finishWrite only logs rename failure. A note app must propagate it
 * instead of reporting a successful save. No non-atomic replacement fallback is used.
 */
internal class AtomicSnapshotFile(val baseFile: File) {
    private val pendingFile = File(baseFile.path + ".new")
    private val legacyBackup = File(baseFile.path + ".bak")

    fun openRead(): FileInputStream {
        recoverLegacyBackup()
        return FileInputStream(baseFile)
    }

    fun startWrite(): FileOutputStream {
        val parent = baseFile.parentFile ?: throw IOException("Kayıt yolu geçersiz.")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Kayıt klasörü oluşturulamadı.")
        recoverLegacyBackup()
        return FileOutputStream(pendingFile)
    }

    fun finishWrite(stream: FileOutputStream?) {
        requireNotNull(stream) { "Kayıt akışı açılmadı." }
        stream.flush()
        stream.fd.sync()
        stream.close()
        Files.move(pendingFile.toPath(), baseFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    fun failWrite(stream: FileOutputStream?) {
        try { stream?.close() } finally { Files.deleteIfExists(pendingFile.toPath()) }
    }

    private fun recoverLegacyBackup() {
        if (legacyBackup.exists()) {
            Files.move(legacyBackup.toPath(), baseFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
    }
}

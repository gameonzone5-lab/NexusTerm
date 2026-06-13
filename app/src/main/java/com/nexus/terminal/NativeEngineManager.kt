package com.nexus.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

class NativeEngineManager(private val context: Context) {
    private val TAG = "NexusEngine"
    private val engineDir = File(context.filesDir, "engine")
    private val libsDir = File(engineDir, "libs")
    val prootBinary = File(engineDir, "proot")

    init {
        if (!engineDir.exists()) engineDir.mkdirs()
        if (!libsDir.exists()) libsDir.mkdirs()
    }

    fun installEngine() {
        try {
            val assetManager = context.assets
            val inputStream = assetManager.open("nexus-engine.tar.gz")
            extractTarGz(inputStream, engineDir)

            val process = Runtime.getRuntime().exec("chmod 755 ${prootBinary.absolutePath}")
            process.waitFor()
            Log.d(TAG, "Engine installed at: ${engineDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Engine installation failed: ${e.message}")
            throw e
        }
    }

    fun getExecutionEnv(): Map<String, String> {
        return mapOf(
            "LD_LIBRARY_PATH" to libsDir.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_TMP_DIR" to context.cacheDir.absolutePath,
            "PATH" to "${engineDir.absolutePath}:/system/bin:/system/xbin"
        )
    }

    private fun extractTarGz(inputStream: InputStream, targetDir: File) {
        val tarIn = TarArchiveInputStream(GzipCompressorInputStream(inputStream))
        var entry = tarIn.nextEntry
        while (entry != null) {
            val outputFile = File(targetDir, entry.name)
            if (entry.isDirectory) {
                outputFile.mkdirs()
            } else {
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { outputStream ->
                    tarIn.copyTo(outputStream)
                }
                outputFile.setExecutable(true, false)
                outputFile.setReadable(true, false)
            }
            entry = tarIn.nextEntry
        }
        tarIn.close()
    }
}

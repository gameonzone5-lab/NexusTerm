package com.nexus.terminal

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class RootFSDownloader(private val context: Context) {
    private val TAG = "RootFSDownloader"
    private val client = OkHttpClient()

    fun downloadRootFS(url: String, targetFile: File): Boolean {
        return try {
            Log.d(TAG, "Starting download: $url")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            response.body?.let { body ->
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val data = ByteArray(4096)
                        var bytesRead: Int
                        while (input.read(data).also { bytesRead = it } != -1) {
                            output.write(data, 0, bytesRead)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            false
        }
    }
}

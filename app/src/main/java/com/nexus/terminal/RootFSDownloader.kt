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

    /**
     * উবুন্টু রুটএফএস ডাউনলোড করার মেইন ফাংশন।
     * @param url ডাউনলোডের ডিরেক্ট লিংক
     * @param targetFile যেখানে ফাইলটি সেভ হবে
     * @return ডাউনলোড সফল হলে true, না হলে false
     */
    fun downloadRootFS(url: String, targetFile: File): Boolean {
        return try {
            Log.d(TAG, "RootFS ডাউনলোড শুরু হচ্ছে: $url থেকে")
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            response.body?.let { body ->
                // মেমোরি ক্র্যাশ (OutOfMemoryError) এড়াতে সরাসরি ডিস্কে স্ট্রিম করা হচ্ছে
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val data = ByteArray(4096) // 4KB বাফার ব্যবহার করা হচ্ছে স্পিড বাড়ানোর জন্য
                        var bytesRead: Int
                        while (input.read(data).also { bytesRead = it } != -1) {
                            output.write(data, 0, bytesRead)
                        }
                    }
                }
            }
            Log.d(TAG, "RootFS সফলভাবে ডাউনলোড হয়েছে: ${targetFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ডাউনলোড ফেইল করেছে: ${e.message}")
            false
        }
    }
}

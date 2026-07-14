package com.maktabah.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class UpdateManager(private val context: Context, private val client: OkHttpClient) {

    fun shouldCheckForUpdates(): Boolean {
        val prefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
        val lastSkipTime = prefs.getLong("last_update_skip_time", 0L)
        val oneDayMillis = 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - lastSkipTime >= oneDayMillis
    }

    fun markUpdateSkipped() {
        val prefs = context.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("last_update_skip_time", System.currentTimeMillis()).apply()
    }

    fun isUpdateAvailable(tagName: String): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersion = packageInfo.versionName ?: "0.0.0"
            
            // Sederhananya compare string. Idealnya pakai semver comparison.
            // Tag biasanya berbentuk "v1.0.1" atau "1.0.1"
            val cleanTagName = tagName.removePrefix("v")
            val cleanCurrentVersion = currentVersion.removePrefix("v")
            
            compareVersions(cleanTagName, cleanCurrentVersion) > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        
        val length = maxOf(parts1.size, parts2.size)
        for (i in 0 until length) {
            val p1 = if (i < parts1.size) parts1[i] else 0
            val p2 = if (i < parts2.size) parts2[i] else 0
            if (p1 > p2) return 1
            if (p1 < p2) return -1
        }
        return 0
    }

    suspend fun downloadApk(url: String, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Maktabah-Android")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body
                val totalBytes = body.contentLength()
                val file = File(context.cacheDir, "update.apk")

                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                withContext(Dispatchers.Main) {
                                    onProgress((totalRead * 100 / totalBytes).toInt())
                                }
                            }
                        }
                    }
                }
                file
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

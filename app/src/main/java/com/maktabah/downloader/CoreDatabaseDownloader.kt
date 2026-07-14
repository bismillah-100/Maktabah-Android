package com.maktabah.downloader

import android.content.Context
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class DownloadProgress(val progress: Float, val detail: String)

class CoreDatabaseDownloader(private val context: Context) {
    private val client = OkHttpClient()

    // Mimic the iOS AppConfig URLs
    private val versionUrl = com.maktabah.BuildConfig.GITHUB_KITAB_VERSION_URL
    private val releaseBaseUrl = com.maktabah.BuildConfig.GITHUB_RELEASE_BASE_URL
    private val indexUrl = com.maktabah.BuildConfig.GITHUB_KITAB_INDEX_URL

    fun areCoreFilesReady(): Boolean {
        val mainFile = File(context.filesDir, "main.sqlite")
        val specialFile = File(context.filesDir, "special.sqlite")
        return mainFile.exists() && specialFile.exists()
    }

    fun startDownloadFlow(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress(0.0f, "Checking version..."))

        // 1. Fetch Version
        val versionRequest = Request.Builder().url(versionUrl).build()
        val versionResponse = client.newCall(versionRequest).execute()
        if (!versionResponse.isSuccessful) throw Exception("HTTP ${versionResponse.code} for version.txt")
        val tag = versionResponse.body.string().trim()

        val filesToDownload = listOf("main.sqlite", "special.sqlite")

        for ((index, fileName) in filesToDownload.withIndex()) {
            val zstFileName = "$fileName.zst"
            val downloadUrl = "$releaseBaseUrl/$tag/$zstFileName"
            
            emit(DownloadProgress((index.toFloat() / filesToDownload.size) * 0.8f, "Downloading $fileName..."))

            // 2. Download .zst
            val request = Request.Builder().url(downloadUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $zstFileName")
            val body = response.body

            val tempFile = File(context.cacheDir, zstFileName)
            var bytesCopied: Long = 0
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        
                        val fileProgress = if (totalBytes > 0) bytesCopied.toFloat() / totalBytes else 0f
                        val overallProgress = ((index + fileProgress) / filesToDownload.size) * 0.8f
                        emit(DownloadProgress(overallProgress, "Downloading $fileName (${bytesCopied / 1024 / 1024} MB)"))
                        
                        bytes = input.read(buffer)
                    }
                }
            }

            emit(DownloadProgress(((index + 1).toFloat() / filesToDownload.size) * 0.8f, "Extracting $fileName..."))

            // 3. Decompress .zst to .sqlite
            val destFile = File(context.filesDir, fileName)
            decompressZstdFile(tempFile, destFile)
            tempFile.delete()
        }

        // 4. Download index.json
        emit(DownloadProgress(0.9f, "Downloading book index..."))
        val indexRequest = Request.Builder().url(indexUrl).build()
        val indexResponse = client.newCall(indexRequest).execute()
        if (!indexResponse.isSuccessful) throw Exception("HTTP ${indexResponse.code} for index.json")
        val indexBody = indexResponse.body.string()
        File(context.filesDir, "index.json").writeText(indexBody)

        emit(DownloadProgress(1.0f, "Finished"))
    }.flowOn(Dispatchers.IO)

    private fun decompressZstdFile(source: File, dest: File) {
        source.inputStream().use { fileIn ->
            ZstdInputStream(fileIn).use { zstdIn ->
                dest.outputStream().use { fileOut ->
                    zstdIn.copyTo(fileOut)
                }
            }
        }
    }
}

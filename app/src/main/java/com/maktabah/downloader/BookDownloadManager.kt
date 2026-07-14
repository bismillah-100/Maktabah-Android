package com.maktabah.downloader

import android.content.Context
import android.util.Log
import com.github.luben.zstd.ZstdInputStream
import com.maktabah.database.BookArchiveIntegrator
import com.maktabah.models.BundleBookIndexEntry
import com.maktabah.models.IntegratePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class BookDownloadManager(private val context: Context) {
    private val indexUrl = com.maktabah.BuildConfig.GITHUB_KITAB_INDEX_URL

    suspend fun fetchIndex(): List<BundleBookIndexEntry> = withContext(Dispatchers.IO) {
        val indexFile = File(context.filesDir, "index.json")
        val jsonString = if (indexFile.exists()) {
            indexFile.readText()
        } else {
            val url = URL(indexUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext emptyList()
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }

        val jsonArray = JSONArray(jsonString)
        val entries = mutableListOf<BundleBookIndexEntry>()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            entries.add(
                BundleBookIndexEntry(
                    bkid = obj.getInt("bkid"),
                    filename = obj.getString("filename"),
                    release = obj.getString("release"),
                    sizeZst = if (obj.has("size_zst")) obj.getLong("size_zst") else null
                )
            )
        }
        entries
    }

    suspend fun downloadBook(
        entry: BundleBookIndexEntry,
        onPhaseChanged: ((IntegratePhase) -> Unit)? = null,
        onProgress: (Int) -> Unit
    ): Boolean =
        withContext(Dispatchers.IO) {
            val downloadUrl =
                "${com.maktabah.BuildConfig.GITHUB_RELEASE_BASE_URL}/${entry.release}/${entry.filename}"
            val dest = File(context.cacheDir, entry.filename)

            try {
                onPhaseChanged?.invoke(IntegratePhase.DOWNLOAD)
                val url = URL(downloadUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connect()

                val totalLength = conn.contentLength
                val input = conn.inputStream
                val output = FileOutputStream(dest)

                val data = ByteArray(4096)
                var total = 0L
                var count: Int

                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (totalLength > 0) {
                        onProgress((total * 100 / totalLength).toInt())
                    }
                    output.write(data, 0, count)
                }
                output.flush()
                output.close()
                input.close()

                val finalDest = if (entry.filename.endsWith(".zst")) {
                    val destFile =
                        File(context.cacheDir, "temp_" + entry.filename.removeSuffix(".zst"))
                    Log.d("BookDownloadManager", "Decompressing to ${destFile.absolutePath}")
                    decompressZstdFile(dest, destFile)
                    dest.delete()
                    destFile
                } else {
                    val destFile = File(context.cacheDir, "temp_" + entry.filename)
                    Log.d("BookDownloadManager", "Moving to ${destFile.absolutePath}")
                    if (dest.renameTo(destFile)) destFile else {
                        // Fallback if rename fails
                        dest.copyTo(destFile, overwrite = true)
                        dest.delete()
                        destFile
                    }
                }

                // After downloading and optional decompression, integrate into archive
                Log.d(
                    "BookDownloadManager",
                    "Download complete for book ${entry.bkid}, filename: ${entry.filename}. Starting integration..."
                )
                val archiveId = BookArchiveIntegrator.getArchiveIdForBook(context, entry.bkid)
                if (archiveId != null) {
                    Log.d(
                        "BookDownloadManager",
                        "Found archiveId $archiveId for book ${entry.bkid}. Integrating..."
                    )
                    val success = BookArchiveIntegrator.integrateDatabase(
                        context,
                        entry.bkid,
                        archiveId,
                        finalDest,
                        onPhaseChanged
                    )
                    if (!success) {
                        Log.e("BookDownloadManager", "Integration FAILED for book ${entry.bkid}")
                        return@withContext false
                    }
                    Log.d("BookDownloadManager", "Integration SUCCESS for book ${entry.bkid}")
                } else {
                    Log.e(
                        "BookDownloadManager",
                        "archiveId NOT FOUND for book ${entry.bkid} in main.sqlite"
                    )
                    // Clean up
                    if (finalDest.exists()) finalDest.delete()
                    return@withContext false
                }

                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

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

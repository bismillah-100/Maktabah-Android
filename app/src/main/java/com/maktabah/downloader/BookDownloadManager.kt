package com.maktabah.downloader

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.github.luben.zstd.ZstdInputStream
import com.maktabah.database.BookArchiveIntegrator
import com.maktabah.models.BundleBookIndexEntry
import com.maktabah.models.IntegratePhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.Reader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL

class BookDownloadManager(private val context: Context) {
    private val indexUrl = com.maktabah.BuildConfig.GITHUB_KITAB_INDEX_URL

    suspend fun fetchIndex(): List<BundleBookIndexEntry> = withContext(Dispatchers.IO) {
        val indexFile = File(context.filesDir, "index.json")
        if (indexFile.exists()) {
            return@withContext try {
                indexFile.bufferedReader().use { parseIndexEntries(it) }
            } catch (e: Exception) {
                Log.w("BookDownloadManager", "Failed to read local index.json", e)
                emptyList()
            }
        }

        val url = URL(indexUrl)
        val conn = url.openConnection() as HttpURLConnection
        val jsonString = try {
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext emptyList()
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        val entries = StringReader(jsonString).use { parseIndexEntries(it) }
        if (entries.isNotEmpty()) {
            try {
                val tempFile = File(context.filesDir, "index.json.tmp")
                tempFile.writeText(jsonString)
                if (!tempFile.renameTo(indexFile)) {
                    tempFile.copyTo(indexFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                Log.w("BookDownloadManager", "Failed to cache index.json locally", e)
            }
        }
        entries
    }

    private fun parseIndexEntries(reader: Reader): List<BundleBookIndexEntry> {
        val entries = mutableListOf<BundleBookIndexEntry>()
        JsonReader(reader).use { json ->
            if (json.peek() != JsonToken.BEGIN_ARRAY) return emptyList()
            json.beginArray()
            while (json.hasNext()) {
                var bkid = 0
                var authno = 0
                var filename = ""
                var release = ""
                var sizeZst: Long? = null
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "bkid" -> bkid = if (json.peek() == JsonToken.NULL) { json.nextNull(); 0 } else json.nextInt()
                        "authno" -> authno = if (json.peek() == JsonToken.NULL) { json.nextNull(); 0 } else json.nextInt()
                        "filename" -> filename = if (json.peek() == JsonToken.NULL) { json.nextNull(); "" } else json.nextString()
                        "release" -> release = if (json.peek() == JsonToken.NULL) { json.nextNull(); "" } else json.nextString()
                        "size_zst" -> sizeZst = if (json.peek() == JsonToken.NULL) { json.nextNull(); null } else json.nextLong()
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                if (bkid > 0) {
                    entries.add(
                        BundleBookIndexEntry(
                            bkid = bkid,
                            filename = filename,
                            release = release,
                            sizeZst = sizeZst,
                            authno = authno,
                        )
                    )
                }
            }
            json.endArray()
        }
        return entries
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

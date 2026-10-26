package com.maktabah.database.updater

import com.maktabah.config.AppConfig
import com.maktabah.models.AuthIndexEntry
import com.maktabah.models.BookIndexEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class BookUpdateRemoteSource(
    private val httpClient: OkHttpClient = defaultHttpClient,
) {
    companion object {
        val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    suspend fun fetchIndexEntries(url: String): List<BookIndexEntry> = withContext(Dispatchers.IO) {
        val csv = fetchCsvString(url)
        parseIndexCsv(csv)
    }

    suspend fun fetchAuthIndexEntries(url: String): List<AuthIndexEntry> = withContext(Dispatchers.IO) {
        val csv = fetchCsvString(url)
        parseAuthIndexCsv(csv)
    }

    private fun fetchCsvString(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Maktabah-Android")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} saat mengambil index dari server")
            }
            return response.body.string()
        }
    }

    fun downloadFile(
        url: String,
        directory: File,
        filePrefix: String,
        isSqlite: Boolean,
    ): File {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Maktabah-Android")
            .build()
        val ext = if (isSqlite) ".sqlite" else ""
        val destFile = File(directory, "${filePrefix}_${UUID.randomUUID()}$ext")

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Gagal mengunduh file (HTTP ${response.code})")
            }
            response.body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return destFile
    }

    fun parseIndexCsv(csv: String): List<BookIndexEntry> {
        val rows = parseCsv(csv, ';')
        if (rows.isEmpty()) return emptyList()
        val dataRows = trimHeaderIfNeeded(rows, "bkid")
        return dataRows.mapNotNull { cols ->
            if (cols.size < 6) return@mapNotNull null
            val bkid = cols[0].trim().toIntOrNull() ?: return@mapNotNull null
            val cat = cols[1].trim().toIntOrNull() ?: return@mapNotNull null
            val ver = cols[2].trim().toLongOrNull() ?: return@mapNotNull null
            val idFile = cols[3].trim()
            val size = cols[4].trim().toLongOrNull() ?: return@mapNotNull null
            val bkName = cols[5].trim()
            BookIndexEntry(
                bkid = bkid,
                bk = bkName,
                category = cat,
                versionName = ver,
                downloadURL = "${AppConfig.GOOGLE_DRIVE_DOWNLOAD_BASE}$idFile",
                fileSize = size,
            )
        }
    }

    fun parseAuthIndexCsv(csv: String): List<AuthIndexEntry> {
        val rows = parseCsv(csv, ';')
        if (rows.isEmpty()) return emptyList()
        val dataRows = trimHeaderIfNeeded(rows, "authid")
        return dataRows.mapNotNull { cols ->
            if (cols.size < 3) return@mapNotNull null
            val authId = cols[0].trim().toIntOrNull() ?: return@mapNotNull null
            val ver = cols[1].trim().toLongOrNull() ?: return@mapNotNull null
            val idFile = cols[2].trim()
            AuthIndexEntry(
                authId = authId,
                versionName = ver,
                downloadURL = "${AppConfig.GOOGLE_DRIVE_DOWNLOAD_BASE}$idFile",
            )
        }
    }

    private fun trimHeaderIfNeeded(rows: List<List<String>>, headerKey: String): List<List<String>> {
        val firstCell = rows.firstOrNull()?.firstOrNull()?.trim() ?: return rows
        return if (firstCell.equals(headerKey, ignoreCase = true)) rows.drop(1) else rows
    }

    private fun parseCsv(csv: String, separator: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var insideQuotes = false

        for (char in csv) {
            when (char) {
                '"' -> insideQuotes = !insideQuotes
                separator -> {
                    if (insideQuotes) {
                        currentField.append(char)
                    } else {
                        currentRow.add(currentField.toString())
                        currentField.clear()
                    }
                }
                '\n' -> {
                    if (insideQuotes) {
                        currentField.append(char)
                    } else {
                        currentRow.add(currentField.toString())
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                        currentField.clear()
                    }
                }
                '\r' -> continue
                else -> currentField.append(char)
            }
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow)
        }
        return rows
    }
}

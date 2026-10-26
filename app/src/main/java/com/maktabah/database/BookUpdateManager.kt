package com.maktabah.database

import android.content.Context
import com.maktabah.config.AppConfig
import com.maktabah.database.updater.BookUpdateLocalSource
import com.maktabah.database.updater.BookUpdateRemoteSource
import com.maktabah.models.AuthIndexEntry
import com.maktabah.models.BookIndexEntry
import com.maktabah.models.BookUpdateItem
import com.maktabah.models.BookUpdateMetadata
import com.maktabah.models.BookUpdateResult
import com.maktabah.models.UpdateAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BookUpdateManager {
    const val DRIVE_LINK = AppConfig.GOOGLE_DRIVE_DOWNLOAD_BASE
    const val MAIN_CSV_URL = AppConfig.BOOK_UPDATE_MAIN_CSV_URL
    const val AUTH_CSV_URL = AppConfig.BOOK_UPDATE_AUTH_CSV_URL

    private val remoteSource = BookUpdateRemoteSource()
    private val localSource = BookUpdateLocalSource()

    data class AuthorContext(
        val authId: Int,
        val versionName: Long,
        val downloadURL: String,
    )

    data class StagedBookUpdate(
        val entry: BookIndexEntry,
        val metadata: BookUpdateMetadata,
        val downloadedBookFile: File,
        val authorContext: AuthorContext?,
        val workingDirectory: File,
    )

    // ── 1. Fetch & Compare Available Updates ──────────────────────────────────

    suspend fun fetchAvailableUpdates(
        context: Context,
        indexUrl: String = MAIN_CSV_URL,
    ): List<BookUpdateItem> = withContext(Dispatchers.IO) {
        val entries = remoteSource.fetchIndexEntries(indexUrl)
        if (entries.isEmpty()) return@withContext emptyList()

        val categoriesMap = localSource.loadCategories(context)
        val localBooksMap = localSource.loadLocalBooks(context)

        entries.map { entry ->
            val local = localBooksMap[entry.bkid]
            val exists = local != null
            val currentVer = local?.second
            val bookName = local?.first?.takeIf { it.isNotEmpty() } ?: entry.bk
            val categoryName = categoriesMap[entry.category] ?: ""

            BookUpdateItem(
                id = entry.bkid,
                bookName = bookName,
                category = entry.category,
                categoryName = categoryName,
                existsInLibrary = exists,
                currentVersion = currentVer,
                newVersion = entry.versionName,
                fileSize = entry.fileSize,
                downloadURL = entry.downloadURL,
            )
        }
    }

    // ── 2. Stage Book Download (Metadata DB + Book DB) ────────────────────────

    suspend fun stageBookDownload(
        context: Context,
        entry: BookIndexEntry,
        authIndex: Map<Int, AuthIndexEntry>,
    ): StagedBookUpdate = withContext(Dispatchers.IO) {
        val workingDir = getWorkingDirectory(context)
        val metadataFile = remoteSource.downloadFile(
            url = entry.downloadURL,
            directory = workingDir,
            filePrefix = "metadata_${entry.bkid}",
            isSqlite = true,
        )

        val metadata = try {
            localSource.readBookMetadata(metadataFile, entry.bkid)
                ?: throw IllegalStateException("Metadata kitab (tabel main_update) tidak ditemukan untuk buku ${entry.bkid}.")
        } finally {
            localSource.removeDatabaseAndSidecars(metadataFile)
        }

        val link = metadata.link?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Link download buku tidak tersedia untuk buku ${entry.bkid}.")

        val bookUrl = if (link.startsWith("http")) link else "$DRIVE_LINK$link"
        val downloadedBookFile = remoteSource.downloadFile(
            url = bookUrl,
            directory = workingDir,
            filePrefix = "book_${metadata.bkid}",
            isSqlite = true,
        )

        val authorContext = metadata.authno?.let { authId ->
            authIndex[authId]?.let { authEntry ->
                AuthorContext(
                    authId = authId,
                    versionName = authEntry.versionName,
                    downloadURL = authEntry.downloadURL,
                )
            }
        }

        StagedBookUpdate(
            entry = entry,
            metadata = metadata,
            downloadedBookFile = downloadedBookFile,
            authorContext = authorContext,
            workingDirectory = workingDir,
        )
    }

    // ── 3. Apply Staged Update (Author + Archive Integration + 0bok) ──────────

    suspend fun applyStagedBookUpdate(
        context: Context,
        staged: StagedBookUpdate,
    ): BookUpdateResult = withContext(Dispatchers.IO) {
        try {
            val versionState = localSource.getBookVersionState(context, staged.metadata.bkid)
            val exists = versionState.existsInLibrary
            val needsUpdate = !exists || versionState.currentVersion == null || versionState.currentVersion != staged.entry.versionName

            if (exists && !needsUpdate) {
                return@withContext BookUpdateResult(
                    bookId = staged.metadata.bkid,
                    catId = staged.entry.category,
                    action = UpdateAction.SKIPPED,
                )
            }

            // 1. Update Author jika diperlukan
            staged.authorContext?.let { authCtx ->
                ensureAuthor(
                    context = context,
                    authId = authCtx.authId,
                    downloadUrl = authCtx.downloadURL,
                    workingDirectory = staged.workingDirectory,
                    newVersion = authCtx.versionName,
                )
            }

            // 2. Integrasi tabel b{id}, t{id} & FTS5 ke archive
            val integrated = BookArchiveIntegrator.integrateDatabase(
                context = context,
                bookId = staged.metadata.bkid,
                archiveId = staged.metadata.archive,
                downloadedDbFile = staged.downloadedBookFile,
            )
            if (!integrated) {
                throw IllegalStateException("Gagal mengintegrasikan database untuk buku ${staged.metadata.bkid}")
            }

            // 3. Simpan metadata ke main.sqlite (0bok)
            localSource.saveBookMetadata(context, staged.metadata, exists)

            BookUpdateResult(
                bookId = staged.metadata.bkid,
                catId = staged.metadata.cat ?: staged.entry.category,
                action = if (exists) UpdateAction.UPDATED else UpdateAction.INSERTED,
            )
        } finally {
            localSource.removeDatabaseAndSidecars(staged.downloadedBookFile)
        }
    }

    private fun ensureAuthor(
        context: Context,
        authId: Int,
        downloadUrl: String,
        workingDirectory: File,
        newVersion: Long,
    ) {
        if (!localSource.isAuthorOutdated(context, authId, newVersion)) return

        val downloadedAuthFile = remoteSource.downloadFile(
            url = downloadUrl,
            directory = workingDirectory,
            filePrefix = "auth_$authId",
            isSqlite = true,
        )
        try {
            val authorRow = localSource.readAuthorFromDownloadedDb(downloadedAuthFile, authId) ?: return
            localSource.saveAuthor(context, authorRow)
        } finally {
            localSource.removeDatabaseAndSidecars(downloadedAuthFile)
        }
    }

    // ── 4. Network & CSV Delegation ──────────────────────────────────────────

    suspend fun fetchIndexEntries(url: String = MAIN_CSV_URL): List<BookIndexEntry> =
        remoteSource.fetchIndexEntries(url)

    suspend fun fetchAuthIndexEntries(url: String = AUTH_CSV_URL): List<AuthIndexEntry> =
        remoteSource.fetchAuthIndexEntries(url)

    internal fun parseIndexCsv(csv: String): List<BookIndexEntry> =
        remoteSource.parseIndexCsv(csv)

    internal fun parseAuthIndexCsv(csv: String): List<AuthIndexEntry> =
        remoteSource.parseAuthIndexCsv(csv)

    // ── 5. Directory Helpers ──────────────────────────────────────────────────

    fun getWorkingDirectory(context: Context): File {
        val dir = File(context.cacheDir, "Updates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun cleanupWorkingDirectory(context: Context) {
        val dir = File(context.cacheDir, "Updates")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }
}

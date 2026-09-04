package com.maktabah.cloudKit

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.database.HistoryDatabaseManager
import com.maktabah.database.ResultsHandler
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.Annotation
import com.maktabah.models.ReadingEntry
import com.maktabah.models.SyncFolder
import com.maktabah.models.SyncResult
import com.maktabah.ui.history.HistoryViewModel
import com.maktabah.utils.isNetworkError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class CloudKitSyncManager {
    private val syncMutex = Mutex()

    // region History Upload Buffer (debounce, mirip iOS)
    private val historyBufferMutex = Mutex()
    private val historyUploadBuffer = mutableMapOf<String, ReadingEntry>()
    private var historyDebounceJob: Job? = null
    private val historyUploadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Upload history entries ke CloudKit dengan buffer + debounce 2 detik.
     * Entry yang masuk dalam window 2 detik digabung jadi satu batch.
     * Tidak suspend — aman dipanggil dari UI tanpa terikat lifecycle composable.
     * Setiap entry ditambahkan ke sync_pending sebelum buffer, dihapus setelah berhasil.
     */
    fun uploadHistory(context: Context, entries: List<ReadingEntry>) {
        if (entries.isEmpty()) return
        val appContext = context.applicationContext
        historyUploadScope.launch {
            // Tandai sebagai pending sebelum masuk buffer
            val db = HistoryDatabaseManager.instance
            for (entry in entries) {
                val key = entry.ckRecordId ?: entry.bookId.toString()
                db?.addPendingSync(key, if (!entry.isFavorite && entry.lastOpenedAt == null) "delete" else "upload")
            }
            historyBufferMutex.withLock {
                for (entry in entries) {
                    val key = entry.ckRecordId ?: entry.bookId.toString()
                    historyUploadBuffer[key] = entry
                }
                historyDebounceJob?.cancel()
                historyDebounceJob = historyUploadScope.launch {
                    delay(2_000.milliseconds)
                    flushHistoryBuffer(appContext)
                }
            }
        }
    }

    private suspend fun flushHistoryBuffer(context: Context) {
        val entriesToUpload: List<ReadingEntry>
        historyBufferMutex.withLock {
            if (historyUploadBuffer.isEmpty()) return
            entriesToUpload = historyUploadBuffer.values.toList()
            historyUploadBuffer.clear()
        }
        val recordsToSave = JSONArray()
        val recordIDsToDelete = JSONArray()
        val uploadedIds = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()
        for (entry in entriesToUpload) {
            val recordId = entry.ckRecordId ?: entry.bookId.toString()
            if (!entry.isFavorite && entry.lastOpenedAt == null) {
                recordIDsToDelete.put(recordId)
                deletedIds.add(recordId)
            } else {
                recordsToSave.put(buildReadingEntryRecord(entry, recordId))
                uploadedIds.add(recordId)
            }
        }
        val result = CloudKitCoreManager.shared.modifyRecords(context, recordsToSave, recordIDsToDelete)
        if (result.isSuccess) {
            // Upload berhasil — hapus dari antrian pending
            HistoryDatabaseManager.instance?.removePendingSync(uploadedIds + deletedIds)
        }
    }

    /**
     * Retry upload/delete entries yang masih di sync_pending dari sesi sebelumnya.
     * Dipanggil saat app resume (ON_RESUME lifecycle event) sebelum fetchChanges.
     */
    suspend fun retryPendingSyncs(context: Context, historyViewModel: HistoryViewModel) {
        withContext(Dispatchers.IO) {
            val db = HistoryDatabaseManager.instance ?: return@withContext
            val pendingUploads = db.fetchPendingSync("upload")
            val pendingDeletes = db.fetchPendingSync("delete")
            if (pendingUploads.isEmpty() && pendingDeletes.isEmpty()) return@withContext

            val allEntries = historyViewModel.entriesByBookId.value
            val entriesToRetry = mutableListOf<ReadingEntry>()

            // Cari entries yang masih pending upload di memory ViewModel
            for (ckId in pendingUploads) {
                val entry = allEntries.values.find { (it.ckRecordId ?: it.bookId.toString()) == ckId }
                if (entry != null) entriesToRetry.add(entry)
            }
            // Buat dummy delete entries untuk ckIds yang perlu dihapus
            for (ckId in pendingDeletes) {
                entriesToRetry.add(ReadingEntry(
                    bookId = ckId.toIntOrNull() ?: continue,
                    ckRecordId = ckId,
                    lastOpenedAt = null,
                    isFavorite = false
                ))
            }
            if (entriesToRetry.isEmpty()) {
                // Tidak ada di memory — data sudah tidak relevan, bersihkan pending
                db.removePendingSync(pendingUploads + pendingDeletes)
                return@withContext
            }
            uploadHistory(context, entriesToRetry)
        }
    }

    private fun buildReadingEntryRecord(entry: ReadingEntry, recordId: String): JSONObject =
        JSONObject().apply {
            put("recordType", "ReadingEntry")
            put("recordName", recordId)
            put("zoneID", JSONObject().apply {
                put("zoneName", "AnnotationsZone")
                put("ownerRecordName", "_defaultOwner_")
            })
            put("fields", JSONObject().apply {
                put("bookId", JSONObject().apply { put("value", entry.bookId) })
                put("isFavorite", JSONObject().apply { put("value", if (entry.isFavorite) 1 else 0) })
                val lastModSec = if (entry.updatedAt > 10000000000L) entry.updatedAt / 1000L else entry.updatedAt
                put("lastModified", JSONObject().apply { put("value", lastModSec) })
                put("lastContentId", JSONObject().apply { put("value", entry.lastContentId ?: JSONObject.NULL) })
                put("lastOpenedAt", JSONObject().apply { put("value", entry.lastOpenedAt ?: JSONObject.NULL) })
                put("favoritedAt", JSONObject().apply { put("value", entry.favoritedAt ?: JSONObject.NULL) })
                put("positionUpdatedAt", JSONObject().apply { put("value", entry.positionUpdatedAt ?: JSONObject.NULL) })
            })
        }

    // endregion

    private suspend fun fetchChangesInternal(
        context: Context,
        annotationManager: AnnotationManager,
        historyViewModel: HistoryViewModel
    ): String? = withContext(Dispatchers.IO) {
            val annotationsToSave = mutableListOf<Annotation>()
            val entriesToSave = mutableListOf<ReadingEntry>()
            val foldersToSave = mutableListOf<SyncFolder>()
            val resultsToSave = mutableListOf<SyncResult>()
            val recordIdsToDelete = mutableListOf<String>()
            val resultRecordIdsToDelete = mutableListOf<String>()

            val result = CloudKitCoreManager.shared.fetchChanges(
                context = context,
                zoneName = "AnnotationsZone",
                onRecordReceived = { record ->
                    val recordType = record.optString("recordType", "")
                    val ckRecordId = record.optString("recordName", "")
                    val fields = record.optJSONObject("fields") ?: return@fetchChanges

                    when (recordType) {
                        "SearchFolder" -> foldersToSave.add(parseSearchFolderRecord(ckRecordId, fields))
                        "SearchResult" -> resultsToSave.add(parseSearchResultRecord(ckRecordId, fields))
                        "Annotation" -> annotationsToSave.add(parseAnnotationRecord(ckRecordId, fields))
                        "ReadingEntry" -> parseReadingEntryRecord(ckRecordId, fields)?.let { entriesToSave.add(it) }
                    }
                },
                onRecordDeleted = { ckRecordId ->
                    recordIdsToDelete.add(ckRecordId)
                    resultRecordIdsToDelete.add(ckRecordId)
                }
            )

            result.fold(
                onSuccess = {
                    // Apply Annotation changes in batch
                    if (annotationsToSave.isNotEmpty() || recordIdsToDelete.isNotEmpty()) {
                        for (ckRecordId in recordIdsToDelete) {
                            annotationManager.deleteByCkRecordId(ckRecordId)
                        }
                        for (annotation in annotationsToSave) {
                            annotationManager.insertOrUpdate(annotation, fromSync = true)
                        }
                    }

                    // Apply ReadingEntry changes in batch
                    if (entriesToSave.isNotEmpty() || recordIdsToDelete.isNotEmpty()) {
                        historyViewModel.applyCloudKitChanges(entriesToSave, recordIdsToDelete)
                    }

                    // Apply SearchFolder/SearchResult changes
                    if (foldersToSave.isNotEmpty() || resultsToSave.isNotEmpty() || resultRecordIdsToDelete.isNotEmpty()) {
                        val resultsHandler = getResultsHandler(context)
                        resultsHandler.applyCloudKitFolderChanges(foldersToSave, resultRecordIdsToDelete)
                        resultsHandler.applyCloudKitResultChanges(resultsToSave, resultRecordIdsToDelete)
                        com.maktabah.ui.search.CloudKitResultSyncHelper.syncEvent.tryEmit(Unit)
                    }

                    return@withContext it.second
                },
                onFailure = {
                    if (it.message == "No Web Auth Token") return@withContext null
                    if (isNetworkError(it, context)) {
                        return@withContext context.getString(R.string.no_internet_connection)
                    }
                    return@withContext "Sync failed: ${it.localizedMessage ?: it.message}"
                }
            )
        }

    suspend fun fetchChanges(
        context: Context,
        annotationManager: AnnotationManager,
        historyViewModel: HistoryViewModel
    ): String? = syncMutex.withLock {
        fetchChangesInternal(context, annotationManager, historyViewModel)
    }

    private suspend fun syncAnnotationsInternal(context: Context, annotationManager: AnnotationManager): String? =
        withContext(Dispatchers.IO) {
            val annotations = annotationManager.getUnsyncedAnnotations()
            val deletedIds = annotationManager.getDeletedRecordIds()

            if (annotations.isEmpty() && deletedIds.isEmpty()) return@withContext "Success"

            val recordsToSave = JSONArray()
            for (annotation in annotations) {
                val recordName = annotation.ckRecordId ?: UUID.randomUUID().toString()
                if (annotation.ckRecordId == null) {
                    annotationManager.insertOrUpdate(annotation.copy(ckRecordId = recordName), fromSync = true)
                }
                val record = buildAnnotationRecord(annotation, recordName)
                recordsToSave.put(record)
            }

            val recordIDsToDelete = JSONArray()
            deletedIds.forEach { recordIDsToDelete.put(it) }

            val result =
                CloudKitCoreManager.shared.modifyRecords(context, recordsToSave, recordIDsToDelete)
            if (result.isSuccess) {
                annotationManager.clearDeletedRecordIds(deletedIds)
                val uploadedIds = annotations.mapNotNull { it.ckRecordId }
                annotationManager.clearPendingUploads(uploadedIds)
                scheduleAnnotationSnapshotUpload(context, annotationManager)
                "Success"
            } else {
                val ex = result.exceptionOrNull()
                val msg = ex?.message
                if (msg == "No Web Auth Token") {
                    null
                } else if (isNetworkError(ex, context)) {
                    context.getString(R.string.no_internet_connection)
                } else {
                    "Failed: ${ex?.localizedMessage ?: msg}"
                }
            }
        }

    suspend fun syncAnnotations(context: Context, annotationManager: AnnotationManager): String? =
        syncMutex.withLock {
            syncAnnotationsInternal(context, annotationManager)
        }

    private val annotationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val annotationThrottleMutex = Mutex()
    private var annotationThrottleJob: Job? = null
    private var hasPendingAnnotationSync = false

    /**
     * Throttle 5 detik untuk sinkronisasi anotasi lokal ke CloudKit.
     * Mencegah spam HTTP POST saat pengguna membuat atau menghapus anotasi secara beruntun.
     */
    fun scheduleAnnotationSync(context: Context, annotationManager: AnnotationManager) {
        val appContext = context.applicationContext
        annotationScope.launch {
            annotationThrottleMutex.withLock {
                hasPendingAnnotationSync = true
                if (annotationThrottleJob?.isActive == true) return@launch

                annotationThrottleJob = annotationScope.launch {
                    while (true) {
                        delay(5_000.milliseconds)
                        annotationThrottleMutex.withLock {
                            hasPendingAnnotationSync = false
                        }
                        syncAnnotations(appContext, annotationManager)
                        val shouldRepeat = annotationThrottleMutex.withLock { hasPendingAnnotationSync }
                        if (!shouldRepeat) break
                    }
                }
            }
        }
    }

    /** Manual sync — dipakai oleh onSyncHistoryRequested di ReaderScreen. */
    suspend fun syncHistoryAndFavorites(context: Context, entries: List<ReadingEntry>): String? =
        withContext(Dispatchers.IO) {
            val recordsToSave = JSONArray()
            val recordIDsToDelete = JSONArray()
            for (entry in entries) {
                coroutineContext.ensureActive()
                val recordId = entry.ckRecordId ?: entry.bookId.toString()
                if (!entry.isFavorite && entry.lastOpenedAt == null) {
                    recordIDsToDelete.put(recordId)
                } else {
                    recordsToSave.put(buildReadingEntryRecord(entry, recordId))
                }
            }
            val result = CloudKitCoreManager.shared.modifyRecords(context, recordsToSave, recordIDsToDelete)
            if (result.isSuccess) {
                "Success: Uploaded history and favorites"
            } else {
                val ex = result.exceptionOrNull()
                val msg = ex?.message
                if (msg == "No Web Auth Token") {
                    null
                } else if (isNetworkError(ex, context)) {
                    context.getString(R.string.no_internet_connection)
                } else {
                    "Failed: ${ex?.localizedMessage ?: msg}"
                }
            }
        }

    suspend fun checkAccountChangeAndSync(
        context: Context,
        annotationManager: AnnotationManager,
        historyViewModel: HistoryViewModel
    ) = withContext(Dispatchers.IO) {
        android.util.Log.d("CloudKitSync", "checkAccountChangeAndSync started")
        val result = CloudKitCoreManager.shared.fetchUserInfo(context)
        result.onFailure {
            android.util.Log.e(
                "CloudKitSync",
                "checkAccountChangeAndSync failed: ${it.message}",
                it
            )
        }
        result.onSuccess { json ->
            android.util.Log.d("CloudKitSync", "checkAccountChangeAndSync success")
            val userRecordName = json.optString("userRecordName")
            val nameObj = json.optJSONObject("name")
            val firstName = nameObj?.optString("first") ?: ""
            val lastName = nameObj?.optString("last") ?: ""
            val email = json.optString("email", "")
            val displayName = if (firstName.isNotEmpty() || lastName.isNotEmpty()) {
                "$firstName $lastName".trim()
            } else {
                userRecordName
            }

            val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
            val currentStoredUser = prefs.getString("ckUserRecordName", null)
            android.util.Log.d(
                "CloudKitSync",
                "currentStoredUser: $currentStoredUser, newUser: $userRecordName"
            )

            if (currentStoredUser != null && currentStoredUser != userRecordName) {
                android.util.Log.d(
                    "CloudKitSync",
                    "User changed! Resetting local annotations and history."
                )
                annotationManager.clearAll()
                historyViewModel.clearAll()
                getResultsHandler(context).nukeDatabase()
                prefs.edit {
                    remove("ckSyncToken_AnnotationsZone")
                        .apply()
                }
            }

            prefs.edit {
                putString("ckUserRecordName", userRecordName)
                    .putString("ckUserDisplayName", displayName)
                    .putString("ckUserEmail", email)
                    .apply()
            }
        }
    }

    // region Search Results Sync

    private suspend fun syncResultsInternal(context: Context): String? =
        withContext(Dispatchers.IO) {
            val handler = getResultsHandler(context)
            val pendingUploads = handler.fetchPendingSync("upload")
            val pendingDeletes = handler.fetchPendingSync("delete")

            if (pendingUploads.isEmpty() && pendingDeletes.isEmpty()) return@withContext "No results to sync"

            val folders = handler.fetchSyncFoldersByCkRecordIds(pendingUploads)
            val results = handler.fetchSyncResultsByCkRecordIds(pendingUploads)

            val recordsToSave = JSONArray()

            for (folder in folders) {
                val recordName = folder.ckRecordId ?: continue
                recordsToSave.put(buildSearchFolderRecord(folder, recordName))
            }

            for (res in results) {
                val recordName = res.ckRecordId ?: continue
                recordsToSave.put(buildSearchResultRecord(res, recordName))
            }

            val recordIDsToDelete = JSONArray()
            for (ckId in pendingDeletes) {
                recordIDsToDelete.put(ckId)
            }

            val result = CloudKitCoreManager.shared.modifyRecords(context, recordsToSave, recordIDsToDelete)
            if (result.isSuccess) {
                val handledIds = mutableListOf<String>()
                handledIds.addAll(pendingDeletes)
                handledIds.addAll(pendingUploads)
                if (handledIds.isNotEmpty()) {
                    handler.removePendingSync(handledIds)
                }
                "Success"
            } else {
                val ex = result.exceptionOrNull()
                val msg = ex?.message
                if (msg == "No Web Auth Token") {
                    null
                } else if (isNetworkError(ex, context)) {
                    context.getString(R.string.no_internet_connection)
                } else {
                    "Failed: ${ex?.localizedMessage ?: msg}"
                }
            }
        }

    suspend fun syncResults(context: Context): String? = syncMutex.withLock {
        syncResultsInternal(context)
    }

    /**
     * Retry semua operasi pending (Anotasi, History & Favorit, Hasil Pencarian) dan tarik perubahan terbaru.
     * Dipanggil saat aplikasi resume (ON_RESUME) dan saat koneksi internet pulih.
     */
    suspend fun retryAllPendingOperations(
        context: Context,
        annotationManager: AnnotationManager,
        historyViewModel: HistoryViewModel,
    ) = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
            if (prefs.getString("ckWebAuthToken", null) == null) return@withContext

            // 1. Periksa perubahan akun user CloudKit
            checkAccountChangeAndSync(context, annotationManager, historyViewModel)

            // 2. Retry upload & delete Anotasi
            syncAnnotationsInternal(context, annotationManager)

            // 3. Retry upload & delete History & Favorit
            retryPendingSyncs(context, historyViewModel)

            // 4. Retry upload & delete Hasil Pencarian
            syncResultsInternal(context)

            // 5. Tarik perubahan terbaru dari CloudKit
            fetchChangesInternal(context, annotationManager, historyViewModel)
        }
    }

    private fun getResultsHandler(context: Context): ResultsHandler {
        return ResultsHandler.getInstance(context)
    }

    // region Helper Record Parsers & Builders

    private fun parseSearchFolderRecord(ckRecordId: String, fields: JSONObject): SyncFolder {
        val name = fields.optJSONObject("name")?.optString("value", "") ?: ""
        val lastModifiedVal = fields.optJSONObject("lastModified")?.optLong("value", 0L) ?: 0L
        val parentCkRecordId = fields.optJSONObject("parentCkRecordId")?.let {
            val v = it.opt("value")
            if (v == null || v == JSONObject.NULL) null else v.toString()
        }
        return SyncFolder(
            name = name,
            ckRecordId = ckRecordId,
            lastModified = lastModifiedVal,
            parentCkRecordId = parentCkRecordId
        )
    }

    private fun parseSearchResultRecord(ckRecordId: String, fields: JSONObject): SyncResult {
        val name = fields.optJSONObject("name")?.optString("value", "") ?: ""
        val query = fields.optJSONObject("query")?.optString("value", "") ?: ""
        val archive = fields.optJSONObject("archive")?.optInt("value", 0) ?: 0
        val bkId = fields.optJSONObject("bkId")?.optInt("value", 0) ?: 0
        val contentId = fields.optJSONObject("contentId")?.optString("value", "") ?: ""
        val lastModifiedVal = fields.optJSONObject("lastModified")?.optLong("value", 0L) ?: 0L
        val folderCkRecordId = fields.optJSONObject("folderCkRecordId")?.let {
            val v = it.opt("value")
            if (v == null || v == JSONObject.NULL) null else v.toString()
        }
        val searchMode = fields.optJSONObject("searchMode")?.optInt("value", 0) ?: 0
        val nearDistance = fields.optJSONObject("nearDistance")?.optInt("value", 10) ?: 10
        return SyncResult(
            name = name,
            query = query,
            archive = archive,
            bkId = bkId,
            contentId = contentId,
            ckRecordId = ckRecordId,
            lastModified = lastModifiedVal,
            folderCkRecordId = folderCkRecordId,
            searchMode = searchMode,
            nearDistance = nearDistance
        )
    }

    private fun parseAnnotationRecord(ckRecordId: String, fields: JSONObject): Annotation {
        val bkId = fields.optJSONObject("bkId")?.optInt("value", 0) ?: 0
        val contentId = fields.optJSONObject("contentId")?.optInt("value", 0) ?: 0
        val colorHex = fields.optJSONObject("colorHex")?.optString("value", "") ?: ""
        val note = fields.optJSONObject("note")?.let { if (it.has("value")) it.optString("value") else null }
        val type = fields.optJSONObject("type")?.optInt("value", 0) ?: 0
        val createdAtVal = fields.optJSONObject("createdAt")?.optLong("value", 0L) ?: 0L
        val createdAt = if (createdAtVal in 1L..9999999999L) createdAtVal * 1000L else createdAtVal
        val page = fields.optJSONObject("page")?.optInt("value", 0) ?: 0
        val contextText = fields.optJSONObject("context")?.optString("value", "") ?: ""
        val rangeLocation = fields.optJSONObject("rangeLocation")?.optInt("value", 0) ?: 0
        val rangeLength = fields.optJSONObject("rangeLength")?.optInt("value", 0) ?: 0
        val rangeDiacLocation = fields.optJSONObject("rangeDiacLocation")?.optInt("value", 0) ?: 0
        val rangeDiacLength = fields.optJSONObject("rangeDiacLength")?.optInt("value", 0) ?: 0
        val part = fields.optJSONObject("part")?.optInt("value", 0) ?: 0

        val tagsObj = fields.optJSONObject("tags")
        val tagsList = tagsObj?.optJSONArray("value")
        val tags = if (tagsList != null && tagsList.length() > 0) {
            (0 until tagsList.length()).joinToString(",") { tagsList.getString(it) }
        } else ""

        val lastModifiedVal = fields.optJSONObject("lastModified")?.optLong("value", 0L) ?: 0L
        val lastModified = if (lastModifiedVal > 0L) {
            if (lastModifiedVal in 1L..9999999999L) lastModifiedVal * 1000L else lastModifiedVal
        } else null

        return Annotation(
            bkId = bkId,
            contentId = contentId,
            colorHex = colorHex,
            note = note,
            type = type,
            createdAt = createdAt,
            page = page,
            context = contextText,
            rangeLocation = rangeLocation,
            rangeLength = rangeLength,
            rangeDiacLocation = rangeDiacLocation,
            rangeDiacLength = rangeDiacLength,
            part = part,
            tags = tags,
            ckRecordId = ckRecordId,
            lastModified = lastModified
        )
    }

    private fun parseReadingEntryRecord(ckRecordId: String, fields: JSONObject): ReadingEntry? {
        val bookIdObj = fields.optJSONObject("bookId")
        val bookId = if (bookIdObj != null) {
            when (val v = bookIdObj.opt("value")) {
                null -> 0
                JSONObject.NULL -> 0
                is Number -> v.toInt()
                else -> v.toString().toIntOrNull() ?: 0
            }
        } else 0
        if (bookId == 0) return null

        val lastContentIdObj = fields.optJSONObject("lastContentId")
        val lastContentId = if (lastContentIdObj != null) {
            when (val v = lastContentIdObj.opt("value")) {
                null -> null
                JSONObject.NULL -> null
                is Number -> v.toInt()
                else -> v.toString().toIntOrNull()
            }
        } else null

        val lastOpenedAtObj = fields.optJSONObject("lastOpenedAt")
        val lastOpenedAtVal = if (lastOpenedAtObj != null) {
            when (val v = lastOpenedAtObj.opt("value")) {
                null -> null
                JSONObject.NULL -> null
                is Number -> v.toLong()
                else -> v.toString().toLongOrNull()
            }
        } else null
        val lastOpenedAt = if (lastOpenedAtVal != null && lastOpenedAtVal in 1L..9999999999L) lastOpenedAtVal * 1000L else lastOpenedAtVal

        val favoritedAtObj = fields.optJSONObject("favoritedAt")
        val favoritedAtVal = if (favoritedAtObj != null) {
            when (val v = favoritedAtObj.opt("value")) {
                null -> null
                JSONObject.NULL -> null
                is Number -> v.toLong()
                else -> v.toString().toLongOrNull()
            }
        } else null
        val favoritedAt = if (favoritedAtVal != null && favoritedAtVal in 1L..9999999999L) favoritedAtVal * 1000L else favoritedAtVal

        val positionUpdatedAtObj = fields.optJSONObject("positionUpdatedAt")
        val positionUpdatedAtVal = if (positionUpdatedAtObj != null) {
            when (val v = positionUpdatedAtObj.opt("value")) {
                null -> null
                JSONObject.NULL -> null
                is Number -> v.toLong()
                else -> v.toString().toLongOrNull()
            }
        } else null
        val positionUpdatedAt = if (positionUpdatedAtVal != null && positionUpdatedAtVal in 1L..9999999999L) positionUpdatedAtVal * 1000L else positionUpdatedAtVal

        val isFavoriteObj = fields.optJSONObject("isFavorite")
        val isFavorite = if (isFavoriteObj != null) {
            when (val v = isFavoriteObj.opt("value")) {
                null -> false
                JSONObject.NULL -> false
                is Boolean -> v
                is Number -> v.toInt() == 1
                else -> v.toString().toIntOrNull() == 1
            }
        } else false

        val lastModifiedObj = fields.optJSONObject("lastModified")
        val lastModifiedVal = if (lastModifiedObj != null) {
            when (val v = lastModifiedObj.opt("value")) {
                null -> 0L
                JSONObject.NULL -> 0L
                is Number -> v.toLong()
                else -> v.toString().toLongOrNull() ?: 0L
            }
        } else 0L
        val lastModified = if (lastModifiedVal in 1L..9999999999L) lastModifiedVal * 1000L else lastModifiedVal

        return ReadingEntry(
            bookId = bookId,
            lastContentId = lastContentId,
            lastOpenedAt = lastOpenedAt,
            favoritedAt = favoritedAt,
            positionUpdatedAt = positionUpdatedAt,
            isFavorite = isFavorite,
            updatedAt = lastModified,
            ckRecordId = ckRecordId
        )
    }

    private fun buildAnnotationRecord(annotation: Annotation, recordName: String): JSONObject =
        JSONObject().apply {
            put("recordType", "Annotation")
            put("recordName", recordName)
            put("zoneID", JSONObject().apply {
                put("zoneName", "AnnotationsZone")
                put("ownerRecordName", "_defaultOwner_")
            })
            put("fields", JSONObject().apply {
                put("bkId", JSONObject().apply { put("value", annotation.bkId) })
                put("contentId", JSONObject().apply { put("value", annotation.contentId) })
                put("colorHex", JSONObject().apply { put("value", annotation.colorHex) })
                if (annotation.note != null) put(
                    "note",
                    JSONObject().apply { put("value", annotation.note) })
                put("type", JSONObject().apply { put("value", annotation.type) })
                val createdAtSec = if (annotation.createdAt > 10000000000L) annotation.createdAt / 1000L else annotation.createdAt
                put("createdAt", JSONObject().apply { put("value", createdAtSec) })
                put("page", JSONObject().apply { put("value", annotation.page) })
                put("context", JSONObject().apply { put("value", annotation.context) })
                put(
                    "rangeLocation",
                    JSONObject().apply { put("value", annotation.rangeLocation) })
                put(
                    "rangeLength",
                    JSONObject().apply { put("value", annotation.rangeLength) })
                put(
                    "rangeDiacLocation",
                    JSONObject().apply { put("value", annotation.rangeDiacLocation) })
                put(
                    "rangeDiacLength",
                    JSONObject().apply { put("value", annotation.rangeDiacLength) })
                put("part", JSONObject().apply { put("value", annotation.part) })

                val tagsArray = JSONArray()
                if (annotation.tags.isNotEmpty()) {
                    annotation.tags.split(",").forEach { tagsArray.put(it) }
                }
                put("tags", JSONObject().apply { put("value", tagsArray) })

                val lastModVal = annotation.lastModified ?: System.currentTimeMillis()
                val lastModSec = if (lastModVal > 10000000000L) lastModVal / 1000L else lastModVal
                put("lastModified", JSONObject().apply { put("value", lastModSec) })
            })
        }

    private fun buildSearchFolderRecord(folder: SyncFolder, recordName: String): JSONObject =
        JSONObject().apply {
            put("recordType", "SearchFolder")
            put("recordName", recordName)
            put("zoneID", JSONObject().apply {
                put("zoneName", "AnnotationsZone")
                put("ownerRecordName", "_defaultOwner_")
            })
            put("fields", JSONObject().apply {
                put("name", JSONObject().apply { put("value", folder.name) })
                put("lastModified", JSONObject().apply { put("value", folder.lastModified ?: (System.currentTimeMillis() / 1000L)) })
                put("parentCkRecordId", JSONObject().apply { put("value", folder.parentCkRecordId ?: JSONObject.NULL) })
            })
        }

    private fun buildSearchResultRecord(res: SyncResult, recordName: String): JSONObject =
        JSONObject().apply {
            put("recordType", "SearchResult")
            put("recordName", recordName)
            put("zoneID", JSONObject().apply {
                put("zoneName", "AnnotationsZone")
                put("ownerRecordName", "_defaultOwner_")
            })
            put("fields", JSONObject().apply {
                put("name", JSONObject().apply { put("value", res.name) })
                put("query", JSONObject().apply { put("value", res.query) })
                put("archive", JSONObject().apply { put("value", res.archive) })
                put("bkId", JSONObject().apply { put("value", res.bkId) })
                put("contentId", JSONObject().apply { put("value", res.contentId) })
                put("lastModified", JSONObject().apply { put("value", res.lastModified ?: (System.currentTimeMillis() / 1000L)) })
                put("folderCkRecordId", JSONObject().apply { put("value", res.folderCkRecordId ?: JSONObject.NULL) })
                put("searchMode", JSONObject().apply { put("value", res.searchMode) })
                put("nearDistance", JSONObject().apply { put("value", res.nearDistance) })
            })
        }

    // endregion

    // endregion

    // region Widget Snapshot Records (upload-only for iOS/macOS widgets, 10s debounce)

    private val prefLastAnnotationSnapshot = "ck_last_uploaded_annotation_snapshot"

    private val snapshotScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val snapshotMutex = Mutex()
    private var annotationSnapshotDebounceJob: Job? = null

    fun scheduleAnnotationSnapshotUpload(context: Context, annotationManager: AnnotationManager) {
        val appContext = context.applicationContext
        snapshotScope.launch {
            snapshotMutex.withLock {
                annotationSnapshotDebounceJob?.cancel()
                annotationSnapshotDebounceJob = snapshotScope.launch {
                    delay(10_000.milliseconds)
                    uploadAnnotationSnapshotIfChanged(appContext, annotationManager)
                }
            }
        }
    }

    private suspend fun uploadAnnotationSnapshotIfChanged(
        context: Context,
        annotationManager: AnnotationManager
    ): Boolean {
        val recordsToSave = JSONArray()
        val signature = compileAnnotationSnapshotRecordIfChanged(context, annotationManager, recordsToSave) ?: return false
        val result = CloudKitCoreManager.shared.modifyRecords(context, recordsToSave, JSONArray())
        if (result.isSuccess) {
            saveLastAnnotationSnapshotSignature(context, signature)
            return true
        }
        return false
    }

    private fun toAppleReferenceSeconds(timestamp: Long): Double {
        val timeMs = if (timestamp > 0L) timestamp else System.currentTimeMillis()
        val unixSec = if (timeMs > 10000000000L) timeMs / 1000L else timeMs
        return unixSec.toDouble() - 978307200.0
    }

    private fun buildSnapshotRecord(recordName: String, recordType: String, jsonString: String): JSONObject =
        JSONObject().apply {
            put("recordType", recordType)
            put("recordName", recordName)
            put("zoneID", JSONObject().apply {
                put("zoneName", "AnnotationsZone")
                put("ownerRecordName", "_defaultOwner_")
            })
            put("fields", JSONObject().apply {
                put("payload", JSONObject().apply {
                    put("value", Base64.encodeToString(
                        jsonString.toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    ))
                    put("type", "BYTES")
                })
            })
        }

    private suspend fun compileAnnotationSnapshotRecordIfChanged(
        context: Context,
        annotationManager: AnnotationManager,
        recordsToSave: JSONArray
    ): String? {
        val top6 = annotationManager.getLatestAnnotations(6)
        val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val lastSignature = prefs.getString(prefLastAnnotationSnapshot, null)

        if (top6.isEmpty() && lastSignature == null) {
            return null
        }

        val libraryDbFile = File(context.filesDir, "main.sqlite")
        val bookNames = if (libraryDbFile.exists() && top6.isNotEmpty()) {
            LibraryDataManager(libraryDbFile).getBookNames(top6.map { it.bkId })
        } else emptyMap()

        val itemsArray = JSONArray()
        for (ann in top6) {
            val itemObj = JSONObject().apply {
                put("id", (ann.id ?: 0L).toString())
                put("bookId", ann.bkId)
                put("bookTitle", bookNames[ann.bkId] ?: "Book ID: ${ann.bkId}")
                put("content", ann.context)
                put("colorHex", ann.colorHex)
                put("type", ann.type)
                put("date", toAppleReferenceSeconds(ann.createdAt))
            }
            itemsArray.put(itemObj)
        }

        val currentSignature = itemsArray.toString()
        if (currentSignature == lastSignature) {
            return null
        }

        val snapshotJson = JSONObject().apply {
            put("items", itemsArray)
            put("lastUpdated", toAppleReferenceSeconds(System.currentTimeMillis()))
        }.toString()

        val snapshotRecord = buildSnapshotRecord("SharedAnnotationSnapshot", "AnnotationSnapshot", snapshotJson)
        recordsToSave.put(snapshotRecord)
        return currentSignature
    }

    private fun saveLastAnnotationSnapshotSignature(context: Context, signature: String) {
        val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        prefs.edit { putString(prefLastAnnotationSnapshot, signature).apply() }
    }

    // endregion
}

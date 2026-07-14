package com.maktabah.cloudKit

import android.content.Context
import androidx.core.content.edit
import com.maktabah.database.AnnotationManager
import com.maktabah.models.Annotation
import com.maktabah.models.ReadingEntry
import com.maktabah.ui.history.HistoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class CloudKitSyncManager {
    private val syncMutex = Mutex()

    suspend fun fetchChanges(
        context: Context,
        annotationManager: AnnotationManager,
        historyViewModel: HistoryViewModel
    ): String? = syncMutex.withLock {
        withContext(Dispatchers.IO) {
        val result = CloudKitCoreManager.shared.fetchChanges(
            context = context,
            zoneName = "AnnotationsZone",
            onRecordReceived = { record ->
                val recordType = record.optString("recordType", "")
                val ckRecordId = record.optString("recordName", "")
                val fields = record.optJSONObject("fields") ?: return@fetchChanges

                if (recordType == "Annotation") {
                    val bkId = fields.optJSONObject("bkId")?.optInt("value", 0) ?: 0
                    val contentId = fields.optJSONObject("contentId")?.optInt("value", 0) ?: 0
                    val colorHex = fields.optJSONObject("colorHex")?.optString("value", "") ?: ""
                    val note = fields.optJSONObject("note")?.let { if (it.has("value")) it.optString("value") else null }
                    val type = fields.optJSONObject("type")?.optInt("value", 0) ?: 0
                    val createdAt = fields.optJSONObject("createdAt")?.optLong("value", 0L) ?: 0L
                    val page = fields.optJSONObject("page")?.optInt("value", 0) ?: 0
                    val contextText = fields.optJSONObject("context")?.optString("value", "") ?: ""
                    val rangeLocation =
                        fields.optJSONObject("rangeLocation")?.optInt("value", 0) ?: 0
                    val rangeLength = fields.optJSONObject("rangeLength")?.optInt("value", 0) ?: 0
                    val rangeDiacLocation =
                        fields.optJSONObject("rangeDiacLocation")?.optInt("value", 0) ?: 0
                    val rangeDiacLength =
                        fields.optJSONObject("rangeDiacLength")?.optInt("value", 0) ?: 0
                    val part = fields.optJSONObject("part")?.optInt("value", 0) ?: 0

                    val tagsObj = fields.optJSONObject("tags")
                    val tagsList = tagsObj?.optJSONArray("value")
                    val tags = if (tagsList != null && tagsList.length() > 0) {
                        (0 until tagsList.length()).joinToString(",") { tagsList.getString(it) }
                    } else ""

                    val lastModified = fields.optJSONObject("lastModified")?.optLong("value", 0L)

                    val annotation = Annotation(
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
                    annotationManager.insertOrUpdate(annotation, fromSync = true)
                } else if (recordType == "ReadingEntry") {
                    val bookId =
                        fields.optJSONObject("bookId")?.optInt("value", 0) ?: return@fetchChanges
                    val lastContentId = fields.optJSONObject("lastContentId")?.optInt("value")
                    val lastOpenedAt = fields.optJSONObject("lastOpenedAt")?.optLong("value")
                    val favoritedAt = fields.optJSONObject("favoritedAt")?.optLong("value")
                    val positionUpdatedAt =
                        fields.optJSONObject("positionUpdatedAt")?.optLong("value")
                    val isFavorite = fields.optJSONObject("isFavorite")?.optInt("value", 0) == 1
                    val lastModified =
                        fields.optJSONObject("lastModified")?.optLong("value", 0L) ?: 0L

                    val entry = ReadingEntry(
                        bookId = bookId,
                        lastContentId = if (lastContentId == 0 && fields.optJSONObject("lastContentId")?.has("value") != true
                        ) null else lastContentId,
                        lastOpenedAt = if (lastOpenedAt == 0L && fields.optJSONObject("lastOpenedAt")?.has("value") != true
                        ) null else lastOpenedAt,
                        favoritedAt = if (favoritedAt == 0L && fields.optJSONObject("favoritedAt")?.has("value") != true
                        ) null else favoritedAt,
                        positionUpdatedAt = if (positionUpdatedAt == 0L && fields.optJSONObject("positionUpdatedAt")?.has("value") != true
                        ) null else positionUpdatedAt,
                        isFavorite = isFavorite,
                        updatedAt = lastModified,
                        ckRecordId = ckRecordId
                    )
                    historyViewModel.applyCloudKitChanges(listOf(entry))
                }
            },
            onRecordDeleted = { ckRecordId ->
                annotationManager.deleteByCkRecordId(ckRecordId)
            }
        )

        result.fold(
            onSuccess = { return@withContext it.second },
            onFailure = { 
                if (it.message == "No Web Auth Token") return@withContext null
                return@withContext "Exception: ${it.message}" 
            }
        )
    } }

    suspend fun syncAnnotations(context: Context, annotationManager: AnnotationManager): String? =
        syncMutex.withLock {
            withContext(Dispatchers.IO) {
            val annotations = annotationManager.getUnsyncedAnnotations()
            val deletedIds = annotationManager.getDeletedRecordIds()

            val recordsToSave = JSONArray()
            for (annotation in annotations) {
                val recordName = annotation.ckRecordId ?: UUID.randomUUID().toString()
                if (annotation.ckRecordId == null) {
                    annotationManager.insertOrUpdate(annotation.copy(ckRecordId = recordName), fromSync = true)
                }
                val record = JSONObject().apply {
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
                        put("createdAt", JSONObject().apply { put("value", annotation.createdAt) })
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

                        val lastMod = annotation.lastModified ?: System.currentTimeMillis()
                        put("lastModified", JSONObject().apply { put("value", lastMod) })
                    })
                }
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
                "Success"
            } else {
                val msg = result.exceptionOrNull()?.message
                if (msg == "No Web Auth Token") null else "Failed: $msg"
            }
        } }

    suspend fun syncHistoryAndFavorites(context: Context, entries: List<ReadingEntry>) =
        withContext(Dispatchers.IO) {
            val recordsToSave = JSONArray()
            for (entry in entries) {
                val record = JSONObject().apply {
                    put("recordType", "ReadingEntry")
                    put("recordName", entry.ckRecordId ?: entry.bookId.toString())
                    put("zoneID", JSONObject().apply {
                        put("zoneName", "AnnotationsZone")
                        put("ownerRecordName", "_defaultOwner_")
                    })
                    put("fields", JSONObject().apply {
                        put("bookId", JSONObject().apply { put("value", entry.bookId) })
                        put(
                            "isFavorite",
                            JSONObject().apply { put("value", if (entry.isFavorite) 1 else 0) })
                        put("lastModified", JSONObject().apply { put("value", entry.updatedAt) })
                        if (entry.lastContentId != null) put(
                            "lastContentId",
                            JSONObject().apply { put("value", entry.lastContentId) })
                        if (entry.lastOpenedAt != null) put(
                            "lastOpenedAt",
                            JSONObject().apply { put("value", entry.lastOpenedAt) })
                        if (entry.favoritedAt != null) put(
                            "favoritedAt",
                            JSONObject().apply { put("value", entry.favoritedAt) })
                        if (entry.positionUpdatedAt != null) put(
                            "positionUpdatedAt",
                            JSONObject().apply { put("value", entry.positionUpdatedAt) })
                    })
                }
                recordsToSave.put(record)
            }

            val result =
                CloudKitCoreManager.shared.modifyRecords(context, recordsToSave, JSONArray())
            if (result.isSuccess) {
                "Success: Uploaded history and favorites"
            } else {
                "Failed: ${result.exceptionOrNull()?.message}"
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
            android.util.Log.d("CloudKitSync", "checkAccountChangeAndSync success: $json")
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
}

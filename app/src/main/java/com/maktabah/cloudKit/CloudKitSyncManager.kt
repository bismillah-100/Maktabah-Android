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
import kotlinx.coroutines.ensureActive
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
            val annotationsToSave = mutableListOf<Annotation>()
            val entriesToSave = mutableListOf<ReadingEntry>()
            val recordIdsToDelete = mutableListOf<String>()

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
                        val createdAtVal = fields.optJSONObject("createdAt")?.optLong("value", 0L) ?: 0L
                        val createdAt = if (createdAtVal in 1L..9999999999L) createdAtVal * 1000L else createdAtVal
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

                        val lastModifiedVal = fields.optJSONObject("lastModified")?.optLong("value", 0L) ?: 0L
                        val lastModified = if (lastModifiedVal > 0L) {
                            if (lastModifiedVal in 1L..9999999999L) lastModifiedVal * 1000L else lastModifiedVal
                        } else null

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
                        annotationsToSave.add(annotation)
                    } else if (recordType == "ReadingEntry") {
                        val bookIdObj = fields.optJSONObject("bookId")
                        val bookId = if (bookIdObj != null) {
                            val v = bookIdObj.opt("value")
                            if (v == null || v == JSONObject.NULL) 0
                            else if (v is Number) v.toInt()
                            else v.toString().toIntOrNull() ?: 0
                        } else 0
                        if (bookId == 0) return@fetchChanges

                        val lastContentIdObj = fields.optJSONObject("lastContentId")
                        val lastContentId = if (lastContentIdObj != null) {
                            val v = lastContentIdObj.opt("value")
                            if (v == null || v == JSONObject.NULL) null
                            else if (v is Number) v.toInt()
                            else v.toString().toIntOrNull()
                        } else null

                        val lastOpenedAtObj = fields.optJSONObject("lastOpenedAt")
                        val lastOpenedAtVal = if (lastOpenedAtObj != null) {
                            val v = lastOpenedAtObj.opt("value")
                            if (v == null || v == JSONObject.NULL) null
                            else if (v is Number) v.toLong()
                            else v.toString().toLongOrNull()
                        } else null
                        val lastOpenedAt = if (lastOpenedAtVal != null && lastOpenedAtVal in 1L..9999999999L) lastOpenedAtVal * 1000L else lastOpenedAtVal

                        val favoritedAtObj = fields.optJSONObject("favoritedAt")
                        val favoritedAtVal = if (favoritedAtObj != null) {
                            val v = favoritedAtObj.opt("value")
                            if (v == null || v == JSONObject.NULL) null
                            else if (v is Number) v.toLong()
                            else v.toString().toLongOrNull()
                        } else null
                        val favoritedAt = if (favoritedAtVal != null && favoritedAtVal in 1L..9999999999L) favoritedAtVal * 1000L else favoritedAtVal

                        val positionUpdatedAtObj = fields.optJSONObject("positionUpdatedAt")
                        val positionUpdatedAtVal = if (positionUpdatedAtObj != null) {
                            val v = positionUpdatedAtObj.opt("value")
                            if (v == null || v == JSONObject.NULL) null
                            else if (v is Number) v.toLong()
                            else v.toString().toLongOrNull()
                        } else null
                        val positionUpdatedAt = if (positionUpdatedAtVal != null && positionUpdatedAtVal in 1L..9999999999L) positionUpdatedAtVal * 1000L else positionUpdatedAtVal

                        val isFavoriteObj = fields.optJSONObject("isFavorite")
                        val isFavorite = if (isFavoriteObj != null) {
                            val v = isFavoriteObj.opt("value")
                            if (v == null || v == JSONObject.NULL) false
                            else if (v is Boolean) v
                            else if (v is Number) v.toInt() == 1
                            else v.toString().toIntOrNull() == 1
                        } else false

                        val lastModifiedObj = fields.optJSONObject("lastModified")
                        val lastModifiedVal = if (lastModifiedObj != null) {
                            val v = lastModifiedObj.opt("value")
                            if (v == null || v == JSONObject.NULL) 0L
                            else if (v is Number) v.toLong()
                            else v.toString().toLongOrNull() ?: 0L
                        } else 0L
                        val lastModified = if (lastModifiedVal in 1L..9999999999L) lastModifiedVal * 1000L else lastModifiedVal

                        val entry = ReadingEntry(
                            bookId = bookId,
                            lastContentId = lastContentId,
                            lastOpenedAt = lastOpenedAt,
                            favoritedAt = favoritedAt,
                            positionUpdatedAt = positionUpdatedAt,
                            isFavorite = isFavorite,
                            updatedAt = lastModified,
                            ckRecordId = ckRecordId
                        )
                        entriesToSave.add(entry)
                    }
                },
                onRecordDeleted = { ckRecordId ->
                    recordIdsToDelete.add(ckRecordId)
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

                    return@withContext it.second
                },
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
            val recordIDsToDelete = JSONArray()
            for (entry in entries) {
                coroutineContext.ensureActive()
                val recordId = entry.ckRecordId ?: entry.bookId.toString()
                if (!entry.isFavorite && entry.lastOpenedAt == null) {
                    recordIDsToDelete.put(recordId)
                } else {
                    val record = JSONObject().apply {
                        put("recordType", "ReadingEntry")
                        put("recordName", recordId)
                        put("zoneID", JSONObject().apply {
                            put("zoneName", "AnnotationsZone")
                            put("ownerRecordName", "_defaultOwner_")
                        })
                        put("fields", JSONObject().apply {
                            put("bookId", JSONObject().apply { put("value", entry.bookId) })
                            put(
                                "isFavorite",
                                JSONObject().apply { put("value", if (entry.isFavorite) 1 else 0) })
                            val lastModSec = if (entry.updatedAt > 10000000000L) entry.updatedAt / 1000L else entry.updatedAt
                            put("lastModified", JSONObject().apply { put("value", lastModSec) })
                            put("lastContentId", JSONObject().apply {
                                put("value", entry.lastContentId ?: JSONObject.NULL)
                            })
                            put("lastOpenedAt", JSONObject().apply {
                                put("value", entry.lastOpenedAt ?: JSONObject.NULL)
                            })
                            put("favoritedAt", JSONObject().apply {
                                put("value", entry.favoritedAt ?: JSONObject.NULL)
                            })
                            put("positionUpdatedAt", JSONObject().apply {
                                put("value", entry.positionUpdatedAt ?: JSONObject.NULL)
                            })
                        })
                    }
                    recordsToSave.put(record)
                }
            }

            val result =
                CloudKitCoreManager.shared.modifyRecords(context, recordsToSave, recordIDsToDelete)
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

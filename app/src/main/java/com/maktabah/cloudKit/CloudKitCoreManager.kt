package com.maktabah.cloudKit

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CloudKitCoreManager private constructor() {
    val cloudKitEnvironment: String
        get() = if (com.maktabah.BuildConfig.DEBUG) "development" else "production"

    val cloudKitBaseUrl: String
        get() = "https://api.apple-cloudkit.com/database/1/${com.maktabah.BuildConfig.CLOUDKIT_CONTAINER_ID}/$cloudKitEnvironment/private/records"

    val apiToken: String = com.maktabah.BuildConfig.CLOUDKIT_TOKEN

    companion object {
        val shared = CloudKitCoreManager()
    }

    suspend fun modifyRecords(
        context: Context,
        recordsToSave: JSONArray,
        recordIDsToDelete: JSONArray
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (recordsToSave.length() == 0 && recordIDsToDelete.length() == 0) {
            return@withContext Result.success(Unit)
        }

        val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val webAuthToken = prefs.getString("ckWebAuthToken", null) 
            ?: return@withContext Result.failure(Exception("No Web Auth Token"))

        try {
            val url = URL("$cloudKitBaseUrl/modify?ckAPIToken=$apiToken&ckWebAuthToken=${URLEncoder.encode(webAuthToken, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val operations = JSONArray()
            for (i in 0 until recordsToSave.length()) {
                val operation = JSONObject().apply {
                    put("operationType", "forceUpdate")
                    put("record", recordsToSave.getJSONObject(i))
                }
                operations.put(operation)
            }
            
            for (i in 0 until recordIDsToDelete.length()) {
                val operation = JSONObject().apply {
                    put("operationType", "forceDelete")
                    put("record", JSONObject().apply {
                        put("recordName", recordIDsToDelete.getString(i))
                        put("zoneID", JSONObject().apply {
                            put("zoneName", "AnnotationsZone")
                            put("ownerRecordName", "_defaultOwner_")
                        })
                    })
                }
                operations.put(operation)
            }

            val jsonBody = JSONObject().apply {
                put("operations", operations)
                put("zoneID", JSONObject().apply {
                    put("zoneName", "AnnotationsZone")
                    put("ownerRecordName", "_defaultOwner_")
                })
            }

            conn.outputStream.use { os ->
                val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                Result.success(Unit)
            } else {
                val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                val reason = try { JSONObject(errorStr).getString("reason") } catch(_: Exception) { errorStr.take(100) }
                Result.failure(Exception("Err: $reason"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun fetchUserInfo(context: Context): Result<JSONObject> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val webAuthToken = prefs.getString("ckWebAuthToken", null)
            ?: return@withContext Result.failure(Exception("No Web Auth Token"))

        val scopes = listOf("public", "private")
        var lastException: Exception? = null

        for (scope in scopes) {
            try {
                val baseUrlWithoutRecords = "https://api.apple-cloudkit.com/database/1/${com.maktabah.BuildConfig.CLOUDKIT_CONTAINER_ID}/$cloudKitEnvironment/$scope"
                val url = URL("$baseUrlWithoutRecords/users/caller?ckAPIToken=$apiToken&ckWebAuthToken=${URLEncoder.encode(webAuthToken, "UTF-8")}")
                android.util.Log.d("CloudKitCore", "fetchUserInfo trying URL: $url")
                
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"

                val responseCode = conn.responseCode
                android.util.Log.d("CloudKitCore", "fetchUserInfo Response Code ($scope): $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    android.util.Log.d("CloudKitCore", "fetchUserInfo Success ($scope): $responseStr")
                    return@withContext Result.success(JSONObject(responseStr))
                } else {
                    val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    android.util.Log.e("CloudKitCore", "fetchUserInfo Error ($scope): $errorStr")
                    val reason = try { JSONObject(errorStr).getString("reason") } catch(_: Exception) { errorStr.take(100) }
                    lastException = Exception("Err ($scope): $reason")
                }
            } catch (e: Exception) {
                android.util.Log.e("CloudKitCore", "fetchUserInfo Exception ($scope): ${e.message}", e)
                lastException = e
            }
        }
        
        Result.failure(lastException ?: Exception("Unknown error fetching user info"))
    }

    suspend fun fetchChanges(
        context: Context,
        zoneName: String,
        onRecordReceived: (JSONObject) -> Unit,
        onRecordDeleted: (String) -> Unit
    ): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val webAuthToken = prefs.getString("ckWebAuthToken", null) 
            ?: return@withContext Result.failure(Exception("No Web Auth Token"))

        try {
            var syncToken: String? = prefs.getString("ckSyncToken_$zoneName", null)
            var moreComing = true
            var totalCount = 0

            while (moreComing) {
                val url = URL("$cloudKitBaseUrl/changes?ckAPIToken=$apiToken&ckWebAuthToken=${URLEncoder.encode(webAuthToken, "UTF-8")}")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val jsonBody = JSONObject().apply {
                    put("zoneID", JSONObject().apply {
                        put("zoneName", zoneName)
                        put("ownerRecordName", "_defaultOwner_")
                    })
                    if (syncToken != null) {
                        put("syncToken", syncToken)
                    }
                }

                conn.outputStream.use { os ->
                    val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val responseObj = JSONObject(responseStr)

                    moreComing = responseObj.optBoolean("moreComing", false)
                    val newSyncToken = responseObj.optString("syncToken", "")
                    if (newSyncToken.isNotEmpty()) {
                        syncToken = newSyncToken
                    }

                    if (responseObj.has("records")) {
                        val records = responseObj.getJSONArray("records")
                        for (i in 0 until records.length()) {
                            val record = records.getJSONObject(i)
                            val ckRecordId = record.optString("recordName", "")
                            
                            if (ckRecordId.isNotEmpty() && record.optBoolean("deleted", false)) {
                                onRecordDeleted(ckRecordId)
                            } else if (record.has("fields")) {
                                onRecordReceived(record)
                                totalCount++
                            }
                        }
                    }
                } else {
                    val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val reason = try { JSONObject(errorStr).getString("reason") } catch(_: Exception) { errorStr.take(100) }
                    return@withContext Result.failure(Exception("Err: $reason"))
                }
            }
            
            syncToken?.let { token ->
                prefs.edit { putString("ckSyncToken_$zoneName", token).apply() }
            }
            
            Result.success(Pair(totalCount, "Success: Synced $totalCount records"))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }
}

package com.maktabah.cloudKit

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
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
            ?: return@withContext Result.failure(CloudKitError.NoWebAuthToken)

        try {
            val url = URL("$cloudKitBaseUrl/modify?ckAPIToken=$apiToken&ckWebAuthToken=${URLEncoder.encode(webAuthToken, "UTF-8")}")
            val allOperations = mutableListOf<JSONObject>()
            for (i in 0 until recordsToSave.length()) {
                val operation = JSONObject().apply {
                    put("operationType", "forceUpdate")
                    put("record", recordsToSave.getJSONObject(i))
                }
                allOperations.add(operation)
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
                allOperations.add(operation)
            }

            if (allOperations.isEmpty()) return@withContext Result.success(Unit)

            val chunkedOperations = allOperations.chunked(200)
            
            for (chunk in chunkedOperations) {
                val operations = JSONArray()
                chunk.forEach { operations.put(it) }

                val jsonBody = JSONObject().apply {
                    put("operations", operations)
                    put("zoneID", JSONObject().apply {
                        put("zoneName", "AnnotationsZone")
                        put("ownerRecordName", "_defaultOwner_")
                    })
                }

                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                try {
                    conn.outputStream.use { os ->
                        val input = jsonBody.toString().toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }

                    val responseCode = conn.responseCode
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        return@withContext Result.failure(classifyError(context, responseCode, errorStr))
                    } else {
                        try {
                            val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                            val responseObj = JSONObject(responseStr)
                            val recordsArr = responseObj.optJSONArray("records")
                            if (recordsArr != null) {
                                for (i in 0 until recordsArr.length()) {
                                    val rec = recordsArr.getJSONObject(i)
                                    if (rec.has("serverErrorCode")) {
                                        val err = rec.optString("serverErrorCode")
                                        val recName = rec.optString("recordName")
                                        android.util.Log.w("CloudKitCore", "modifyRecord $recName: $err")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("CloudKitCore", "Error parsing modifyRecords response", e)
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun fetchUserInfo(context: Context): Result<JSONObject> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val webAuthToken = prefs.getString("ckWebAuthToken", null)
            ?: return@withContext Result.failure(CloudKitError.NoWebAuthToken)

        val scopes = listOf("public", "private")
        var lastException: Exception? = null

        for (scope in scopes) {
            try {
                val baseUrlWithoutRecords = "https://api.apple-cloudkit.com/database/1/${com.maktabah.BuildConfig.CLOUDKIT_CONTAINER_ID}/$cloudKitEnvironment/$scope"
                val url = URL("$baseUrlWithoutRecords/users/caller?ckAPIToken=$apiToken&ckWebAuthToken=${URLEncoder.encode(webAuthToken, "UTF-8")}")
                android.util.Log.d("CloudKitCore", "fetchUserInfo trying URL for scope: $scope")
                
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"

                try {
                    val responseCode = conn.responseCode
                    android.util.Log.d("CloudKitCore", "fetchUserInfo Response Code ($scope): $responseCode")

                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                        android.util.Log.d("CloudKitCore", "fetchUserInfo Success ($scope)")
                        return@withContext Result.success(JSONObject(responseStr))
                    } else {
                        val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        android.util.Log.e("CloudKitCore", "fetchUserInfo Error ($scope)")
                        lastException = classifyError(context, responseCode, errorStr)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("CloudKitCore", "fetchUserInfo Exception ($scope): ${e.message}", e)
                lastException = e
            }
        }
        
        Result.failure(lastException ?: CloudKitError.ServerError("Unknown error fetching user info"))
    }

    suspend fun fetchChanges(
        context: Context,
        zoneName: String,
        onRecordReceived: (JSONObject) -> Unit,
        onRecordDeleted: (String) -> Unit,
        onReset: (() -> Unit)? = null
    ): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val webAuthToken = prefs.getString("ckWebAuthToken", null) 
            ?: return@withContext Result.failure(CloudKitError.NoWebAuthToken)

        try {
            var syncToken: String? = prefs.getString("ckSyncToken_$zoneName", null)
            var moreComing = true
            var totalCount = 0
            var changeTokenRetries = 0

            while (moreComing) {
                coroutineContext.ensureActive()
                val url = URL("$cloudKitBaseUrl/changes?ckAPIToken=$apiToken&ckWebAuthToken=${URLEncoder.encode(webAuthToken, "UTF-8")}")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                try {
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

                    val responseCode = conn.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
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
                                coroutineContext.ensureActive()
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
                        val error = classifyError(context, responseCode, errorStr, zoneName)
                        if (error is CloudKitError.ChangeTokenExpired && syncToken != null && changeTokenRetries < 1) {
                            changeTokenRetries++
                            totalCount = 0
                            onReset?.invoke()
                            prefs.edit { remove("ckSyncToken_$zoneName") }
                            android.util.Log.w("CloudKitCore", "Change token expired for $zoneName, retrying full sync")
                            syncToken = null
                            moreComing = true
                            continue
                        }
                        return@withContext Result.failure(error)
                    }
                } finally {
                    conn.disconnect()
                }
            }
            
            syncToken?.let { token ->
                prefs.edit { putString("ckSyncToken_$zoneName", token) }
            }
            
            Result.success(Pair(totalCount, "Success: Synced $totalCount records"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun classifyError(
        context: Context,
        httpCode: Int,
        errorBody: String,
        zoneName: String? = null
    ): CloudKitError {
        val json = try { JSONObject(errorBody) } catch (_: Exception) { null }
        val serverErrorCode = json?.optString("serverErrorCode") ?: ""
        val reason = json?.optString("reason")?.takeIf { it.isNotEmpty() }
            ?: serverErrorCode.takeIf { it.isNotEmpty() }
            ?: errorBody.take(100)

        val codeUpper = serverErrorCode.uppercase()
        val rawUpper = errorBody.uppercase()

        // HTTP 401 or Apple's AUTHENTICATION_FAILED / AUTHENTICATION_REQUIRED / NOT_AUTHENTICATED
        if (httpCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
            codeUpper == "AUTHENTICATION_FAILED" ||
            codeUpper == "AUTHENTICATION_REQUIRED" ||
            codeUpper == "NOT_AUTHENTICATED" ||
            rawUpper.contains("AUTHENTICATION_FAILED") ||
            rawUpper.contains("AUTHENTICATION_REQUIRED") ||
            rawUpper.contains("NOT_AUTHENTICATED")
        ) {
            val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
            prefs.edit { remove("ckWebAuthToken") }
            android.util.Log.w("CloudKitCore", "Auth expired, cleared ckWebAuthToken")
            return CloudKitError.AuthenticationExpired
        }

        // Delta sync token expired
        if ((codeUpper == "CHANGE_TOKEN_EXPIRED" ||
             rawUpper.contains("CHANGE_TOKEN_EXPIRED") ||
             rawUpper.contains("CHANGE TOKEN EXPIRED")) && zoneName != null
        ) {
            return CloudKitError.ChangeTokenExpired(zoneName)
        }

        return CloudKitError.ServerError(reason)
    }
}

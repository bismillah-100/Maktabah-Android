package com.maktabah.update

import com.maktabah.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class UpdateRepository(private val client: OkHttpClient) {

    suspend fun getLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_APP_REPO}/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Maktabah-Android")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    return@withContext GitHubRelease.fromJson(JSONObject(body))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}

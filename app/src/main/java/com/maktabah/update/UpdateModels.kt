package com.maktabah.update

import org.json.JSONObject

data class GitHubRelease(
    val tagName: String,
    val body: String,
    val assets: List<GitHubAsset>
) {
    companion object {
        fun fromJson(json: JSONObject): GitHubRelease {
            val assetsArray = json.getJSONArray("assets")
            val assetsList = mutableListOf<GitHubAsset>()
            for (i in 0 until assetsArray.length()) {
                assetsList.add(GitHubAsset.fromJson(assetsArray.getJSONObject(i)))
            }
            return GitHubRelease(
                tagName = json.getString("tag_name"),
                body = json.getString("body"),
                assets = assetsList
            )
        }
    }
}

data class GitHubAsset(
    val name: String,
    val browserDownloadUrl: String,
    val size: Long
) {
    companion object {
        fun fromJson(json: JSONObject): GitHubAsset {
            return GitHubAsset(
                name = json.getString("name"),
                browserDownloadUrl = json.getString("browser_download_url"),
                size = json.getLong("size")
            )
        }
    }
}

sealed class UpdateUIState {
    object Idle : UpdateUIState()
    object Checking : UpdateUIState()
    data class UpdateAvailable(val release: GitHubRelease) : UpdateUIState()
    data class Downloading(val progress: Int) : UpdateUIState()
    object Installing : UpdateUIState()
    data class Error(val message: String) : UpdateUIState()
}

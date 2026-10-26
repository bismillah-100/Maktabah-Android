package com.maktabah.models

import java.util.Locale

data class BookIndexEntry(
    val bkid: Int,
    val bk: String,
    val category: Int,
    val versionName: Long,
    val downloadURL: String,
    val fileSize: Long,
)

data class AuthIndexEntry(
    val authId: Int,
    val versionName: Long,
    val downloadURL: String,
)

data class BookUpdateMetadata(
    val bkid: Int,
    val cat: Int?,
    val bk: String,
    val archive: Int,
    val betaka: String?,
    val authno: Int?,
    val inf: String?,
    val tafseerNam: String?,
    val bVer: Int?,
    val link: String?,
    val pdfCs: Int?,
)

sealed interface UpdateStatus {
    data object Pending : UpdateStatus
    data object Checking : UpdateStatus
    data object New : UpdateStatus
    data object NeedsUpdate : UpdateStatus
    data object UpToDate : UpdateStatus
    data object Downloading : UpdateStatus
    data object Downloaded : UpdateStatus
    data object Processing : UpdateStatus
    data object Completed : UpdateStatus
    data class Failed(val message: String) : UpdateStatus
    data object Skipped : UpdateStatus
}

data class BookUpdateItem(
    val id: Int,
    val bookName: String,
    val category: Int,
    val categoryName: String,
    val existsInLibrary: Boolean,
    val currentVersion: Long?,
    val newVersion: Long,
    val fileSize: Long,
    val downloadURL: String,
    val isSelected: Boolean = !existsInLibrary || currentVersion == null || currentVersion != newVersion,
    val status: UpdateStatus = when {
        !existsInLibrary -> UpdateStatus.New
        currentVersion == null || currentVersion != newVersion -> UpdateStatus.NeedsUpdate
        else -> UpdateStatus.UpToDate
    },
) {
    val needsUpdate: Boolean
        get() = !existsInLibrary || currentVersion == null || currentVersion != newVersion

    val newBook: Boolean
        get() = !existsInLibrary

    val fileSizeFormatted: String
        get() = formatByteCount(fileSize)

    companion object {
        fun formatByteCount(bytes: Long): String {
            if (bytes <= 0) return "\u200E0 B"
            val kb = 1024.0
            val mb = kb * 1024.0
            val gb = mb * 1024.0
            val formatted = when {
                bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
                bytes >= mb -> String.format(Locale.US, "%.2f MB", bytes / mb)
                bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
                else -> "$bytes B"
            }
            return "\u200E$formatted"
        }
    }
}

enum class UpdateAction {
    INSERTED,
    UPDATED,
    SKIPPED,
}

data class BookUpdateResult(
    val bookId: Int,
    val catId: Int,
    val action: UpdateAction,
)

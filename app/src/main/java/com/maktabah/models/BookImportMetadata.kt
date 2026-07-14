package com.maktabah.models

data class AuthorRow(
    val id: Int,
    val name: String,
    val lng: String,
    val inf: String,
    val higriD: String,
    val oVer: Int,
)

data class ImportBookMetadata(
    val bkid: Int,
    val categoryId: Int,
    val bookName: String,
    val archiveId: Int,
    val betaka: String?,
    val inf: String?,
    val tafseerNam: String?,
    val bVer: Int,
    val isMultiLanguage: Boolean,
    val authno: Int?,
)

enum class ImportMode { NEW, REPLACE, CHANGE_ID }
enum class AuthorMode { EXISTING, NEW }
package com.maktabah.models

// Port dari iOS BookmarkModel.swift + DataModel.swift + ResultsHandler SyncModels

data class SyncFolder(
    val id: Long? = null,
    val name: String,
    val parent: Long? = null,
    val ckRecordId: String? = null,
    val lastModified: Long? = null,
    val parentCkRecordId: String? = null,
)

data class SyncResult(
    val id: Long? = null,
    val folderId: Long? = null,
    val name: String,
    val query: String,
    val archive: Int,
    val bkId: Int,
    val contentId: String,
    val ckRecordId: String? = null,
    val lastModified: Long? = null,
    val folderCkRecordId: String? = null,
)

data class FolderNode(
    val id: Long,
    val name: String,
    val children: MutableList<FolderNode> = mutableListOf(),
)

data class ResultNode(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val items: List<SavedResultsItem>,
)

data class SavedResultsItem(
    val archive: String,
    val tableName: String,
    val query: String,
    val bookId: Int,
    val bookTitle: String,
)

data class GroupedResult(
    val archive: Int,
    val bkId: Int,
    val contentIds: MutableList<String> = mutableListOf(),
)

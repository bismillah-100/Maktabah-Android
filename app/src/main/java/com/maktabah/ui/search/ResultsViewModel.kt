package com.maktabah.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.database.ResultsHandler
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.FolderNode
import com.maktabah.models.GroupedResult
import com.maktabah.models.ResultNode
import com.maktabah.models.SavedResultsItem
import com.maktabah.models.SearchResult
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Port dari iOS ResultsViewModel.swift.
 * Manages saved search results state (folders + results tree).
 */
class ResultsViewModel : ViewModel() {

    private var resultsHandler: ResultsHandler? = null
    private var dataManager: LibraryDataManager? = null
    private var appContext: android.content.Context? = null

    private val _folderRoots = MutableStateFlow<List<FolderNode>>(emptyList())
    val folderRoots: StateFlow<List<FolderNode>> = _folderRoots.asStateFlow()

    private val _folderResults = MutableStateFlow<Map<Long?, List<ResultNode>>>(emptyMap())
    val folderResults: StateFlow<Map<Long?, List<ResultNode>>> = _folderResults.asStateFlow()

    // In-memory caches
    private val folderById = mutableMapOf<Long, FolderNode>()
    private val parentById = mutableMapOf<Long, Long?>()
    private val resultById = mutableMapOf<Long, ResultNode>()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _folderStack = MutableStateFlow<List<FolderNode>>(emptyList())
    val folderStack: StateFlow<List<FolderNode>> = _folderStack.asStateFlow()

    fun initialize(context: android.content.Context, dataManager: LibraryDataManager) {
        if (resultsHandler != null) return
        this.appContext = context.applicationContext
        this.dataManager = dataManager
        val dbFile = File(context.filesDir, "SearchResults.sqlite")
        resultsHandler = ResultsHandler(dbFile)
        viewModelScope.launch {
            getFolders()
            loadAllResults()
            CloudKitResultSyncHelper.syncEvent.collect {
                if (com.maktabah.BuildConfig.DEBUG) {
                    appContext?.let { ctx ->
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "CloudKit Results Synced (Pull)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                reloadFromSync()
            }
        }
    }

    fun getHandler(): ResultsHandler? = resultsHandler

    fun pushFolder(folder: FolderNode) {
        val current = _folderStack.value.toMutableList()
        current.add(folder)
        _folderStack.value = current
    }

    fun popFolder(): Boolean {
        val current = _folderStack.value.toMutableList()
        if (current.isNotEmpty()) {
            current.removeAt(current.lastIndex)
            _folderStack.value = current
            return true
        }
        return false
    }

    fun clearFolderStack() {
        _folderStack.value = emptyList()
    }

    // region Folders

    suspend fun getFolders() {
        val roots = withContext(Dispatchers.IO) {
            val tree = resultsHandler?.fetchFolderTree() ?: emptyList()
            sortTree(tree)
            tree
        }
        _folderRoots.value = roots
        rebuildFolderIndex()
    }

    suspend fun loadAllResults() {
        _isLoading.value = true
        val currentRoots = _folderRoots.value
        val dm = dataManager
        val handler = resultsHandler

        if (handler == null || dm == null) {
            _isLoading.value = false
            return
        }

        val allResults = withContext(Dispatchers.IO) {
            val resultsMap = mutableMapOf<Long?, List<ResultNode>>()

            fun loadForFolder(folderId: Long?) {
                val rawResults = handler.fetchResultsRaw(folderId)
                if (rawResults.isNotEmpty()) {
                    val grouped = rawResults.groupBy { it.name }
                    val nodes = grouped.map { (name, rows) ->
                        val firstRow = rows.first()
                        val items = rows.flatMap { row ->
                            val contentIds = row.contentId.split(",")
                            contentIds.mapNotNull { cidStr ->
                                val cid = cidStr.trim().toIntOrNull() ?: return@mapNotNull null
                                val book = dm.booksById[row.bkId] ?: return@mapNotNull null
                                SavedResultsItem(
                                    archive = row.archive.toString(),
                                    tableName = row.bkId.toString(),
                                    query = row.query,
                                    bookId = cid,
                                    bookTitle = book.name,
                                )
                            }
                        }
                        ResultNode(
                            id = firstRow.id,
                            parentId = firstRow.folderId,
                            name = name,
                            items = items,
                        )
                    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

                    if (nodes.isNotEmpty()) {
                        resultsMap[folderId] = nodes
                    }
                }
            }

            loadForFolder(null) // root results

            fun loadRecursive(folder: FolderNode) {
                loadForFolder(folder.id)
                for (child in folder.children) loadRecursive(child)
            }
            for (root in currentRoots) loadRecursive(root)

            resultsMap
        }

        _folderResults.value = allResults
        rebuildResultIndex()
        _isLoading.value = false
    }

    fun addRootFolder(name: String): Boolean {
        val handler = resultsHandler ?: return false
        return try {
            val id = handler.insertRootFolder(name) ?: return false
            val node = FolderNode(id, name)
            val current = _folderRoots.value.toMutableList()
            current.add(node)
            current.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            _folderRoots.value = current
            folderById[id] = node
            parentById[id] = null
            uploadFolderSync(id)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun addSubFolder(parentId: Long, name: String): Boolean {
        val handler = resultsHandler ?: return false
        val parentNode = folderById[parentId] ?: return false
        return try {
            val id = handler.insertSubFolder(parentId, name) ?: return false
            val node = FolderNode(id, name)
            parentNode.children.add(node)
            parentNode.children.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            _folderRoots.value = _folderRoots.value.toList() // trigger recompose
            folderById[id] = node
            parentById[id] = parentId
            uploadFolderSync(id)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun hasDescendantWithId(node: FolderNode, id: Long): Boolean {
        if (node.id == id) return true
        return node.children.any { hasDescendantWithId(it, id) }
    }

    private fun replaceFolderNodeInTree(nodes: List<FolderNode>, id: Long, newName: String): List<FolderNode> {
        return nodes.map { node ->
            if (node.id == id) {
                node.copy(name = newName)
            } else if (hasDescendantWithId(node, id)) {
                val newChildren = replaceFolderNodeInTree(node.children, id, newName).toMutableList()
                node.copy(children = newChildren)
            } else {
                node
            }
        }
    }

    fun updateFolderName(folderId: Long, newName: String): Boolean {
        val handler = resultsHandler ?: return false
        return try {
            handler.updateFolderName(folderId, newName)

            _folderRoots.value = replaceFolderNodeInTree(_folderRoots.value, folderId, newName)
            rebuildFolderIndex()

            // Re-sort siblings
            val parentId = parentById[folderId]
            if (parentId != null) {
                folderById[parentId]?.children?.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                _folderRoots.value = _folderRoots.value.toList()
            } else {
                val roots = _folderRoots.value.toMutableList()
                roots.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                _folderRoots.value = roots
            }

            _folderStack.value = _folderStack.value.map {
                folderById[it.id] ?: it
            }

            uploadFolderSync(folderId)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFolder(node: FolderNode) {
        val handler = resultsHandler ?: return
        val allIds = getAllDescendantIds(node)

        handler.deleteFolder(node.id)

        for (id in allIds) {
            _folderResults.value = _folderResults.value - id
            folderById.remove(id)
            parentById.remove(id)
        }

        // Remove resultById entries
        resultById.entries.removeAll { (_, rNode) ->
            rNode.parentId != null && allIds.contains(rNode.parentId)
        }

        // Remove node from tree
        removeNodeFromTree(node)
        _folderRoots.value = _folderRoots.value.toList()
    }

    fun moveFolder(draggedNode: FolderNode, newParentId: Long?): Boolean {
        val handler = resultsHandler ?: return false
        val newParent = if (newParentId != null) folderById[newParentId] else null

        // Prevent cycles
        if (newParent != null && isDescendant(newParent, of = draggedNode)) return false

        return try {
            handler.updateParent(draggedNode.id, newParentId)
            removeNodeFromTree(draggedNode)

            if (newParent != null) {
                newParent.children.add(draggedNode)
                newParent.children.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                parentById[draggedNode.id] = newParentId
            } else {
                val roots = _folderRoots.value.toMutableList()
                roots.add(draggedNode)
                roots.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                _folderRoots.value = roots
                parentById[draggedNode.id] = null
            }
            _folderRoots.value = _folderRoots.value.toList()
            uploadFolderSync(draggedNode.id)
            true
        } catch (e: Exception) {
            false
        }
    }

    // endregion

    // region Results

    fun deleteResult(parentFolderId: Long?, name: String) {
        val handler = resultsHandler ?: return
        handler.deleteResult(parentFolderId, name)

        val current = _folderResults.value.toMutableMap()
        val list = current[parentFolderId]?.toMutableList()
        list?.removeAll { it.name == name }
        if (list.isNullOrEmpty()) {
            current.remove(parentFolderId)
        } else {
            current[parentFolderId] = list
        }
        _folderResults.value = current

        resultById.entries.removeAll { (_, r) -> r.parentId == parentFolderId && r.name == name }
    }

    fun updateResultName(resultId: Long, newName: String): Boolean {
        val handler = resultsHandler ?: return false
        val node = resultById[resultId] ?: return false
        val folderId = node.parentId

        return try {
            handler.updateResultQueryName(folderId, node.name, newName)

            val newNode = node.copy(name = newName)
            resultById[resultId] = newNode

            val current = _folderResults.value.toMutableMap()
            val list = current[folderId]?.toMutableList() ?: return true

            // Replace old node with new node
            val index = list.indexOfFirst { it.id == resultId }
            if (index != -1) {
                list[index] = newNode
            }

            list.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            current[folderId] = list
            _folderResults.value = current

            uploadResultSyncByName(folderId, newName)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun moveResult(resultId: Long, newFolderId: Long?): Boolean {
        val handler = resultsHandler ?: return false
        val node = resultById[resultId] ?: return false
        val oldFolderId = node.parentId

        return try {
            handler.updateResultParent(newFolderId, oldFolderId, node.name)

            val current = _folderResults.value.toMutableMap()

            // Remove from old
            val oldList = current[oldFolderId]?.toMutableList()
            oldList?.removeAll { it.id == resultId }
            if (oldList.isNullOrEmpty()) current.remove(oldFolderId)
            else current[oldFolderId] = oldList

            // Add to new
            val newNode = node.copy(parentId = newFolderId)
            resultById[resultId] = newNode

            val newList = (current[newFolderId] ?: emptyList()).toMutableList()
            newList.add(newNode)
            newList.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            current[newFolderId] = newList

            _folderResults.value = current
            uploadResultSyncByName(newFolderId, newNode.name)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun saveSearchResults(results: List<SearchResult>, query: String, folderId: Long?, name: String, dataManager: LibraryDataManager): Boolean {
        val handler = resultsHandler ?: return false
        val groupedResults = mutableMapOf<String, GroupedResult>()

        for (result in results) {
            val book = dataManager.booksById[result.bookId] ?: continue
            val archive = book.archive
            val bkId = result.bookId
            val bookId = result.contentId.toString()
            val key = "${archive}_${bkId}"

            val group = groupedResults.getOrPut(key) { GroupedResult(archive, bkId) }
            if (!group.contentIds.contains(bookId)) {
                group.contentIds.add(bookId)
            }
        }

        var success = true
        for ((_, group) in groupedResults) {
            val commaSeparated = group.contentIds.joinToString(",")
            try {
                handler.insertResult(group.archive, group.bkId, commaSeparated, folderId, query, name)
            } catch (e: Exception) {
                e.printStackTrace()
                success = false
            }
        }

        // Reload results for the folder
        viewModelScope.launch {
            loadAllResults()
        }
        return success
    }

    // endregion

    // region Search in memory

    fun searchFoldersInMemory(query: String): List<FolderNode> {
        if (query.isBlank()) return emptyList()
        val cleanQuery = query.normalizeArabic()
        return folderById.values.filter {
            it.name.normalizeArabic().contains(cleanQuery, ignoreCase = true)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun searchResultsInMemory(query: String): List<ResultNode> {
        if (query.isBlank()) return emptyList()
        val cleanQuery = query.normalizeArabic()
        return resultById.values.filter {
            it.name.normalizeArabic().contains(cleanQuery, ignoreCase = true)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun folderPath(folderId: Long?): String {
        var id = folderId ?: return "Root"
        val parts = mutableListOf<String>()
        while (true) {
            val node = folderById[id] ?: break
            parts.add(0, node.name)
            val parent = parentById[id]
            if (parent != null) id = parent else break
        }
        return parts.joinToString(" / ")
    }

    fun findFolder(id: Long): FolderNode? = folderById[id]

    val allFolders: List<FolderNode>
        get() = folderById.values.toList()

    // endregion

    // region Sync reload

    fun reloadFromSync() {
        viewModelScope.launch {
            getFolders()
            loadAllResults()
        }
    }

    // endregion

    // region Private helpers

    private fun sortTree(nodes: List<FolderNode>) {
        for (node in nodes) {
            node.children.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            sortTree(node.children)
        }
    }

    private fun rebuildFolderIndex() {
        folderById.clear()
        parentById.clear()
        fun walk(node: FolderNode, parent: Long?) {
            folderById[node.id] = node
            parentById[node.id] = parent
            for (c in node.children) walk(c, node.id)
        }
        for (root in _folderRoots.value) walk(root, null)
    }

    private fun rebuildResultIndex() {
        resultById.clear()
        for ((_, results) in _folderResults.value) {
            for (r in results) {
                resultById[r.id] = r
            }
        }
    }

    private fun removeNodeFromTree(node: FolderNode) {
        val roots = _folderRoots.value.toMutableList()
        if (roots.removeAll { it.id == node.id }) {
            _folderRoots.value = roots
            return
        }

        fun remove(parent: FolderNode): Boolean {
            if (parent.children.removeAll { it.id == node.id }) return true
            return parent.children.any { remove(it) }
        }
        for (root in roots) if (remove(root)) break
        _folderRoots.value = roots
    }

    private fun getAllDescendantIds(node: FolderNode): List<Long> {
        val ids = mutableListOf(node.id)
        for (child in node.children) ids.addAll(getAllDescendantIds(child))
        return ids
    }

    private fun isDescendant(node: FolderNode, of: FolderNode): Boolean {
        if (node.id == of.id) return true
        return of.children.any { isDescendant(node, it) }
    }

    private fun uploadFolderSync(folderId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val syncData = resultsHandler?.fetchFolderSyncData(folderId) ?: return@launch
            appContext?.let { CloudKitResultSyncHelper.uploadFolders(it, listOf(syncData)) }
        }
    }

    private fun uploadResultSyncByName(folderId: Long?, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val syncData = resultsHandler?.fetchResultsSyncDataByFolder(folderId, name) ?: return@launch
            if (syncData.isNotEmpty()) {
                appContext?.let { CloudKitResultSyncHelper.uploadResults(it, syncData) }
            }
        }
    }
}

/**
 * Static helper for CloudKit result sync operations.
 * These are called from ResultsHandler operations and scheduled for background execution.
 */
object CloudKitResultSyncHelper {
    val syncEvent = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    fun uploadFolders(context: android.content.Context, folders: List<com.maktabah.models.SyncFolder>) {
        triggerSync(context)
    }

    fun uploadResults(context: android.content.Context, results: List<com.maktabah.models.SyncResult>) {
        triggerSync(context)
    }

    fun delete(context: android.content.Context, ckRecordIds: List<String>) {
        triggerSync(context)
    }

    private fun triggerSync(context: android.content.Context) {
        scope.launch {
            val result = com.maktabah.cloudKit.CloudKitSyncManager().syncResults(context)
            if (com.maktabah.BuildConfig.DEBUG) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "CloudKit Upload: $result", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

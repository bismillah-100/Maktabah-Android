package com.maktabah.ui.history

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.database.HistoryDatabaseManager
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.ReadingEntry
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private val _refreshFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val refreshFlow: SharedFlow<Unit> = _refreshFlow.asSharedFlow()

        fun notifyRefresh() {
            _refreshFlow.tryEmit(Unit)
        }
    }

    private val dbManager = HistoryDatabaseManager(
        File(app.filesDir, "History.sqlite")
    )

    private val _entriesByBookId = MutableStateFlow<Map<Int, ReadingEntry>>(emptyMap())
    val entriesByBookId: StateFlow<Map<Int, ReadingEntry>> = _entriesByBookId.asStateFlow()

    private val _historyOrder = MutableStateFlow<List<Int>>(emptyList())

    private val maxHistoryCount = 50

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _historyExpanded = MutableStateFlow(true)
    val historyExpanded: StateFlow<Boolean> = _historyExpanded.asStateFlow()

    private val _favoritesExpanded = MutableStateFlow(true)
    val favoritesExpanded: StateFlow<Boolean> = _favoritesExpanded.asStateFlow()

    private val favoriteOrderFlow: Flow<List<Int>> = _entriesByBookId
        .map { entries ->
            entries.values
                .asSequence()
                .filter { it.isFavorite }
                .sortedByDescending { it.favoritedAt ?: 0L }
                .map { it.bookId }
                .toList()
        }
        .distinctUntilChanged()

    private var filteredHistoryFlow: StateFlow<List<Int>>? = null
    private var filteredFavoritesFlow: StateFlow<List<Int>>? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val historyPrefs = app.getSharedPreferences("HistoryPrefs", Context.MODE_PRIVATE)
            _historyExpanded.value = historyPrefs.getBoolean("historyExpanded", true)
            _favoritesExpanded.value = historyPrefs.getBoolean("favoritesExpanded", true)

            // Migrasi one-shot dari JSON lama ke SQLite
            val jsonFile = File(app.filesDir, "user_data.json")
            val prefs = app.getSharedPreferences("history_db_prefs", Context.MODE_PRIVATE)
            dbManager.migrateFromJsonIfNeeded(jsonFile, prefs)

            // Load dari SQLite
            loadFromDatabase()
        }
    }

    fun toggleHistoryExpanded() {
        val next = !_historyExpanded.value
        _historyExpanded.value = next
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>()
                .getSharedPreferences("HistoryPrefs", Context.MODE_PRIVATE)
                .edit { putBoolean("historyExpanded", next) }
        }
    }

    fun toggleFavoritesExpanded() {
        val next = !_favoritesExpanded.value
        _favoritesExpanded.value = next
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>()
                .getSharedPreferences("HistoryPrefs", Context.MODE_PRIVATE)
                .edit { putBoolean("favoritesExpanded", next) }
        }
    }

    private fun loadFromDatabase() {
        val (entries, order) = dbManager.loadFromDatabase()
        _entriesByBookId.value = entries.associateBy { it.bookId }
        _historyOrder.value = order
    }

    fun getFavoriteBookIds(): List<Int> {
        return _entriesByBookId.value.values
            .filter { it.isFavorite }
            .sortedByDescending { it.favoritedAt ?: 0L }
            .map { it.bookId }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun getFilteredHistory(
        dataManager: LibraryDataManager,
        catalogRevision: StateFlow<Long>? = null,
    ): StateFlow<List<Int>> {
        return filteredHistoryFlow ?: combine(
            _historyOrder,
            _searchQuery.debounce { query -> if (query.isEmpty()) 0L else 500L },
            catalogRevision ?: MutableStateFlow(0L),
        ) { order, query, _ ->
            val cleanQuery = query.normalizeArabic()
            if (cleanQuery.isBlank()) order
            else order.filter { dataManager.bookContainsNormalizedQuery(it, cleanQuery) }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, _historyOrder.value)
            .also { filteredHistoryFlow = it }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun getFilteredFavorites(
        dataManager: LibraryDataManager,
        catalogRevision: StateFlow<Long>? = null,
    ): StateFlow<List<Int>> {
        return filteredFavoritesFlow ?: combine(
            favoriteOrderFlow,
            _searchQuery.debounce { query -> if (query.isEmpty()) 0L else 500L },
            catalogRevision ?: MutableStateFlow(0L),
        ) { favorites, query, _ ->
            val cleanQuery = query.normalizeArabic()
            if (cleanQuery.isBlank()) favorites
            else favorites.filter { dataManager.bookContainsNormalizedQuery(it, cleanQuery) }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, getFavoriteBookIds())
            .also { filteredFavoritesFlow = it }
    }

    fun addBookToHistory(bookId: Int): List<ReadingEntry> {
        return recordBookOpen(bookId = bookId, contentId = null)
    }

    fun updateLastContentId(contentId: Int, bookId: Int) {
        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId]
        if (entry != null) {
            val now = System.currentTimeMillis()
            val newEntry = entry.copy(
                lastContentId = contentId,
                positionUpdatedAt = now,
                updatedAt = now
            )
            entries[bookId] = newEntry
            _entriesByBookId.value = entries
            viewModelScope.launch(Dispatchers.IO) { dbManager.upsertEntry(newEntry) }
        } else {
            recordBookOpen(bookId = bookId, contentId = contentId)
        }
    }

    private fun recordBookOpen(bookId: Int, contentId: Int?): List<ReadingEntry> {
        val now = System.currentTimeMillis()
        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId] ?: ReadingEntry(
            bookId = bookId,
            ckRecordId = bookId.toString()
        )
        val newEntry = if (contentId != null) {
            entry.copy(
                lastContentId = contentId,
                lastOpenedAt = now,
                positionUpdatedAt = now,
                updatedAt = now
            )
        } else {
            entry.copy(
                lastOpenedAt = now,
                updatedAt = now
            )
        }
        entries[bookId] = newEntry

        val affectedEntries = mutableListOf(newEntry)
        val upserted = mutableListOf(newEntry)
        val deletedIds = mutableListOf<Int>()

        val order = _historyOrder.value.toMutableList()
        order.remove(bookId)
        order.add(0, bookId)

        if (order.size > maxHistoryCount) {
            val overflow = order.subList(maxHistoryCount, order.size)
            val toRemove = overflow.toList()
            overflow.clear()
            toRemove.forEach { idToRemove ->
                val oldEntry = entries[idToRemove]
                if (oldEntry != null) {
                    val updatedOldEntry = oldEntry.copy(
                        lastOpenedAt = null,
                        lastContentId = null,
                        updatedAt = now
                    )
                    if (!updatedOldEntry.isFavorite) {
                        entries.remove(idToRemove)
                        deletedIds.add(idToRemove)
                    } else {
                        entries[idToRemove] = updatedOldEntry
                        upserted.add(updatedOldEntry)
                    }
                    affectedEntries.add(updatedOldEntry)
                }
            }
        }

        _entriesByBookId.value = entries
        _historyOrder.value = order

        viewModelScope.launch(Dispatchers.IO) {
            dbManager.saveHistoryBatch(
                upsertEntries = upserted,
                deleteBookIds = deletedIds,
                newOrder = order,
            )
        }

        return affectedEntries
    }

    fun toggleFavorite(bookId: Int): ReadingEntry? {
        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId] ?: ReadingEntry(bookId = bookId, ckRecordId = bookId.toString())
        val isFav = !entry.isFavorite
        val newEntry = entry.copy(
            isFavorite = isFav,
            favoritedAt = if (isFav) System.currentTimeMillis() else entry.favoritedAt,
            updatedAt = System.currentTimeMillis()
        )
        if (!newEntry.isFavorite && newEntry.lastOpenedAt == null) {
            entries.remove(bookId)
            viewModelScope.launch(Dispatchers.IO) { dbManager.deleteEntry(bookId) }
        } else {
            entries[bookId] = newEntry
            viewModelScope.launch(Dispatchers.IO) { dbManager.upsertEntry(newEntry) }
        }
        _entriesByBookId.value = entries
        return newEntry
    }

    fun removeFromHistory(bookId: Int): ReadingEntry? {
        val order = _historyOrder.value.toMutableList()
        order.remove(bookId)
        _historyOrder.value = order

        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId] ?: return null
        val newEntry = entry.copy(
            lastOpenedAt = null,
            lastContentId = null,
            updatedAt = System.currentTimeMillis()
        )
        if (!newEntry.isFavorite) {
            entries.remove(bookId)
            viewModelScope.launch(Dispatchers.IO) {
                dbManager.saveHistoryBatch(
                    upsertEntries = emptyList(),
                    deleteBookIds = listOf(bookId),
                    newOrder = order,
                )
            }
        } else {
            entries[bookId] = newEntry
            viewModelScope.launch(Dispatchers.IO) {
                dbManager.saveHistoryBatch(
                    upsertEntries = listOf(newEntry),
                    deleteBookIds = emptyList(),
                    newOrder = order,
                )
            }
        }
        _entriesByBookId.value = entries
        return newEntry
    }

    fun applyCloudKitChanges(entriesToSave: List<ReadingEntry>, recordIdsToDelete: List<String>) {
        val entries = _entriesByBookId.value.toMutableMap()
        var didChange = false
        val deletedBookIds = mutableListOf<Int>()

        val upserted = mutableListOf<ReadingEntry>()
        // Process Deletions
        if (recordIdsToDelete.isNotEmpty()) {
            val recordIdsSet = recordIdsToDelete.toSet()
            val toDelete = entries.values.filter { entry ->
                val ckId = entry.ckRecordId
                (ckId != null && recordIdsSet.contains(ckId)) || (ckId == null && recordIdsSet.contains(entry.bookId.toString()))
            }
            for (e in toDelete) {
                if (e.isFavorite) {
                    val kept = e.copy(lastOpenedAt = null, lastContentId = null, updatedAt = System.currentTimeMillis())
                    entries[e.bookId] = kept
                    upserted.add(kept)
                } else {
                    entries.remove(e.bookId)
                    deletedBookIds.add(e.bookId)
                }
                didChange = true
            }
        }

        // Updates/Insertions
        for (incoming in entriesToSave) {
            val existing = entries[incoming.bookId]
            if (existing == null || incoming.updatedAt > existing.updatedAt) {
                entries[incoming.bookId] = incoming
                upserted.add(incoming)
                didChange = true
            }
        }

        // Sync history order berdasarkan lastOpenedAt
        val validHistoryEntries = entries.values.filter { it.lastOpenedAt != null }
        val sortedIds = validHistoryEntries
            .sortedByDescending { it.lastOpenedAt ?: 0L }
            .map { it.bookId }

        val newOrder = if (sortedIds.size > maxHistoryCount) {
            val toKeep = sortedIds.subList(0, maxHistoryCount)
            val toRemove = sortedIds.subList(maxHistoryCount, sortedIds.size)
            toRemove.forEach { idToRemove ->
                val oldEntry = entries[idToRemove]
                if (oldEntry != null) {
                    val updatedOldEntry = oldEntry.copy(
                        lastOpenedAt = null,
                        lastContentId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    
                    // C5: Pruned History Entries Re-Inserted Due to Stale upserted List
                    val iterator = upserted.iterator()
                    while (iterator.hasNext()) {
                        if (iterator.next().bookId == idToRemove) iterator.remove()
                    }

                    if (!updatedOldEntry.isFavorite) {
                        entries.remove(idToRemove)
                        deletedBookIds.add(idToRemove)
                    } else {
                        entries[idToRemove] = updatedOldEntry
                        upserted.add(updatedOldEntry)
                    }
                    didChange = true
                }
            }
            toKeep
        } else {
            sortedIds
        }

        val currentOrder = _historyOrder.value
        if (newOrder != currentOrder || didChange) {
            val finalEntries = entries.toMap()
            _entriesByBookId.value = finalEntries
            _historyOrder.value = newOrder

            val allDeletedIds = (deletedBookIds + (currentOrder - newOrder.toSet())
                .filter { finalEntries[it]?.let { e -> !e.isFavorite } ?: true }).distinct()

            viewModelScope.launch(Dispatchers.IO) {
                dbManager.applyCloudKitBatch(upserted, allDeletedIds, newOrder)
            }
            notifyRefresh()
        }
    }

    fun clearAll() {
        _entriesByBookId.value = emptyMap()
        _historyOrder.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbManager.clearAllData()
            } catch (e: Exception) {
                android.util.Log.e("HistoryViewModel", "Failed to clear history database", e)
            }
        }
    }
}

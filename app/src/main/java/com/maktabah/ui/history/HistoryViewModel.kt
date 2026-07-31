package com.maktabah.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.maktabah.database.HistoryDatabaseManager
import com.maktabah.models.ReadingEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import com.maktabah.manager.LibraryDataManager
import com.maktabah.utils.normalizeArabic
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
    val historyOrder: StateFlow<List<Int>> = _historyOrder.asStateFlow()

    private val maxHistoryCount = 50

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var filteredHistoryFlow: StateFlow<List<Int>>? = null
    private var filteredFavoritesFlow: StateFlow<List<Int>>? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            // Migrasi one-shot dari JSON lama ke SQLite
            val jsonFile = File(app.filesDir, "user_data.json")
            val prefs = app.getSharedPreferences("history_db_prefs", android.content.Context.MODE_PRIVATE)
            dbManager.migrateFromJsonIfNeeded(jsonFile, prefs)

            // Load dari SQLite
            loadFromDatabase()
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
        _searchQuery.value = query.normalizeArabic()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun getFilteredHistory(dataManager: LibraryDataManager): StateFlow<List<Int>> {
        return filteredHistoryFlow ?: combine(
            _historyOrder,
            _searchQuery.debounce { query -> if (query.isEmpty()) 0L else 500L }
        ) { order, query ->
            val cleanQuery = query.normalizeArabic()
            if (cleanQuery.isBlank()) order
            else order.filter { dataManager.bookContainsQuery(it, cleanQuery) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), _historyOrder.value).also {
            filteredHistoryFlow = it
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun getFilteredFavorites(dataManager: LibraryDataManager): StateFlow<List<Int>> {
        return filteredFavoritesFlow ?: combine(
            _entriesByBookId,
            _searchQuery.debounce { query -> if (query.isEmpty()) 0L else 500L }
        ) { entries, query ->
            val cleanQuery = query.normalizeArabic()
            val favorites = entries.values
                .filter { it.isFavorite }
                .sortedByDescending { it.favoritedAt ?: 0L }
                .map { it.bookId }
            if (cleanQuery.isBlank()) favorites
            else favorites.filter { dataManager.bookContainsQuery(it, cleanQuery) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), getFavoriteBookIds()).also {
            filteredFavoritesFlow = it
        }
    }

    fun addBookToHistory(bookId: Int): List<ReadingEntry> {
        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId] ?: ReadingEntry(
            bookId = bookId,
            ckRecordId = bookId.toString()
        )
        val newEntry = entry.copy(
            lastOpenedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        entries[bookId] = newEntry

        val affectedEntries = mutableListOf<ReadingEntry>()
        affectedEntries.add(newEntry)

        val order = _historyOrder.value.toMutableList()
        order.remove(bookId)
        order.add(0, bookId)

        if (order.size > maxHistoryCount) {
            val toRemove = order.subList(maxHistoryCount, order.size).toList()
            order.removeAll(toRemove)
            toRemove.forEach { idToRemove ->
                val oldEntry = entries[idToRemove]
                if (oldEntry != null) {
                    val updatedOldEntry = oldEntry.copy(
                        lastOpenedAt = null,
                        lastContentId = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (!updatedOldEntry.isFavorite) {
                        entries.remove(idToRemove)
                        viewModelScope.launch(Dispatchers.IO) { dbManager.deleteEntry(idToRemove) }
                    } else {
                        entries[idToRemove] = updatedOldEntry
                        viewModelScope.launch(Dispatchers.IO) { dbManager.upsertEntry(updatedOldEntry) }
                    }
                    affectedEntries.add(updatedOldEntry)
                }
            }
        }

        _entriesByBookId.value = entries
        _historyOrder.value = order

        viewModelScope.launch(Dispatchers.IO) {
            dbManager.upsertEntry(newEntry)
            dbManager.saveHistoryOrder(order)
        }

        return affectedEntries
    }

    fun updateLastContentId(contentId: Int, bookId: Int) {
        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId]
        if (entry != null) {
            val newEntry = entry.copy(
                lastContentId = contentId,
                positionUpdatedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            entries[bookId] = newEntry
            _entriesByBookId.value = entries
            viewModelScope.launch(Dispatchers.IO) { dbManager.upsertEntry(newEntry) }
        } else {
            addBookToHistory(bookId)
            updateLastContentId(contentId, bookId)
        }
    }

    fun toggleFavorite(bookId: Int): ReadingEntry {
        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId] ?: ReadingEntry(
            bookId = bookId,
            ckRecordId = bookId.toString()
        )
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
        val entry = entries[bookId]
        if (entry != null) {
            val newEntry = entry.copy(
                lastOpenedAt = null,
                lastContentId = null,
                updatedAt = System.currentTimeMillis()
            )
            if (!newEntry.isFavorite) {
                entries.remove(bookId)
                viewModelScope.launch(Dispatchers.IO) {
                    dbManager.deleteEntry(bookId)
                    dbManager.saveHistoryOrder(order)
                }
            } else {
                entries[bookId] = newEntry
                viewModelScope.launch(Dispatchers.IO) {
                    dbManager.upsertEntry(newEntry)
                    dbManager.saveHistoryOrder(order)
                }
            }
            _entriesByBookId.value = entries
            return newEntry
        }
        viewModelScope.launch(Dispatchers.IO) { dbManager.saveHistoryOrder(order) }
        return null
    }

    fun applyCloudKitChanges(entriesToSave: List<ReadingEntry>, recordIdsToDelete: List<String>) {
        val entries = _entriesByBookId.value.toMutableMap()
        var didChange = false

        // Process Deletions
        if (recordIdsToDelete.isNotEmpty()) {
            val recordIdsSet = recordIdsToDelete.toSet()
            val removed = entries.values.removeAll { entry ->
                val ckId = entry.ckRecordId ?: entry.bookId.toString()
                recordIdsSet.contains(ckId)
            }
            if (removed) didChange = true
        }

        // Updates/Insertions
        val upserted = mutableListOf<ReadingEntry>()
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
                    if (!updatedOldEntry.isFavorite) entries.remove(idToRemove)
                    else entries[idToRemove] = updatedOldEntry
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
            val finalOrder = newOrder
            _entriesByBookId.value = finalEntries
            _historyOrder.value = finalOrder

            // Hitung deleted IDs untuk batch DB write
            val deletedBookIds = recordIdsToDelete.mapNotNull { ckId ->
                _entriesByBookId.value.values.find { it.ckRecordId == ckId }?.bookId
            } + (currentOrder - newOrder.toSet())
                .filter { finalEntries[it]?.let { e -> !e.isFavorite } ?: true }

            viewModelScope.launch(Dispatchers.IO) {
                dbManager.applyCloudKitBatch(upserted, deletedBookIds.distinct(), finalOrder)
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

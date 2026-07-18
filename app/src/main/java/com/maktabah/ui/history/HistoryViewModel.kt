package com.maktabah.ui.history

import android.content.Context
import androidx.lifecycle.ViewModel
import com.maktabah.models.ReadingEntry
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryViewModel : ViewModel() {
    companion object {
        private val _refreshFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val refreshFlow: SharedFlow<Unit> = _refreshFlow.asSharedFlow()

        fun notifyRefresh() {
            _refreshFlow.tryEmit(Unit)
        }
    }

    private val _entriesByBookId = MutableStateFlow<Map<Int, ReadingEntry>>(emptyMap())
    val entriesByBookId: StateFlow<Map<Int, ReadingEntry>> = _entriesByBookId.asStateFlow()

    private val _historyOrder = MutableStateFlow<List<Int>>(emptyList())
    val historyOrder: StateFlow<List<Int>> = _historyOrder.asStateFlow()

    private val maxHistoryCount = 50
    private var dataFile: File? = null
    private var isSyncing = false
    private var isRefreshing = false

    fun initialize(context: Context) {
        if (isRefreshing) return
        isRefreshing = true
        try {
            if (dataFile == null) {
                dataFile = File(context.filesDir, "user_data.json")
            }
            loadFromFile()
        } finally {
            isRefreshing = false
        }
    }

    fun getFavoriteBookIds(): List<Int> {
        return _entriesByBookId.value.values
            .asSequence()
            .filter { it.isFavorite }
            .sortedByDescending { it.favoritedAt ?: 0L }
            .map { it.bookId }
            .toList()
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var filteredHistoryFlow: StateFlow<List<Int>>? = null
    private var filteredFavoritesFlow: StateFlow<List<Int>>? = null

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
			if (cleanQuery.isBlank()) {
				order
			} else {
				order.filter { dataManager.bookContainsQuery(it, cleanQuery) }
			}
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
	
			if (cleanQuery.isBlank()) {
				favorites
			} else {
				favorites.filter { dataManager.bookContainsQuery(it, cleanQuery) }
			}
		}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), getFavoriteBookIds()).also {
			filteredFavoritesFlow = it
		}
	}

    fun addBookToHistory(bookId: Int) {
        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId] ?: ReadingEntry(
            bookId = bookId,
            ckRecordId = bookId.toString()
        )
        entry.lastOpenedAt = System.currentTimeMillis()
        entry.updatedAt = System.currentTimeMillis()
        entries[bookId] = entry

        val order = _historyOrder.value.toMutableList()
        order.remove(bookId)
        order.add(0, bookId)

        if (order.size > maxHistoryCount) {
            val toRemove = order.subList(maxHistoryCount, order.size)
            order.removeAll(toRemove)
        }

        _entriesByBookId.value = entries
        _historyOrder.value = order
        saveToFile()
    }

    fun updateLastContentId(contentId: Int, bookId: Int) {
        val entries = _entriesByBookId.value.toMutableMap()
        if (entries.containsKey(bookId)) {
            val entry = entries[bookId]!!
            entry.lastContentId = contentId
            entry.positionUpdatedAt = System.currentTimeMillis()
            entry.updatedAt = System.currentTimeMillis()
            entries[bookId] = entry
            _entriesByBookId.value = entries
            saveToFile()
        } else {
            addBookToHistory(bookId)
            updateLastContentId(contentId, bookId)
        }
    }

    fun toggleFavorite(bookId: Int) {
        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId] ?: ReadingEntry(
            bookId = bookId,
            ckRecordId = bookId.toString()
        )
        entry.isFavorite = !entry.isFavorite
        if (entry.isFavorite) {
            entry.favoritedAt = System.currentTimeMillis()
        }
        entry.updatedAt = System.currentTimeMillis()
        entries[bookId] = entry
        _entriesByBookId.value = entries
        saveToFile()
    }

    fun removeFromHistory(bookId: Int): ReadingEntry? {
        val order = _historyOrder.value.toMutableList()
        order.remove(bookId)
        _historyOrder.value = order

        val entries = _entriesByBookId.value.toMutableMap()
        val entry = entries[bookId]
        if (entry != null) {
            entry.lastOpenedAt = null
            entry.lastContentId = null
            entry.updatedAt = System.currentTimeMillis()
            entries[bookId] = entry
            _entriesByBookId.value = entries
        }
        saveToFile()
        return entry
    }

    private fun saveToFile() {
        if (isSyncing) {
            val file = dataFile ?: return
            if (file.exists()) {
                try {
                    val root = JSONObject(file.readText())
                    val entriesArray = root.optJSONArray("entries")
                    if (entriesArray != null) {
                        val memoryEntries = _entriesByBookId.value.toMutableMap()
                        for (i in 0 until entriesArray.length()) {
                            val obj = entriesArray.getJSONObject(i)
                            val bookId = obj.getInt("bookId")
                            if (!memoryEntries.containsKey(bookId)) {
                                val entry = ReadingEntry(
                                    bookId = bookId,
                                    lastContentId = if (obj.has("lastContentId")) obj.getInt("lastContentId") else null,
                                    lastOpenedAt = if (obj.has("lastOpenedAt")) obj.getLong("lastOpenedAt") else null,
                                    favoritedAt = if (obj.has("favoritedAt")) obj.getLong("favoritedAt") else null,
                                    positionUpdatedAt = if (obj.has("positionUpdatedAt")) obj.getLong("positionUpdatedAt") else null,
                                    updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                                    isFavorite = obj.optBoolean("isFavorite", false),
                                    ckRecordId = if (obj.has("ckRecordId")) obj.getString("ckRecordId") else null
                                )
                                memoryEntries[bookId] = entry
                            }
                        }
                        _entriesByBookId.value = memoryEntries
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        dataFile?.let { file ->
            val root = JSONObject()

            val orderArray = JSONArray()
            _historyOrder.value.forEach { orderArray.put(it) }
            root.put("historyOrder", orderArray)

            val entriesArray = JSONArray()
            _entriesByBookId.value.values.forEach { entry ->
                val obj = JSONObject()
                obj.put("bookId", entry.bookId)
                entry.lastContentId?.let { obj.put("lastContentId", it) }
                entry.lastOpenedAt?.let { obj.put("lastOpenedAt", it) }
                entry.favoritedAt?.let { obj.put("favoritedAt", it) }
                entry.positionUpdatedAt?.let { obj.put("positionUpdatedAt", it) }
                obj.put("updatedAt", entry.updatedAt)
                obj.put("isFavorite", entry.isFavorite)
                entry.ckRecordId?.let { obj.put("ckRecordId", it) }
                entriesArray.put(obj)
            }
            root.put("entries", entriesArray)

            file.writeText(root.toString())
        }
    }

    private fun loadFromFile() {
        dataFile?.let { file ->
            if (file.exists()) {
                try {
                    val root = JSONObject(file.readText())

                    val orderArray = root.optJSONArray("historyOrder")
                    val orderList = mutableListOf<Int>()
                    if (orderArray != null) {
                        for (i in 0 until orderArray.length()) {
                            orderList.add(orderArray.getInt(i))
                        }
                    }
                    _historyOrder.value = orderList

                    val entriesArray = root.optJSONArray("entries")
                    val entriesMap = mutableMapOf<Int, ReadingEntry>()
                    if (entriesArray != null) {
                        for (i in 0 until entriesArray.length()) {
                            val obj = entriesArray.getJSONObject(i)
                            val entry = ReadingEntry(
                                bookId = obj.getInt("bookId"),
                                lastContentId = if (obj.has("lastContentId")) obj.getInt("lastContentId") else null,
                                lastOpenedAt = if (obj.has("lastOpenedAt")) obj.getLong("lastOpenedAt") else null,
                                favoritedAt = if (obj.has("favoritedAt")) obj.getLong("favoritedAt") else null,
                                positionUpdatedAt = if (obj.has("positionUpdatedAt")) obj.getLong("positionUpdatedAt") else null,
                                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                                isFavorite = obj.optBoolean("isFavorite", false),
                                ckRecordId = if (obj.has("ckRecordId")) obj.getString("ckRecordId") else null
                            )
                            entriesMap[entry.bookId] = entry
                        }
                    }
                    _entriesByBookId.value = entriesMap
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun applyCloudKitChanges(entriesToSave: List<ReadingEntry>) {
        isSyncing = true
        try {
            val entries = _entriesByBookId.value.toMutableMap()
            for (incoming in entriesToSave) {
                val existing = entries[incoming.bookId]
                if (existing == null || incoming.updatedAt > existing.updatedAt) {
                    entries[incoming.bookId] = incoming
                }
            }

            // Ensure order has the incoming items at the top or update their positions
            val order = _historyOrder.value.toMutableList()
            for (incoming in entriesToSave.sortedBy { it.updatedAt }) {
                order.remove(incoming.bookId)
                order.add(0, incoming.bookId)
            }

            if (order.size > maxHistoryCount) {
                val toRemove = order.subList(maxHistoryCount, order.size)
                order.removeAll(toRemove)
            }

            _entriesByBookId.value = entries
            _historyOrder.value = order
            saveToFile()
            notifyRefresh()
        } finally {
            isSyncing = false
        }
    }

    fun clearAll() {
        _entriesByBookId.value = emptyMap()
        _historyOrder.value = emptyList()
        dataFile?.delete()
    }
}

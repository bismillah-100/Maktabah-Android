package com.maktabah.ui.search

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.models.BookContent
import com.maktabah.models.SearchMode
import com.maktabah.search.SearchEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import com.maktabah.R

class BookSearchViewModel : ViewModel() {
    private val searchEngine = SearchEngine()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _lastSearchQuery = MutableStateFlow("")
    val lastSearchQuery: StateFlow<String> = _lastSearchQuery.asStateFlow()

    private val _results = MutableStateFlow<List<BookContent>>(emptyList())
    val results: StateFlow<List<BookContent>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchMode = MutableStateFlow(SearchMode.PHRASE)
    val searchMode: StateFlow<SearchMode> = _searchMode.asStateFlow()

    private val _searchProgress = MutableStateFlow<Pair<Int, Int>?>(null) // Pair(current, total)
    val searchProgress: StateFlow<Pair<Int, Int>?> = _searchProgress.asStateFlow()

    fun updateQuery(newQuery: String) {
        _query.value = newQuery
    }

    fun updateSearchMode(mode: SearchMode) {
        _searchMode.value = mode
    }

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    fun loadHistory(context: Context) {
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        val historyString = prefs.getString("search_history_list", "") ?: ""
        if (historyString.isNotEmpty()) {
            _searchHistory.value = historyString.split("\n").filter { it.isNotEmpty() }
        } else {
            _searchHistory.value = emptyList()
        }
    }

    fun addToHistory(context: Context, query: String) {
        if (query.isBlank()) return
        val current = _searchHistory.value.toMutableList()
        current.remove(query)
        current.add(0, query)
        if (current.size > 20) {
            current.removeAt(current.lastIndex)
        }
        _searchHistory.value = current
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("search_history_list", current.joinToString("\n")).apply() }
    }

    fun removeFromHistory(context: Context, query: String) {
        val current = _searchHistory.value.toMutableList()
        current.remove(query)
        _searchHistory.value = current
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("search_history_list", current.joinToString("\n")).apply() }
    }

    fun clearHistory(context: Context) {
        _searchHistory.value = emptyList()
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        prefs.edit { remove("search_history_list").apply() }
    }

    fun search(
        context: Context,
        bookId: Int,
        archivePath: String,
    ) {
        val currentQuery = _query.value
        if (currentQuery.isBlank()) {
            _results.value = emptyList()
            return
        }

        _lastSearchQuery.value = currentQuery
        addToHistory(context, currentQuery)

        viewModelScope.launch {
            _isSearching.value = true
            _results.value = emptyList() // clear previous results
            _searchProgress.value = null

            val currentMode = _searchMode.value
            val archiveFile = File(archivePath)
            val ftsPath = archivePath.replace(".sqlite", "_fts.sqlite")
            val ftsFile = File(ftsPath)

            try {
                val searchResults =
                    searchEngine.searchInBook(
                        bookId = bookId,
                        archiveFile = archiveFile,
                        archiveFtsFile = ftsFile,
                        query = currentQuery,
                        mode = currentMode,
                        onRowProgress = { current, total ->
                            _searchProgress.value = Pair(current, total)
                        }
                    )
                _results.value = searchResults
            } catch (e: Exception) {
                e.printStackTrace()
            }

            _isSearching.value = false
            _searchProgress.value = null

            if (_results.value.isEmpty()) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.reader_toc_no_results),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

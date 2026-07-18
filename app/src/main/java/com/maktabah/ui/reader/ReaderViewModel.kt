package com.maktabah.ui.reader

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.database.AnnotationManager
import com.maktabah.database.BookConnection
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.ActiveAnnotationState
import com.maktabah.models.Annotation
import com.maktabah.models.BookContent
import com.maktabah.models.FlashTarget
import com.maktabah.models.TOCNode
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.maktabah.models.AnnotationSearchScope
import com.maktabah.ui.annotation.AnnotationCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ReaderViewModel : ViewModel() {

    // Tab-specific UI state for Book TOC
    var tocSearchQuery = mutableStateOf("")
    var tocExpandedNodes = mutableStateOf(setOf<String>())
    var tocListIndex = mutableIntStateOf(0)
    var tocListOffset = mutableIntStateOf(0)

    // Tab-specific UI state for Book Annotations
    var annotationSearchQuery = mutableStateOf("")
    var annotationSearchScope = mutableStateOf(AnnotationSearchScope.ALL)
    var annotationListIndex = mutableIntStateOf(0)
    var annotationListOffset = mutableIntStateOf(0)

    // Tab-specific UI state for Book Search
    var bookSearchListIndex = mutableIntStateOf(0)
    var bookSearchListOffset = mutableIntStateOf(0)

    private var libraryDataManager: LibraryDataManager? = null
    private var bookConnection: BookConnection? = null
    private var sharedPreferences: SharedPreferences? = null

    private val _currentContent = MutableStateFlow<BookContent?>(null)
    val currentContent: StateFlow<BookContent?> = _currentContent.asStateFlow()

    private var annotationManager: AnnotationManager? = null

    private val _currentAnnotations = MutableStateFlow<List<Annotation>>(emptyList())
    val currentAnnotations: StateFlow<List<Annotation>> = _currentAnnotations.asStateFlow()

    private val _bookAnnotations = MutableStateFlow<List<Annotation>>(emptyList())
    val bookAnnotations: StateFlow<List<Annotation>> = _bookAnnotations.asStateFlow()

    private val _activeAnnotationForEdit = MutableStateFlow<ActiveAnnotationState?>(null)
    val activeAnnotationForEdit: StateFlow<ActiveAnnotationState?> =
        _activeAnnotationForEdit.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _bookTitle = MutableStateFlow("")
    val bookTitle: StateFlow<String> = _bookTitle.asStateFlow()

    private val _isMultiLanguage = MutableStateFlow(false)
    val isMultiLanguage: StateFlow<Boolean> = _isMultiLanguage.asStateFlow()

    private val _textSize = MutableStateFlow(24f)
    val textSize: StateFlow<Float> = _textSize.asStateFlow()

    private val _showHarakat = MutableStateFlow(true)
    val showHarakat: StateFlow<Boolean> = _showHarakat.asStateFlow()

    private val _isDarkMode = MutableStateFlow(false)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _backgroundColorIndex =
        MutableStateFlow(0) // 0: White, 1: Sepia, 2: DarkSepia, 3: Gray, 4: Black
    val backgroundColorIndex: StateFlow<Int> = _backgroundColorIndex.asStateFlow()

    private val _fontIndex = MutableStateFlow(1)
    val fontIndex: StateFlow<Int> = _fontIndex.asStateFlow()

    private val _customFonts = MutableStateFlow<List<File>>(emptyList())
    val customFonts: StateFlow<List<File>> = _customFonts.asStateFlow()

    private val _tocList = MutableStateFlow<List<TOCNode>>(emptyList())
    val tocList: StateFlow<List<TOCNode>> = _tocList.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _flashTarget = MutableStateFlow<FlashTarget?>(null)
    val flashTarget: StateFlow<FlashTarget?> = _flashTarget.asStateFlow()

    private val _searchQuery = MutableStateFlow<String?>(null)
    val searchQuery: StateFlow<String?> = _searchQuery.asStateFlow()

    private val _totalParts = MutableStateFlow(0)
    val totalParts: StateFlow<Int> = _totalParts.asStateFlow()

    private val _minPageInPart = MutableStateFlow(0)
    val minPageInPart: StateFlow<Int> = _minPageInPart.asStateFlow()

    private val _maxPageInPart = MutableStateFlow(0)
    val maxPageInPart: StateFlow<Int> = _maxPageInPart.asStateFlow()

    private var currentBookId: Int = 0
    private val _currentBookIdFlow = MutableStateFlow(0)
    val currentBookIdFlow: StateFlow<Int> = _currentBookIdFlow.asStateFlow()
    private var archiveFile: File? = null
    private var isQuranBook: Boolean = false

    fun initialize(
        context: Context,
        annotationManager: AnnotationManager,
        libraryDataManager: LibraryDataManager
    ) {
        this.annotationManager = annotationManager
        this.libraryDataManager = libraryDataManager
        if (this.bookConnection == null) {
            this.bookConnection = BookConnection(libraryDataManager)
        }
        if (sharedPreferences == null) {
            sharedPreferences = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
            _textSize.value = sharedPreferences?.getFloat("text_size", 24f) ?: 24f
            _showHarakat.value = sharedPreferences?.getBoolean("show_harakat", true) ?: true
            _backgroundColorIndex.value = sharedPreferences?.getInt("bg_color_index", 0) ?: 0
            _fontIndex.value = sharedPreferences?.getInt("font_index", 1) ?: 1
            _isDarkMode.value = sharedPreferences?.getBoolean("is_dark_mode", false) ?: false
            _isInitialized.value = true

            loadCustomFonts(context)

            // Reactive subscription to updates flow
            viewModelScope.launch {
                AnnotationManager.updates.collect { _ ->
                    refreshAnnotations()
                }
            }
        }
    }

    fun updateTextSize(size: Float) {
        _textSize.value = size
        sharedPreferences?.edit { putFloat("text_size", size)?.apply() }
    }

    fun toggleHarakat() {
        _showHarakat.value = !_showHarakat.value
        sharedPreferences?.edit { putBoolean("show_harakat", _showHarakat.value)?.apply() }
    }

    fun setBackgroundColorIndex(index: Int) {
        _backgroundColorIndex.value = index
        sharedPreferences?.edit { putInt("bg_color_index", index)?.apply() }

        // Auto dark mode based on background color
        // 2: Dark Sepia, 4: Black are dark colors
        val shouldBeDark = index == 2 || index == 4
        if (_isDarkMode.value != shouldBeDark) {
            _isDarkMode.value = shouldBeDark
            sharedPreferences?.edit { putBoolean("is_dark_mode", shouldBeDark)?.apply() }
        }
    }

    fun setFontIndex(index: Int) {
        _fontIndex.value = index
        sharedPreferences?.edit { putInt("font_index", index)?.apply() }
    }

    private fun loadCustomFonts(context: Context) {
        val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
        val fontPaths = prefs.getStringSet("custom_fonts", emptySet()) ?: emptySet()
        _customFonts.value = fontPaths.asSequence().map { File(it) }.filter { it.exists() }.toList()
    }

    fun loadBook(
        bookId: Int,
        archivePath: String,
        title: String,
        initialContentId: Int? = null,
        isQuran: Boolean = false
    ) {
        currentBookId = bookId
        _currentBookIdFlow.value = bookId
        _bookTitle.value = title
        _isMultiLanguage.value = libraryDataManager?.booksById?.get(bookId)?.isMultiLanguage == true
        archiveFile = File(archivePath)
        isQuranBook = isQuran

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val content = withContext(Dispatchers.IO) {
                    archiveFile?.let { file ->
                        val fetchedToc =
                            bookConnection?.getTableOfContents(bookId, file) ?: emptyList()
                        _tocList.value = fetchedToc

                        if (initialContentId != null) {
                            bookConnection?.getContent(bookId, initialContentId, file, isQuranBook)
                        } else {
                            bookConnection?.getFirstContent(bookId, file)
                        }
                    }
                }
                _currentContent.value = content
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
                refreshAnnotations()
                updateNavigationLimits()
            }
        }
    }

    fun loadContentById(contentId: Int) {
        val archive = archiveFile ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val content = withContext(Dispatchers.IO) {
                    bookConnection?.getContent(currentBookId, contentId, archive, isQuranBook)
                }
                if (content != null) {
                    _currentContent.value = content
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
                refreshAnnotations()
                updateNavigationLimits()
            }
        }
    }

    fun nextPage() {
        val currentId = _currentContent.value?.id ?: return
        val archive = archiveFile ?: return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val nextContent = withContext(Dispatchers.IO) {
                    bookConnection?.getNextPage(currentBookId, currentId, archive, isQuranBook)
                }
                if (nextContent != null) {
                    _currentContent.value = nextContent
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
                refreshAnnotations()
                updateNavigationLimits()
            }
        }
    }

    fun prevPage() {
        val currentId = _currentContent.value?.id ?: return
        val archive = archiveFile ?: return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val prevContent = withContext(Dispatchers.IO) {
                    bookConnection?.getPrevPage(currentBookId, currentId, archive, isQuranBook)
                }
                if (prevContent != null) {
                    _currentContent.value = prevContent
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
                refreshAnnotations()
                updateNavigationLimits()
            }
        }
    }

    fun updateNavigationLimits() {
        val content = _currentContent.value ?: return
        val archive = archiveFile ?: return
        val bkid = currentBookId

        viewModelScope.launch(Dispatchers.IO) {
            val total = bookConnection?.getTotalParts(bkid, archive) ?: 0
            val part = content.part.let { if (it < 1) 1 else it }
            val minPg = bookConnection?.getMinPagesInPart(bkid, part, archive) ?: 0
            val maxPg = bookConnection?.getPagesInPart(bkid, part, archive) ?: 0

            _totalParts.value = total
            _minPageInPart.value = minPg
            _maxPageInPart.value = maxPg
        }
    }

    fun jumpToPart(part: Int) {
        val archive = archiveFile ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val content = withContext(Dispatchers.IO) {
                    val minPage =
                        bookConnection?.getMinPagesInPart(currentBookId, part, archive) ?: 1
                    bookConnection?.getContent(currentBookId, part, minPage, archive)
                }
                if (content != null) {
                    _currentContent.value = content
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
                refreshAnnotations()
                updateNavigationLimits()
            }
        }
    }

    fun jumpToPage(page: Int) {
        val archive = archiveFile ?: return
        val part = _currentContent.value?.part ?: 1
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val content = withContext(Dispatchers.IO) {
                    bookConnection?.getContent(currentBookId, part, page, archive)
                }
                if (content != null) {
                    _currentContent.value = content
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
                refreshAnnotations()
                updateNavigationLimits()
            }
        }
    }

    fun refreshAnnotations() {
        val manager = annotationManager ?: return
        val bid = currentBookId
        val cid = _currentContent.value?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val all = manager.getAnnotationsForBook(bid)
            _bookAnnotations.value = all
            _currentAnnotations.value = all.filter { it.contentId == cid }
        }
    }

    fun showAnnotationEditor(active: ActiveAnnotationState?) {
        _activeAnnotationForEdit.value = active
    }

    fun setFlashTarget(target: FlashTarget?) {
        _flashTarget.value = target
    }

    fun clearFlashTarget() {
        _flashTarget.value = null
    }

    fun setSearchQuery(query: String?) {
        _searchQuery.value = query
    }

    fun addAnnotationDirectly(
        loc: Int,
        len: Int,
        selectedText: String,
        rawNass: String,
        type: Int,
        colorHex: String,
        onComplete: (() -> Unit)? = null
    ) {
        val currentContent = _currentContent.value ?: return
        val manager = annotationManager ?: return
        val bid = currentBookId

        viewModelScope.launch(Dispatchers.IO) {
            val (diacLoc, diacLen, plainLoc, plainLen) =
                AnnotationCoordinator.calculateBothRanges(
                    loc,
                    len,
                    rawNass,
                    _showHarakat.value
                )

            val annotationToSave = Annotation(
                id = null,
                bkId = bid,
                contentId = currentContent.id,
                colorHex = colorHex,
                note = null,
                type = type,
                createdAt = (System.currentTimeMillis() / 1000),
                page = currentContent.page,
                context = selectedText,
                rangeLocation = plainLoc,
                rangeLength = plainLen,
                rangeDiacLocation = diacLoc,
                rangeDiacLength = diacLen,
                part = currentContent.part,
                tags = "",
                ckRecordId = UUID.randomUUID().toString(),
            )

            manager.insertOrUpdate(annotationToSave)
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    fun deleteAnnotationDirectly(annotation: Annotation, onComplete: (() -> Unit)? = null) {
        val manager = annotationManager ?: return
        val id = annotation.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            manager.deleteAnnotation(id, annotation.ckRecordId)
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    fun importFont(uri: Uri, context: Context, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                val contentResolver = context.contentResolver
                var fileName: String? = null
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }

                if (fileName == null) {
                    fileName = uri.lastPathSegment ?: "custom_font_${System.currentTimeMillis()}.ttf"
                }

                val fontsDir = File(context.filesDir, "fonts")
                if (!fontsDir.exists()) {
                    fontsDir.mkdirs()
                }

                val destFile = File(fontsDir, fileName)
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(destFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                // Update custom fonts list in preferences
                // Use MaktabahPrefs for global sync, or reader_prefs if specific to reader.
                // Settings used MaktabahPrefs, keeping it for consistency.
                val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
                val currentFonts = prefs.getStringSet("custom_fonts", emptySet())?.toMutableSet() ?: mutableSetOf()
                currentFonts.add(destFile.absolutePath)
                prefs.edit { putStringSet("custom_fonts", currentFonts).apply() }

                loadCustomFonts(context)
                onSuccess()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteFont(font: File, context: Context) {
        viewModelScope.launch {
            try {
                if (font.exists()) {
                    font.delete()
                }
                val prefs = context.getSharedPreferences("MaktabahPrefs", Context.MODE_PRIVATE)
                val currentFonts =
                    prefs.getStringSet("custom_fonts", emptySet())?.toMutableSet() ?: mutableSetOf()
                currentFonts.remove(font.absolutePath)
                prefs.edit { putStringSet("custom_fonts", currentFonts).apply() }

                loadCustomFonts(context)

                // Reset to default font (Lateef) if a custom font was deleted to be safe
                // or if the current index is now out of bounds
                val builtInCount = 4 // Matches builtInFontNames.size in ReaderOptions.kt
                if (_fontIndex.value >= builtInCount) {
                    _fontIndex.value = 1
                    sharedPreferences?.edit { putInt("font_index", 1).apply() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Called manually when the tab is closed to prevent memory leaks,
     * since this ViewModel is instantiated manually and not tied to the standard Compose lifecycle.
     */
    fun onClose() {
        viewModelScope.cancel()
        libraryDataManager = null
        bookConnection = null
        annotationManager = null
        sharedPreferences = null
        archiveFile = null
    }
}

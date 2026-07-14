package com.maktabah.ui.library

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.database.BookImportManager
import com.maktabah.models.AuthorMode
import com.maktabah.models.AuthorRow
import com.maktabah.models.BooksData
import com.maktabah.models.CategoryData
import com.maktabah.models.ImportBookMetadata
import com.maktabah.models.ImportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImportBookViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext

    // --- Loading state ---
    private val _isLoadingData = MutableStateFlow(true)
    val isLoadingData: StateFlow<Boolean> = _isLoadingData

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    private val _importResult = MutableStateFlow<Result<Unit>?>(null)
    val importResult: StateFlow<Result<Unit>?> = _importResult

    // --- Library data ---
    private val _categories = MutableStateFlow<List<CategoryData>>(emptyList())
    val categories: StateFlow<List<CategoryData>> = _categories

    private val _authors = MutableStateFlow<List<AuthorRow>>(emptyList())
    val authors: StateFlow<List<AuthorRow>> = _authors

    private val _books = MutableStateFlow<List<BooksData>>(emptyList())
    val books: StateFlow<List<BooksData>> = _books

    private val _maxBkid = MutableStateFlow(0)

    private val _maxAuthid = MutableStateFlow(0)
    val maxAuthid: StateFlow<Int> = _maxAuthid

    // --- Form state ---
    private val _importMode = MutableStateFlow(ImportMode.NEW)
    val importMode: StateFlow<ImportMode> = _importMode

    private val _selectedFileUri = MutableStateFlow<Uri?>(null)

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName

    // Book fields
    private val _customBookIdText = MutableStateFlow("")
    val customBookIdText: StateFlow<String> = _customBookIdText

    private val _selectedBookId = MutableStateFlow<Int?>(null)
    val selectedBookId: StateFlow<Int?> = _selectedBookId

    private val _bookName = MutableStateFlow("")
    val bookName: StateFlow<String> = _bookName

    private val _categoryId = MutableStateFlow(0)
    val categoryId: StateFlow<Int> = _categoryId

    private val _archiveId = MutableStateFlow(20)
    val archiveId: StateFlow<Int> = _archiveId

    private val _isMultiLanguage = MutableStateFlow(true)
    val isMultiLanguage: StateFlow<Boolean> = _isMultiLanguage

    private val _betaka = MutableStateFlow("")
    val betaka: StateFlow<String> = _betaka

    private val _inf = MutableStateFlow("")
    val inf: StateFlow<String> = _inf

    private val _tafseerNam = MutableStateFlow("")
    val tafseerNam: StateFlow<String> = _tafseerNam

    private val _bVerText = MutableStateFlow("1")
    val bVerText: StateFlow<String> = _bVerText

    // Author fields
    private val _authorMode = MutableStateFlow(AuthorMode.EXISTING)
    val authorMode: StateFlow<AuthorMode> = _authorMode

    private val _selectedAuthorId = MutableStateFlow<Int?>(null)
    val selectedAuthorId: StateFlow<Int?> = _selectedAuthorId

    private val _authorName = MutableStateFlow("")
    val authorName: StateFlow<String> = _authorName

    private val _authorInf = MutableStateFlow("")
    val authorInf: StateFlow<String> = _authorInf

    private val _authorLng = MutableStateFlow("")
    val authorLng: StateFlow<String> = _authorLng

    private val _authorHigriD = MutableStateFlow("")
    val authorHigriD: StateFlow<String> = _authorHigriD

    private val _oVerText = MutableStateFlow("1")
    val oVerText: StateFlow<String> = _oVerText

    // --- Setters ---
    fun setImportMode(mode: ImportMode) {
        _importMode.value = mode
    }

    fun setAuthorMode(mode: AuthorMode) {
        _authorMode.value = mode
    }

    fun setCustomBookIdText(v: String) {
        _customBookIdText.value = v.filter { it.isDigit() }
    }

    fun setSelectedBookId(id: Int?, name: String?, archiveId: Int?, catId: Int?) {
        _selectedBookId.value = id
        if (name != null) _bookName.value = name
        if (archiveId != null) _archiveId.value = archiveId
        if (catId != null) _categoryId.value = catId
    }

    fun setBookName(v: String) {
        _bookName.value = v
    }

    fun setCategoryId(v: Int) {
        _categoryId.value = v
    }

    fun setArchiveId(v: Int) {
        _archiveId.value = v.coerceIn(1, 20)
    }

    fun setIsMultiLanguage(v: Boolean) {
        _isMultiLanguage.value = v
    }

    fun setBetaka(v: String) {
        _betaka.value = v
    }

    fun setInf(v: String) {
        _inf.value = v
    }

    fun setTafseerNam(v: String) {
        _tafseerNam.value = v
    }

    fun setBVerText(v: String) {
        _bVerText.value = v
    }

    fun setSelectedAuthorId(id: Int) {
        _selectedAuthorId.value = id
    }

    fun setAuthorName(v: String) {
        _authorName.value = v
    }

    fun setAuthorInf(v: String) {
        _authorInf.value = v
    }

    fun setAuthorLng(v: String) {
        _authorLng.value = v
    }

    fun setAuthorHigriD(v: String) {
        _authorHigriD.value = v
    }

    fun setOVerText(v: String) {
        _oVerText.value = v
    }

    fun clearResult() {
        _importResult.value = null
    }

    fun setSelectedFile(uri: Uri, displayName: String) {
        _selectedFileUri.value = uri
        _selectedFileName.value = displayName
    }

    // --- Computed ---
    val isBookIdTaken: StateFlow<Boolean> = _customBookIdText.map { text ->
        val id = text.toIntOrNull() ?: return@map false
        BookImportManager.isBookIdTaken(context, id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isValid: StateFlow<Boolean> = combine(
        _importMode,
        _selectedFileUri,
        _customBookIdText,
        _selectedBookId,
        _bookName,
        _categoryId,
        _authorMode,
        _selectedAuthorId,
        _authorName,
        isBookIdTaken
    ) { flows ->
        val mode = flows[0] as ImportMode
        val uri = flows[1] as Uri?
        val customIdText = flows[2] as String
        val selBookId = flows[3] as Int?
        val name = flows[4] as String
        val catId = flows[5] as Int
        val authMode = flows[6] as AuthorMode
        val selAuthId = flows[7] as Int?
        val authName = flows[8] as String
        val idTaken = flows[9] as Boolean

        when (mode) {
            ImportMode.CHANGE_ID -> {
                val oldId = selBookId ?: return@combine false
                val newId = customIdText.toIntOrNull() ?: return@combine false
                newId > 0 && newId != oldId && !idTaken
            }

            else -> {
                if (uri == null) return@combine false
                when (mode) {
                    ImportMode.NEW -> {
                        val id = customIdText.toIntOrNull() ?: return@combine false
                        if (id <= 0 || idTaken) return@combine false
                    }

                    ImportMode.REPLACE -> if (selBookId == null) return@combine false
                }
                if (name.isBlank()) return@combine false
                if (catId == 0) return@combine false
                when (authMode) {
                    AuthorMode.EXISTING -> if (selAuthId == null) return@combine false
                    AuthorMode.NEW -> if (authName.isBlank()) return@combine false
                }
                true
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setupData(context: Context) {
        viewModelScope.launch {
            _isLoadingData.value = true
            val (maxBk, maxAuth, cats, auths, bks) = withContext(Dispatchers.IO) {
                val maxBk = BookImportManager.getMaxBookId(context)
                val maxAuth = BookImportManager.getMaxAuthId(context)
                val cats = BookImportManager.getCategories(context).distinctBy { it.id }
                val auths = BookImportManager.getAuthors(context).distinctBy { it.id }
                val bks = BookImportManager.getBooks(context).distinctBy { it.id }
                quintuple(maxBk, maxAuth, cats, auths, bks)
            }
            _maxBkid.value = maxBk
            _maxAuthid.value = maxAuth
            _categories.value = cats
            _authors.value = auths
            _books.value = bks
            _customBookIdText.value = "${maxBk + 1}"
            _isLoadingData.value = false
        }
    }

    fun performImport(
        context: Context,
        annotationManager: AnnotationManager,
        onReloadLibrary: suspend () -> Unit
    ) {
        viewModelScope.launch {
            _isImporting.value = true
            val result = withContext(Dispatchers.IO) {
                try {
                    if (_importMode.value == ImportMode.CHANGE_ID) {
                        performChangeBookId(context, annotationManager)
                    } else {
                        performBookImport(context)
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            if (result.isSuccess) onReloadLibrary()
            _importResult.value = result
            _isImporting.value = false
        }
    }

    private suspend fun performBookImport(context: Context): Result<Unit> {
        val uri = _selectedFileUri.value
            ?: return Result.failure(Exception(context.getString(R.string.error_file_not_selected)))
        val finalBookId = if (_importMode.value == ImportMode.NEW)
            _customBookIdText.value.toIntOrNull() ?: (_maxBkid.value + 1)
        else
            _selectedBookId.value
                ?: return Result.failure(Exception(context.getString(R.string.error_target_book_not_selected)))

        val (finalAuthId, newAuthor) = resolveAuthor()

        val metadata = ImportBookMetadata(
            bkid = finalBookId,
            categoryId = _categoryId.value,
            bookName = _bookName.value,
            archiveId = _archiveId.value,
            betaka = _betaka.value.ifBlank { null },
            inf = _inf.value.ifBlank { null },
            tafseerNam = _tafseerNam.value.ifBlank { null },
            bVer = _bVerText.value.toIntOrNull() ?: 1,
            isMultiLanguage = _isMultiLanguage.value,
            authno = finalAuthId,
        )

        // Salin file ke temp agar bisa diakses setelah URI dilepas
        val tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.sqlite")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return Result.failure(Exception(context.getString(R.string.error_cannot_read_file)))

        return BookImportManager.importBook(context, tempFile, metadata, newAuthor)
    }

    private fun performChangeBookId(
        context: Context,
        annotationManager: AnnotationManager
    ): Result<Unit> {
        val oldId = _selectedBookId.value
            ?: return Result.failure(Exception(context.getString(R.string.error_source_book_not_selected)))
        val newId = _customBookIdText.value.toIntOrNull()
            ?: return Result.failure(Exception(context.getString(R.string.error_invalid_new_id)))

        val changeResult = BookImportManager.changeBookId(context, oldId, newId)
        if (changeResult.isFailure) return changeResult

        // Update bkId pada anotasi yang ada
        annotationManager.migrateBookId(oldId, newId)

        return Result.success(Unit)
    }

    private fun resolveAuthor(): Pair<Int?, AuthorRow?> {
        return if (_authorMode.value == AuthorMode.NEW) {
            val newId = _maxAuthid.value + 1
            val author = AuthorRow(
                id = newId,
                name = _authorName.value,
                lng = _authorLng.value,
                inf = _authorInf.value,
                higriD = _authorHigriD.value,
                oVer = _oVerText.value.toIntOrNull() ?: 1,
            )
            Pair(newId, author)
        } else {
            Pair(_selectedAuthorId.value, null)
        }
    }

    private data class Quintuple<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private fun <A, B, C, D, E> quintuple(a: A, b: B, c: C, d: D, e: E) = Quintuple(a, b, c, d, e)
}

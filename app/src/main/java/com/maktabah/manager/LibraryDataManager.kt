package com.maktabah.manager

import android.util.Log
import com.maktabah.database.SQLiteDB
import com.maktabah.models.BooksData
import com.maktabah.models.CategoryData
import com.maktabah.models.ShortsMapping
import com.maktabah.utils.normalizeArabic
import java.io.File

class LibraryDataManager(
    private val mainDbFile: File,
) {
    private data class CatalogSnapshot(
        val allRootCategories: List<CategoryData> = emptyList(),
        val categoryMap: Map<Int, CategoryData> = emptyMap(),
        val booksById: Map<Int, BooksData> = emptyMap(),
        val normalizedNamesById: Map<Int, String> = emptyMap(),
    )

    @Volatile
    private var snapshot = CatalogSnapshot()

    val allRootCategories: List<CategoryData>
        get() = snapshot.allRootCategories

    val categoryMap: Map<Int, CategoryData>
        get() = snapshot.categoryMap

    val booksById: Map<Int, BooksData>
        get() = snapshot.booksById

    val normalizedNamesById: Map<Int, String>
        get() = snapshot.normalizedNamesById

    private val shortsCache = mutableMapOf<String, ShortsMapping>()

    fun loadData() {
        if (!mainDbFile.exists()) return
        synchronized(shortsCache) {
            shortsCache.clear()
        }

        val categories = mutableListOf<CategoryData>()
        val catMap = mutableMapOf<Int, CategoryData>()
        val booksMap = mutableMapOf<Int, BooksData>()
        val normalizedNamesMap = mutableMapOf<Int, String>()
        val authorsMap = mutableMapOf<Int, Pair<String, String>>()
        val rootCats = mutableListOf<CategoryData>()

        SQLiteDB(
            mainDbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
        ).use { db ->
            // Load Categories
            db.prepare("SELECT id, name, Lvl, catord FROM \"0cat\" ORDER BY catord ASC")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    val cat =
                        CategoryData(
                            id = stmt.columnInt(0),
                            name = stmt.columnText(1) ?: "",
                            level = stmt.columnInt(2),
                            order = stmt.columnInt(3),
                        )
                    categories.add(cat)
                    catMap[cat.id] = cat
                }
            }

            // Build Category Hierarchy BEFORE loading books so sub-categories appear at the top
            var currentRoot: CategoryData? = null
            for (cat in categories) {
                if (cat.level == 0) {
                    rootCats.add(cat)
                    currentRoot = cat
                } else if (cat.level == 1 && currentRoot != null) {
                    currentRoot.children.add(cat)
                }
            }

            // Load Authors from special.sqlite
            val specialDbFile = File(mainDbFile.parentFile, "special.sqlite")
            if (specialDbFile.exists()) {
                try {
                    SQLiteDB(
                        specialDbFile.absolutePath,
                        SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
                    ).use { specDb ->
                        specDb.prepare("SELECT authid, auth, Lng, inf FROM Auth")?.use { authStmt ->
                            while (authStmt.step() == SQLiteDB.SQLITE_ROW) {
                                val authId = authStmt.columnInt(0)
                                val authName = authStmt.columnText(1) ?: ""
                                val fullName = authStmt.columnText(2) ?: ""
                                val authInf = authStmt.columnText(3) ?: ""
                                val displayName = fullName.ifEmpty { authName }
                                authorsMap[authId] = Pair(displayName, authInf)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LibraryDataManager", "Error loading authors from special.sqlite", e)
                }
            }

            // Load Books
            db.prepare(
                """
                SELECT bkid, cat, bk, Archive, inf, 
                COALESCE(betaka, ''), COALESCE(authno, 0), COALESCE(auth, ''), COALESCE(AuthInf, ''), PdfCs
                FROM "0bok" ORDER BY bk ASC
                """,
            )?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    val authNo = stmt.columnInt(6)
                    val authorData = authorsMap[authNo]
                    val fallbackAuth = stmt.columnText(7) ?: ""
                    val fallbackAuthInf = stmt.columnText(8) ?: ""

                    val book =
                        BooksData(
                            id = stmt.columnInt(0),
                            categoryId = stmt.columnInt(1),
                            name = stmt.columnText(2) ?: "",
                            archive = stmt.columnInt(3),
                            info = stmt.columnText(4) ?: "",
                            betaka = stmt.columnText(5) ?: "",
                            authno = authNo,
                            auth = authorData?.first?.takeIf { it.isNotEmpty() } ?: fallbackAuth,
                            authInf = authorData?.second?.takeIf { it.isNotEmpty() } ?: fallbackAuthInf,
                            isMultiLanguage = stmt.columnInt(9) == 3,
                        )

                    if (booksMap.containsKey(book.id)) {
                        val oldBook = booksMap[book.id]
                        if (oldBook != null) {
                            catMap[oldBook.categoryId]?.children?.remove(oldBook)
                        }
                    }

                    booksMap[book.id] = book
                    normalizedNamesMap[book.id] = book.name.normalizeArabic()
                    catMap[book.categoryId]?.children?.add(book)
                }
            }
        }

        snapshot = CatalogSnapshot(
            allRootCategories = rootCats.toList(),
            categoryMap = catMap.toMap(),
            booksById = booksMap.toMap(),
            normalizedNamesById = normalizedNamesMap.toMap(),
        )
    }

    fun loadShortsForBook(bookId: String): ShortsMapping {
        synchronized(shortsCache) {
            if (shortsCache.containsKey(bookId)) {
                return shortsCache[bookId]!!
            }
        }

        val dict = mutableMapOf<String, String>()
        val specialDbFile = File(mainDbFile.parentFile, "special.sqlite")

        if (specialDbFile.exists()) {
            try {
                SQLiteDB(
                    specialDbFile.absolutePath,
                    SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
                ).use { specDb ->
                    specDb.prepare("SELECT Ramz, Nass FROM shorts WHERE Bk = ?")?.use { stmt ->
                        stmt.bindText(1, bookId)
                        while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                            val ramz = stmt.columnText(0) ?: ""
                            val nass = stmt.columnText(1) ?: ""
                            if (ramz.isNotEmpty()) {
                                dict[ramz] = nass
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LibraryDataManager", "Error loading shorts for book $bookId", e)
            }
        }

        // Urutkan key dari yang terpanjang ke terpendek (Sama seperti iOS)
        val sortedKeys = dict.keys.sortedByDescending { it.length }
        val mapping = ShortsMapping(dict, sortedKeys)

        synchronized(shortsCache) {
            shortsCache[bookId] = mapping
        }
        return mapping
    }

    fun buildAuthorHierarchy(): List<CategoryData> {
        val allBooks = booksById.values
        val booksByAuthor = mutableMapOf<Int, MutableList<BooksData>>()
        val booksWithNoAuthor = mutableListOf<BooksData>()

        val authorCategories = mutableListOf<CategoryData>()
        val authorNames = mutableMapOf<Int, String>()

        for (book in allBooks) {
            if (book.authno == 0) {
                booksWithNoAuthor.add(book)
            } else {
                booksByAuthor.getOrPut(book.authno) { mutableListOf() }.add(book)
                if (book.auth.isNotEmpty()) {
                    authorNames[book.authno] = book.auth
                }
            }
        }

        for ((authorId, books) in booksByAuthor) {
            val name = authorNames[authorId] ?: "Unknown Author ($authorId)"
            val authorCategory = CategoryData(
                id = authorId,
                name = name,
                level = 0,
                order = authorId
            )
            authorCategory.children.addAll(books.sortedBy { it.name })
            authorCategories.add(authorCategory)
        }

        authorCategories.sortBy { it.name }

        if (booksWithNoAuthor.isNotEmpty()) {
            val noAuthorCategory = CategoryData(
                id = 0,
                name = "---",
                level = 0,
                order = Int.MAX_VALUE
            )
            noAuthorCategory.children.addAll(booksWithNoAuthor.sortedBy { it.name })
            authorCategories.add(noAuthorCategory)
        }

        return authorCategories
    }

    fun bookContainsNormalizedQuery(
        bookId: Int,
        normalizedQuery: String,
        normalizedNames: Map<Int, String> = this.normalizedNamesById,
        books: Map<Int, BooksData> = this.booksById,
    ): Boolean {
        if (normalizedQuery.isEmpty()) return true
        val normalizedName = normalizedNames[bookId]
            ?: books[bookId]?.name?.normalizeArabic()
            ?: return false
        return normalizedName.contains(normalizedQuery, ignoreCase = true)
    }

    fun bookContainsNormalizedQuery(
        book: BooksData,
        normalizedQuery: String,
        normalizedNames: Map<Int, String> = this.normalizedNamesById,
    ): Boolean {
        if (normalizedQuery.isEmpty()) return true
        val normalizedName = normalizedNames[book.id] ?: book.name.normalizeArabic()
        return normalizedName.contains(normalizedQuery, ignoreCase = true)
    }

    // Fungsi pembantu untuk cek apakah nama buku mengandung query
    fun bookContainsQuery(bookId: Int, query: String): Boolean {
        return bookContainsNormalizedQuery(bookId, query.normalizeArabic())
    }

    fun getBookNames(ids: List<Int>): Map<Int, String> {
        if (!mainDbFile.exists() || ids.isEmpty()) {
            return emptyMap()
        }
        val result = mutableMapOf<Int, String>()
        SQLiteDB(
            mainDbFile.absolutePath,
            SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
        ).use { db ->
            for (chunk in ids.chunked(900)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.prepare("SELECT bkid, bk FROM \"0bok\" WHERE bkid IN ($placeholders)")?.use { stmt ->
                    chunk.forEachIndexed { index, id -> stmt.bindInt(index + 1, id) }
                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                        val id = stmt.columnInt(0)
                        val name = stmt.columnText(1) ?: ""
                        result[id] = name
                    }
                }
            }
        }
        return result
    }
}

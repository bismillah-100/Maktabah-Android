package com.maktabah.database

import com.github.luben.zstd.Zstd
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.BookContent
import com.maktabah.models.ShortsMapping
import com.maktabah.models.TOC
import com.maktabah.models.TOCNode
import java.io.File


class BookConnection(private val libraryDataManager: LibraryDataManager) {
    fun getTableOfContents(
        bookId: Int,
        archiveDbFile: File,
    ): List<TOCNode> {
        if (!archiveDbFile.exists()) return emptyList()

        val flatTOCs = mutableListOf<TOC>()
        val tableName = "t$bookId"

        SQLiteDB(archiveDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            db.prepare("SELECT id, tit, COALESCE(lvl, 0), COALESCE(sub, 0) FROM $tableName ORDER BY id ASC")?.use { stmt ->
                while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    flatTOCs.add(
                        TOC(
                            id = stmt.columnInt(0),
                            title = stmt.columnText(1) ?: "",
                            level = stmt.columnInt(2),
                            sub = stmt.columnInt(3),
                        ),
                    )
                }
            }
        }

        return buildTOCTree(flatTOCs)
    }

    private fun buildTOCTree(flatTOCs: List<TOC>): List<TOCNode> {
        if (flatTOCs.isEmpty()) return emptyList()

        val allNodes = mutableListOf<TOCNode>()
        val levelStacks = mutableMapOf<Int, MutableList<TOCNode>>()

        for (toc in flatTOCs) {
            val node = TOCNode(toc)
            allNodes.add(node)
            levelStacks.getOrPut(node.level) { mutableListOf() }.add(node)
        }

        val sortedLevels = levelStacks.keys.sorted()
        val minLevel = sortedLevels.firstOrNull() ?: return emptyList()

        // Semuanya di minLevel otomatis menjadi root node karena tidak ada level di atasnya.
        val rootNodes = levelStacks[minLevel]?.toMutableList() ?: mutableListOf()

        for (currentLevel in sortedLevels) {
            if (currentLevel <= minLevel) continue
            val nodesAtCurrentLevel = levelStacks[currentLevel] ?: continue

            for (node in nodesAtCurrentLevel) {
                var foundParent = false

                for (parentLevel in currentLevel - 1 downTo 1) {
                    val candidateParents = levelStacks[parentLevel] ?: continue
                    val parent = candidateParents.lastOrNull { it.id <= node.id }
                    if (parent != null) {
                        parent.children.add(node)
                        foundParent = true
                        break
                    }
                }

                if (!foundParent) {
                    rootNodes.add(node)
                }
            }
        }

        for (i in allNodes.indices) {
            if (i < allNodes.size - 1) {
                allNodes[i].endID = allNodes[i + 1].id - 1
            } else {
                allNodes[i].endID = Int.MAX_VALUE
            }
        }

        return rootNodes
    }

    private fun parsePartValue(
        stmt: SQLiteStmt,
        col: Int,
    ): Int {
        val type = stmt.columnType(col)
        if (type == SQLiteDB.SQLITE_INTEGER) {
            return stmt.columnInt(col)
        } else if (type == SQLiteDB.SQLITE_TEXT) {
            val strValue = stmt.columnText(col)
            if (strValue != null) {
                val dashIndex = strValue.indexOf('-')
                if (dashIndex != -1) {
                    return strValue.substring(0, dashIndex).toIntOrNull() ?: 1
                }
                return strValue.toIntOrNull() ?: 1
            }
        }
        return 1
    }

    /**
     * Mengganti singkatan/simbol berdasarkan ShortsMapping yang diurutkan
     */
    fun applyShortsMapping(text: String, mapping: ShortsMapping): String {
        if (mapping.isEmpty) return text

        var output = text
        for (key in mapping.sortedKeys) {
            val replacement = mapping.map[key]
            if (replacement != null) {
                output = output.replace(key, "$replacement\n")
            }
        }
        return output
    }

    fun getContent(
        bookId: Int,
        contentId: Int,
        archiveDbFile: File,
        isQuran: Boolean = false,
    ): BookContent? {
        if (!archiveDbFile.exists()) return null

        var content: BookContent? = null
        val tableName = "b$bookId"

        SQLiteDB(archiveDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            val columns = if (isQuran) "id, nass, page, part, sora, aya" else "id, nass, page, part"
            db.prepare("SELECT $columns FROM $tableName WHERE id = ? LIMIT 1")?.use { stmt ->
                stmt.bindInt(1, contentId)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    val id = stmt.columnInt(0)
                    var nassText = ""
                    if (stmt.columnType(1) == SQLiteDB.SQLITE_BLOB) {
                        nassText = decompressBlob(stmt.columnBlobDirect(1))
                    } else {
                        nassText = stmt.columnText(1) ?: ""
                    }

                    val shortsMap = libraryDataManager.loadShortsForBook(bookId.toString())
                    if (!shortsMap.isEmpty) {
                        nassText = applyShortsMapping(nassText, shortsMap)
                    }

                    val page = stmt.columnInt(2)
                    val part = parsePartValue(stmt, 3)

                    content = BookContent(id, nassText, page, part)
                }
            }
        }
        return content
    }

    fun getFirstContent(
        bookId: Int,
        archiveDbFile: File,
    ): BookContent? {
        if (!archiveDbFile.exists()) return null

        var content: BookContent? = null
        val tableName = "b$bookId"

        SQLiteDB(archiveDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            db.prepare("SELECT id, nass, page, part FROM $tableName ORDER BY id ASC LIMIT 1")?.use { stmt ->
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    val id = stmt.columnInt(0)
                    var nassText = ""
                    if (stmt.columnType(1) == SQLiteDB.SQLITE_BLOB) {
                        nassText = decompressBlob(stmt.columnBlobDirect(1))
                    } else {
                        nassText = stmt.columnText(1) ?: ""
                    }

                    // --- PROSES SHORTS MAPPING DISINI ---
                    val shortsMap = libraryDataManager.loadShortsForBook(bookId.toString())
                    if (!shortsMap.isEmpty) {
                        nassText = applyShortsMapping(nassText, shortsMap)
                    }
                    // ------------------------------------

                    val page = stmt.columnInt(2)
                    val part = parsePartValue(stmt, 3)
                    content = BookContent(id, nassText, page, part)
                }
            }
        }
        return content
    }

    fun getNextPage(
        bookId: Int,
        currentContentId: Int,
        archiveDbFile: File,
        isQuran: Boolean = false,
    ): BookContent? =
        getContent(bookId, currentContentId + 1, archiveDbFile, isQuran)
            ?: getContent(bookId, currentContentId + 2, archiveDbFile, isQuran)

    fun getPrevPage(
        bookId: Int,
        currentContentId: Int,
        archiveDbFile: File,
        isQuran: Boolean = false,
    ): BookContent? =
        getContent(bookId, currentContentId - 1, archiveDbFile, isQuran)
            ?: getContent(bookId, currentContentId - 2, archiveDbFile, isQuran)

    fun getTotalParts(
        bookId: Int,
        archiveDbFile: File,
    ): Int {
        if (!archiveDbFile.exists()) return 0
        var total = 0
        val tableName = "b$bookId"
        SQLiteDB(archiveDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            val querySQL = """
                SELECT MAX(
                    CAST(
                        CASE
                            WHEN instr(part, '-') > 0
                            THEN substr(part, 1, instr(part, '-') - 1)
                            ELSE part
                        END AS INTEGER
                    )
                )
                FROM $tableName
            """
            db.prepare(querySQL)?.use { stmt ->
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    total = stmt.columnInt(0)
                }
            }
        }
        return total
    }

    fun getPagesInPart(
        bookId: Int,
        part: Int,
        archiveDbFile: File,
    ): Int {
        if (!archiveDbFile.exists()) return 0
        var maxPage = 0
        val tableName = "b$bookId"
        SQLiteDB(archiveDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            val querySQL = "SELECT MAX(page) FROM $tableName WHERE part = ?"
            db.prepare(querySQL)?.use { stmt ->
                stmt.bindText(1, part.toString())
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    maxPage = stmt.columnInt(0)
                }
            }
        }
        return maxPage
    }

    fun getMinPagesInPart(
        bookId: Int,
        part: Int,
        archiveDbFile: File,
    ): Int {
        if (!archiveDbFile.exists()) return 0
        var minPage = 0
        val tableName = "b$bookId"
        SQLiteDB(archiveDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            val querySQL = "SELECT MIN(page) FROM $tableName WHERE part = ?"
            db.prepare(querySQL)?.use { stmt ->
                stmt.bindText(1, part.toString())
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    minPage = stmt.columnInt(0)
                }
            }
        }
        return minPage
    }

    fun getContent(
        bookId: Int,
        part: Int,
        page: Int,
        archiveDbFile: File,
    ): BookContent? {
        if (!archiveDbFile.exists()) return null
        var content: BookContent? = null
        val tableName = "b$bookId"
        SQLiteDB(archiveDbFile.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
            val querySQL =
                "SELECT id, nass, page, part FROM $tableName WHERE part = ? AND page = ? LIMIT 1"
            db.prepare(querySQL)?.use { stmt ->
                stmt.bindText(1, part.toString())
                stmt.bindInt(2, page)
                if (stmt.step() == SQLiteDB.SQLITE_ROW) {
                    val id = stmt.columnInt(0)
                    var nassText = ""
                    if (stmt.columnType(1) == SQLiteDB.SQLITE_BLOB) {
                        nassText = decompressBlob(stmt.columnBlobDirect(1))
                    } else {
                        nassText = stmt.columnText(1) ?: ""
                    }
                    val shortsMap = libraryDataManager.loadShortsForBook(bookId.toString())
                    if (!shortsMap.isEmpty) {
                        nassText = applyShortsMapping(nassText, shortsMap)
                    }
                    val partVal = parsePartValue(stmt, 3)
                    content = BookContent(id, nassText, page, partVal)
                }
            }
        }
        return content
    }
}

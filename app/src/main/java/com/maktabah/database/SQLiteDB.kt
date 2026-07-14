package com.maktabah.database

import java.io.Closeable

class SQLiteDB(path: String, flags: Int) : Closeable {
    private var dbPtr: Long = 0

    init {
        dbPtr = open(path, flags)
        if (dbPtr == 0L) {
            throw Exception("Failed to open database at $path")
        }
    }

    override fun close() {
        if (dbPtr != 0L) {
            close(dbPtr)
            dbPtr = 0
        }
    }

    fun prepare(sql: String): SQLiteStmt? {
        val stmtPtr = prepare(dbPtr, sql)
        return if (stmtPtr != 0L) SQLiteStmt(stmtPtr) else null
    }

    fun lastInsertRowId(): Long = lastInsertRowId(dbPtr)

    private external fun open(path: String, flags: Int): Long
    private external fun close(dbPtr: Long): Int
    private external fun prepare(dbPtr: Long, sql: String): Long
    private external fun lastInsertRowId(dbPtr: Long): Long

    companion object {
        init {
            System.loadLibrary("maktabah_sqlite")
        }

        const val SQLITE_OK = 0
        const val SQLITE_OPEN_READONLY = 0x00000001
        const val SQLITE_OPEN_READWRITE = 0x00000002
        const val SQLITE_OPEN_CREATE = 0x00000004
        const val SQLITE_OPEN_FULLMUTEX = 0x00010000

        const val SQLITE_ROW = 100
        const val SQLITE_DONE = 101

        const val SQLITE_INTEGER = 1
        const val SQLITE_FLOAT = 2
        const val SQLITE_TEXT = 3
        const val SQLITE_BLOB = 4
        const val SQLITE_NULL = 5
    }
}
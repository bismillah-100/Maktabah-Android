package com.maktabah.database

import java.io.Closeable

class SQLiteStmt(private var stmtPtr: Long) : Closeable {
    fun step(): Int = step(stmtPtr)

    fun reset(): Int = reset(stmtPtr)

    fun clearBindings(): Int = clearBindings(stmtPtr)

    fun bindInt(index: Int, value: Int) {
        bindInt(stmtPtr, index, value)
    }

    fun bindText(index: Int, value: String) {
        bindText(stmtPtr, index, value)
    }

    fun bindBlob(index: Int, value: ByteArray) {
        bindBlob(stmtPtr, index, value)
    }

    fun bindNull(index: Int) {
        bindNull(stmtPtr, index)
    }

    fun bindLong(index: Int, value: Long) {
        bindLong(stmtPtr, index, value)
    }

    fun columnText(col: Int): String? = columnText(stmtPtr, col)
    fun columnInt(col: Int): Int = columnInt(stmtPtr, col)
    fun columnLong(col: Int): Long = columnLong(stmtPtr, col)
    fun columnBlob(col: Int): ByteArray? = columnBlob(stmtPtr, col)
    fun columnBlobDirect(col: Int): java.nio.ByteBuffer? = columnBlobDirect(stmtPtr, col)
    fun columnType(col: Int): Int = columnType(stmtPtr, col)

    override fun close() {
        if (stmtPtr != 0L) {
            finalize(stmtPtr)
            stmtPtr = 0
        }
    }

    private external fun step(stmtPtr: Long): Int
    private external fun reset(stmtPtr: Long): Int
    private external fun clearBindings(stmtPtr: Long): Int
    private external fun finalize(stmtPtr: Long): Int
    private external fun bindInt(stmtPtr: Long, index: Int, value: Int): Int
    private external fun bindText(stmtPtr: Long, index: Int, value: String): Int
    private external fun bindBlob(stmtPtr: Long, index: Int, value: ByteArray): Int
    private external fun bindNull(stmtPtr: Long, index: Int): Int
    private external fun bindLong(stmtPtr: Long, index: Int, value: Long): Int
    private external fun columnText(stmtPtr: Long, col: Int): String?
    private external fun columnInt(stmtPtr: Long, col: Int): Int
    private external fun columnLong(stmtPtr: Long, col: Int): Long
    private external fun columnBlob(stmtPtr: Long, col: Int): ByteArray?
    private external fun columnBlobDirect(stmtPtr: Long, col: Int): java.nio.ByteBuffer?
    private external fun columnType(stmtPtr: Long, col: Int): Int
}
package com.maktabah.database

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ensures that database integration happens sequentially per archive or globally.
 * SQLite doesn't support concurrent writes to the same file.
 */
object BookArchiveSingleFlight {
    private val archiveLocks = mutableMapOf<Int, Mutex>()
    private val globalLock = Mutex()

    suspend fun <T> run(archiveId: Int, action: suspend () -> T): T {
        val mutex = synchronized(archiveLocks) {
            archiveLocks.getOrPut(archiveId) { Mutex() }
        }
        return mutex.withLock {
            action()
        }
    }

    /**
     * Use this if you want to ensure only one integration happens across all archives.
     */
    suspend fun <T> runGlobal(action: suspend () -> T): T {
        return globalLock.withLock {
            action()
        }
    }
}

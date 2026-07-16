package com.maktabah.database

import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import java.util.concurrent.ArrayBlockingQueue

/**
 * ⚡ Bolt Optimization: Zstd Context Pooling
 *
 * Creating a new ZstdDecompressCtx/ZstdCompressCtx for every byte array directly calls
 * into JNI to allocate a new Zstd_DCtx/Zstd_CCtx structure in C memory.
 * For operations that process thousands of rows (like full-text search indexing or sequential reading),
 * this introduces massive JNI boundary crossing overhead and unnecessary native memory allocations.
 *
 * Previous implementation used SoftReference which caused two issues:
 * 1. Allocated new SoftReference objects on every release (GC pressure).
 * 2. GC could clear the Kotlin object without calling .close(), leading to native memory leaks.
 *
 * Using an ArrayBlockingQueue maintains a bounded, lock-free pool.
 * It reuses native pointers efficiently and calls .close() gracefully on overflow.
 */
object ZstdContextPool {
    private const val MAX_POOL_SIZE = 16
    private val decompressCache = ArrayBlockingQueue<ZstdDecompressCtx>(MAX_POOL_SIZE)
    private val compressCache = ArrayBlockingQueue<ZstdCompressCtx>(MAX_POOL_SIZE)
    private val directBufferCache = ArrayBlockingQueue<java.nio.ByteBuffer>(MAX_POOL_SIZE)

    // Allocate 1MB direct buffer per thread (large enough for most compressed chunks)
    private const val MAX_DIRECT_BUFFER_SIZE = 1024 * 1024

    fun getDecompressCtx(): ZstdDecompressCtx {
        return decompressCache.poll() ?: ZstdDecompressCtx()
    }

    fun releaseDecompressCtx(ctx: ZstdDecompressCtx) {
        // Offer returns false if the queue is full, ensuring bounded memory
        if (!decompressCache.offer(ctx)) {
            ctx.close() // Safe disposal of C memory if pool is full
        }
    }

    fun getCompressCtx(): ZstdCompressCtx {
        return compressCache.poll() ?: ZstdCompressCtx().apply { setLevel(10) }
    }

    fun releaseCompressCtx(ctx: ZstdCompressCtx) {
        if (!compressCache.offer(ctx)) {
            ctx.close()
        }
    }

    fun getDirectBuffer(minCapacity: Int): java.nio.ByteBuffer {
        var buf = directBufferCache.poll()
        if (buf == null || buf.capacity() < minCapacity) {
            val sizeToAllocate = maxOf(MAX_DIRECT_BUFFER_SIZE, minCapacity)
            buf = java.nio.ByteBuffer.allocateDirect(sizeToAllocate)
        }
        buf.clear()
        return buf
    }

    fun releaseDirectBuffer(buf: java.nio.ByteBuffer) {
        if (buf.capacity() <= MAX_DIRECT_BUFFER_SIZE) {
            directBufferCache.offer(buf)
        }
    }
}

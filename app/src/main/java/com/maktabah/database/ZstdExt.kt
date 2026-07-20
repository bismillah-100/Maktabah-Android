package com.maktabah.database

import com.github.luben.zstd.Zstd
import java.nio.ByteBuffer

fun decompressBlob(blob: ByteBuffer?): String {
    if (blob == null) return ""
    val decompressedSize = Zstd.getFrameContentSize(blob).toInt()
    if (decompressedSize <= 0) return ""

    val ctx = ZstdContextPool.getDecompressCtx()
    return try {
        val dstBuf = ZstdContextPool.getDirectBuffer(decompressedSize)
        ctx.decompressDirectByteBuffer(dstBuf, 0, decompressedSize, blob, 0, blob.limit())
        val dst = ByteArray(decompressedSize)
        dstBuf.get(dst)
        ZstdContextPool.releaseDirectBuffer(dstBuf)
        String(dst)
    } finally {
        ZstdContextPool.releaseDecompressCtx(ctx)
    }
}

fun compressText(text: String?): ByteArray? {
    if (text == null) return null
    val bytes = text.toByteArray()
    val ctx = ZstdContextPool.getCompressCtx()
    return try {
        ctx.compress(bytes)
    } finally {
        ZstdContextPool.releaseCompressCtx(ctx)
    }
}

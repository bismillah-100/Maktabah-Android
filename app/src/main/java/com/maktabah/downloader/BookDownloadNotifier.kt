package com.maktabah.downloader

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object BookDownloadNotifier {
    private val _downloadedBookEvents = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val downloadedBookEvents: SharedFlow<Int> = _downloadedBookEvents

    fun notifyBookDownloaded(bookId: Int) {
        _downloadedBookEvents.tryEmit(bookId)
    }
}

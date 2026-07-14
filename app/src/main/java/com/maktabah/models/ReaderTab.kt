package com.maktabah.models

import com.maktabah.ui.reader.ReaderViewModel
import java.util.UUID

data class ReaderTab(
    val id: String = UUID.randomUUID().toString(),
    val bookId: Int,
    val bookName: String,
    val archivePath: String,
    val viewModel: ReaderViewModel,
    var savedScrollY: Int = 0,
)

data class FlashTarget(
    val annotationId: String? = null,
    val query: String? = null,
    val loc: Int? = null,
    val len: Int? = null
)
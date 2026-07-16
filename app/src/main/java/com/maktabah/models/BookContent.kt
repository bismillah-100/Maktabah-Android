package com.maktabah.models

import androidx.compose.runtime.Immutable
import java.util.UUID


@Immutable
data class TOC(
    val title: String,
    val level: Int,
    val sub: Int,
    val id: Int,
)



data class TOCNode(
    val id: Int,
    val title: String,
    val level: Int,
    val sub: Int,
    var endID: Int = Int.MAX_VALUE,
    val children: MutableList<TOCNode> = mutableListOf(),
    val uuid: String = UUID.randomUUID().toString(),
) {
    constructor(toc: TOC) : this(id = toc.id, title = toc.title, level = toc.level, sub = toc.sub)
}


@Immutable
data class BookContent(
    val id: Int,
    val nass: String,
    val page: Int,
    val part: Int,
)


@Immutable
data class VisibleTOCNode(
    val node: TOCNode,
    val depth: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val isSelected: Boolean,
)

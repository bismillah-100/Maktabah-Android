package com.maktabah.models

sealed class AnnotationGroup {
    abstract val key: String
    abstract val title: String
    abstract val size: Int
    abstract val annotations: List<Annotation>

    data class BookGroup(
        val bkId: Int,
        override val title: String,
        override val annotations: List<Annotation>,
    ) : AnnotationGroup() {
        override val key: String = "book_$bkId"
        override val size: Int = annotations.size
    }

    data class TagGroup(
        val tagName: String,
        override val title: String,
        override val annotations: List<Annotation>,
    ) : AnnotationGroup() {
        override val key: String = "tag_$tagName"
        override val size: Int = annotations.size
    }
}

enum class AnnotationGroupingMode {
    BOOK,
    TAG,
}

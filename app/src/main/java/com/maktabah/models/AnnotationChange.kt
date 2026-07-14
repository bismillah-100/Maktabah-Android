package com.maktabah.models

sealed class AnnotationChange {
    data class Upsert(
        val annotation: Annotation,
        val fromSync: Boolean = false,
    ) : AnnotationChange()

    data class Delete(
        val id: Long,
        val fromSync: Boolean = false,
    ) : AnnotationChange()

    object ReloadAll : AnnotationChange()
}
package com.maktabah.models

data class BundleBookIndexEntry(
    val bkid: Int,
    val filename: String,
    val release: String,
    val sizeZst: Long?,
    val authno: Int = 0,
)
package com.maktabah.models

data class ShortsMapping(
    val map: Map<String, String>,
    val sortedKeys: List<String>
) {
    val isEmpty: Boolean get() = map.isEmpty()
}
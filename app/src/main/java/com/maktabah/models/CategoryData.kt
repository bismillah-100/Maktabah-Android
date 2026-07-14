package com.maktabah.models

data class CategoryData(
    val id: Int,
    val name: String,
    val level: Int,
    val order: Int,
    val children: MutableList<Any> = mutableListOf(), // Contains subcategories and books
)
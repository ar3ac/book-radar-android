package com.ar3ac.bookradar.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val isbn: String = "",
    val price: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val localCoverPath: String? = null,
    val giuntiUrl: String = "",
    val amazonUrl: String = "",
    val goodreadsUrl: String = "",
    val badge: String = "✨ Novità"
)

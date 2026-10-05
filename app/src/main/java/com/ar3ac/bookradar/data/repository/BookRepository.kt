package com.ar3ac.bookradar.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ar3ac.bookradar.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class BookRepository private constructor(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("book_radar_prefs", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cacheFile = File(context.filesDir, "books_cache.json")
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        @Volatile
        private var instance: BookRepository? = null

        fun getInstance(context: Context): BookRepository {
            return instance ?: synchronized(this) {
                instance ?: BookRepository(context.applicationContext).also { instance = it }
            }
        }

        private const val GIUNTI_URL = "https://giuntialpunto.it/collections/novita-da-non-perdere/products.json?limit=30"
        private const val PREF_CURRENT_INDEX = "pref_current_index"
    }

    suspend fun refreshBooks(): Result<List<Book>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GIUNTI_URL)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP Error: ${response.code}"))
            }

            val bodyString = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response body"))
            val jsonRoot = json.parseToJsonElement(bodyString).jsonObject
            val products = jsonRoot["products"]?.jsonArray ?: JsonArray(emptyList())

            val books = mutableListOf<Book>()
            for (pElem in products) {
                val p = pElem.jsonObject
                val id = p["id"]?.jsonPrimitive?.content ?: continue
                val title = p["title"]?.jsonPrimitive?.content ?: "Titolo Sconosciuto"
                val handle = p["handle"]?.jsonPrimitive?.content ?: ""
                val tags = p["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val bodyHtml = p["body_html"]?.jsonPrimitive?.content ?: ""

                // Estrai autore
                val author = parseGiuntiAuthors(tags, handle)

                // Estrai ISBN (13 cifre da handle o tag)
                val isbn = parseIsbn(handle, tags)

                // Estrai prezzo
                val variants = p["variants"]?.jsonArray
                var priceStr = ""
                if (!variants.isNullOrEmpty()) {
                    val firstVar = variants[0].jsonObject
                    val rawPrice = firstVar["price"]?.jsonPrimitive?.content ?: ""
                    if (rawPrice.isNotEmpty()) {
                        priceStr = "€ $rawPrice"
                    }
                }

                // Estrai immagine
                val images = p["images"]?.jsonArray
                val imageUrl = if (!images.isNullOrEmpty()) {
                    images[0].jsonObject["src"]?.jsonPrimitive?.content ?: ""
                } else ""

                val giuntiUrl = "https://giuntialpunto.it/products/$handle"
                val amazonUrl = if (isbn.isNotEmpty()) {
                    "https://www.amazon.it/s?k=$isbn"
                } else {
                    "https://www.amazon.it/s?k=" + URLEncoder.encode("$title $author", "UTF-8")
                }

                val cleanQuery = cleanGoodreadsQuery(title, author)
                val goodreadsUrl = "https://www.goodreads.com/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8")
                val description = cleanHtml(bodyHtml)

                // Download cover in locale
                var localCoverPath: String? = null
                if (imageUrl.isNotEmpty()) {
                    val coverFile = File(coversDir, "cover_${id}.jpg")
                    if (!coverFile.exists() || coverFile.length() == 0L) {
                        try {
                            downloadFile(imageUrl, coverFile)
                        } catch (_: Exception) {}
                    }
                    if (coverFile.exists() && coverFile.length() > 0) {
                        localCoverPath = coverFile.absolutePath
                    }
                }

                books.add(
                    Book(
                        id = "giunti_$id",
                        title = title,
                        author = author,
                        isbn = isbn,
                        price = priceStr,
                        description = description,
                        imageUrl = imageUrl,
                        localCoverPath = localCoverPath,
                        giuntiUrl = giuntiUrl,
                        amazonUrl = amazonUrl,
                        goodreadsUrl = goodreadsUrl,
                        badge = "✨ Novità"
                    )
                )
            }

            if (books.isNotEmpty()) {
                val booksJson = json.encodeToString(books)
                cacheFile.writeText(booksJson)
            }

            Result.success(books)
        } catch (e: Exception) {
            val cached = getCachedBooks()
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    fun getCachedBooks(): List<Book> {
        if (!cacheFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<Book>>(cacheFile.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getCurrentBook(): Book? {
        val books = getCachedBooks()
        if (books.isEmpty()) return null
        val idx = getCurrentIndex().coerceIn(0, books.size - 1)
        return books[idx]
    }

    fun getCurrentIndex(): Int {
        return prefs.getInt(PREF_CURRENT_INDEX, 0)
    }

    fun setCurrentIndex(index: Int) {
        val count = getCachedBooks().size
        if (count == 0) return
        val validIndex = (index % count + count) % count
        prefs.edit().putInt(PREF_CURRENT_INDEX, validIndex).apply()
    }

    fun nextBook(): Book? {
        val books = getCachedBooks()
        if (books.isEmpty()) return null
        val nextIdx = (getCurrentIndex() + 1) % books.size
        setCurrentIndex(nextIdx)
        return books[nextIdx]
    }

    fun prevBook(): Book? {
        val books = getCachedBooks()
        if (books.isEmpty()) return null
        val prevIdx = (getCurrentIndex() - 1 + books.size) % books.size
        setCurrentIndex(prevIdx)
        return books[prevIdx]
    }

    fun getCoverBitmap(book: Book, reqWidth: Int = 300, reqHeight: Int = 450): Bitmap? {
        val path = book.localCoverPath ?: return null
        val file = File(path)
        if (!file.exists()) return null

        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (e: Exception) {
            null
        }
    }

    private fun downloadFile(url: String, destFile: File) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            response.body?.byteStream()?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun parseGiuntiAuthors(tags: List<String>, handle: String): String {
        val authors = mutableListOf<String>()
        for (t in tags) {
            if (t.startsWith("AUTORE::")) {
                val surname = t.removePrefix("AUTORE::").lowercase()
                var found = false
                for (cand in tags) {
                    val candLower = cand.lowercase()
                    if (candLower.startsWith("${surname}_")) {
                        val namePart = candLower.removePrefix("${surname}_").replace("_", " ").split(" ")
                            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                        val full = "$namePart ${surname.replaceFirstChar { it.uppercase() }}"
                        if (!authors.contains(full)) authors.add(full)
                        found = true
                        break
                    }
                }
                if (!found) {
                    authors.add(surname.replaceFirstChar { it.uppercase() })
                }
            }
        }
        if (authors.isNotEmpty()) return authors.joinToString(", ")

        val m = Pattern.compile("(\\d{13})").matcher(handle)
        if (m.find()) {
            val beforeIsbn = handle.substring(0, m.start()).trimEnd('-')
            val parts = beforeIsbn.split("-")
            if (parts.size >= 2) {
                val surname = parts.last().replaceFirstChar { it.uppercase() }
                val name = parts.dropLast(1).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                return "$name $surname".trim()
            }
        }
        return "Autore Sconosciuto"
    }

    private fun parseIsbn(handle: String, tags: List<String>): String {
        val m = Pattern.compile("(\\d{13})").matcher(handle)
        if (m.find()) return m.group(1) ?: ""

        for (t in tags) {
            val m2 = Pattern.compile("^(\\d{13})$").matcher(t)
            if (m2.find()) return m2.group(1) ?: ""
        }
        return ""
    }

    private fun cleanGoodreadsQuery(title: String, author: String): String {
        var t = title
        val patterns = listOf(
            "(?i)\\bediz\\.?\\s+italiana\\b",
            "(?i)\\bediz\\.?\\s+a\\s+colori\\b",
            "(?i)\\bedizione\\s+italiana\\b",
            "(?i)\\blimited\\s+edition\\b",
            "(?i)\\bcon\\s+booklet\\b.*",
            "(?i)\\bcon\\s+illustration\\b.*",
            "(?i)\\bcon\\s+gadget\\b.*",
            "(?i)\\(vol\\.?\\s*\\d+\\)",
            "(?i)\\(vol\\b.*?\\)"
        )
        for (pat in patterns) {
            t = t.replace(Regex(pat), "")
        }
        for (sep in listOf(":", " - ", " – ", ". ")) {
            if (t.contains(sep)) {
                val parts = t.split(sep)
                if (parts[0].trim().length >= 3) {
                    t = parts[0]
                    break
                }
            }
        }
        t = t.replace(Regex("[.,;\"“”]+"), " ").replace(Regex("\\s+"), " ").trim()
        val cleanAuthor = if (author.contains(",")) author.split(",")[0].trim() else author.trim()

        return if (cleanAuthor.isNotEmpty() && cleanAuthor != "Autore Sconosciuto") {
            "$t $cleanAuthor".trim()
        } else {
            t
        }
    }

    private fun cleanHtml(rawHtml: String): String {
        if (rawHtml.isEmpty()) return ""
        return rawHtml.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

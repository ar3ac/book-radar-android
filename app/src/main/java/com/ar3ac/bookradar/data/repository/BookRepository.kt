package com.ar3ac.bookradar.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.ar3ac.bookradar.data.model.Book
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
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
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        @Volatile
        private var instance: BookRepository? = null

        fun getInstance(context: Context): BookRepository {
            return instance ?: synchronized(this) {
                instance ?: BookRepository(context.applicationContext).also { instance = it }
            }
        }

        private const val AMAZON_BESTSELLERS_URL = "https://www.amazon.it/gp/bestsellers/books"
        private const val USER_AGENT_BROWSER = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PREF_CURRENT_INDEX = "pref_current_index"
    }

    suspend fun refreshBooks(): Result<List<Book>> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("BookRepository", "Inizio refreshBooks()...")
            // Scarica in parallelo Bestseller Amazon e Novità Giunti
            val amzDeferred = async { fetchAmazonBestsellers(15) }
            val giuntiDeferred = async { fetchGiunti(20) }

            val amzBooks = amzDeferred.await()
            val giuntiBooks = giuntiDeferred.await()
            android.util.Log.d("BookRepository", "Scaricati ${amzBooks.size} Amazon, ${giuntiBooks.size} Giunti")

            // Interleave (alternanza) con deduplicazione su titolo normalizzato
            val seenTitles = mutableSetOf<String>()
            val mixedList = mutableListOf<Book>()
            val maxLen = maxOf(amzBooks.size, giuntiBooks.size)

            for (i in 0 until maxLen) {
                if (i < amzBooks.size) {
                    val b = amzBooks[i]
                    val norm = normalizeTitle(b.title)
                    if (seenTitles.add(norm)) {
                        mixedList.add(b)
                    }
                }
                if (i < giuntiBooks.size) {
                    val b = giuntiBooks[i]
                    val norm = normalizeTitle(b.title)
                    if (seenTitles.add(norm)) {
                        mixedList.add(b)
                    }
                }
            }

            if (mixedList.isNotEmpty()) {
                val booksJson = json.encodeToString(mixedList)
                cacheFile.writeText(booksJson)
                Result.success(mixedList)
            } else {
                val cached = getCachedBooks()
                if (cached.isNotEmpty()) {
                    Result.success(cached)
                } else {
                    Result.failure(Exception("Nessun libro recuperato da Amazon né da Giunti"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BookRepository", "Errore in refreshBooks", e)
            val cached = getCachedBooks()
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun fetchAmazonBestsellers(limit: Int = 15): List<Book> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(AMAZON_BESTSELLERS_URL)
                .header("User-Agent", USER_AGENT_BROWSER)
                .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val html = response.body?.string() ?: return@withContext emptyList()
            val doc = Jsoup.parse(html)
            val items = doc.select("#gridItemRoot")
            val books = mutableListOf<Book>()

            for ((index, item) in items.take(limit).withIndex()) {
                val idx = index + 1
                val rankElem = item.selectFirst(".zg-bdg-text, span[class*='zg-badge'], .zg-bdg-body")
                val rank = rankElem?.text()?.trim() ?: "#$idx"

                val img = item.selectFirst("img")
                val imgSrc = img?.attr("src") ?: ""

                val titleElem = item.selectFirst("div[class*='line-clamp-1'], div[class*='line-clamp-2'], .p13n-sc-truncate")
                val title = titleElem?.text()?.trim() ?: img?.attr("alt")?.trim() ?: ""
                if (title.isEmpty()) continue

                var author = "Autore Sconosciuto"
                for (r in item.select(".a-row.a-size-small")) {
                    val txt = r.text().trim()
                    if (txt.isEmpty()) continue
                    val txtLower = txt.lowercase()
                    if (txtLower.contains("stelle") || txtLower.contains("formati")) continue
                    if (listOf("copertina", "flessibile", "rigida", "kindle", "audiolibro").any { txtLower.contains(it) }) continue
                    author = txt
                    break
                }

                val priceElem = item.selectFirst("span[class*='price'], .p13n-sc-price")
                val price = priceElem?.text()?.trim() ?: ""

                val link = item.selectFirst("a.a-link-normal[href*='/dp/']")
                val href = link?.attr("href") ?: ""
                val asinMatch = Regex("/dp/([A-Z0-9]{10})").find(href)
                val asin = asinMatch?.groupValues?.get(1) ?: "bestseller_$idx"

                val amazonUrl = if (asinMatch != null) {
                    "https://www.amazon.it/dp/$asin"
                } else if (href.startsWith("/")) {
                    "https://www.amazon.it$href"
                } else {
                    "https://www.amazon.it/gp/bestsellers/books"
                }

                val giuntiUrl = "https://giuntialpunto.it/search?q=" + URLEncoder.encode(title, "UTF-8")
                val isIsbn10 = asin.matches(Regex("^\\d{9}[\\dXx]$"))
                val isbn = if (isIsbn10) asin.uppercase() else ""
                val cleanQuery = cleanGoodreadsQuery(title, author)
                val goodreadsUrl = "https://www.goodreads.com/search?q=" + URLEncoder.encode(cleanQuery, "UTF-8")

                // Risoluzione nativa alta qualità per copertina
                val cleanImgUrl = if (imgSrc.isNotEmpty()) imgSrc.replace(Regex("\\._[^.]+\\.jpg$"), ".jpg") else ""
                val targetDownloadUrl = cleanImgUrl.ifEmpty { imgSrc }

                var localCoverPath: String? = null
                if (targetDownloadUrl.isNotEmpty()) {
                    val coverFile = File(coversDir, "amz_${asin}.jpg")
                    if (!coverFile.exists() || coverFile.length() == 0L) {
                        try {
                            downloadFile(targetDownloadUrl, coverFile)
                        } catch (_: Exception) {
                            if (imgSrc.isNotEmpty() && imgSrc != targetDownloadUrl) {
                                try {
                                    downloadFile(imgSrc, coverFile)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    if (coverFile.exists() && coverFile.length() > 0) {
                        localCoverPath = coverFile.absolutePath
                    }
                }

                books.add(
                    Book(
                        id = "amazon_$asin",
                        title = title,
                        author = author,
                        isbn = isbn,
                        price = price,
                        description = "Classifica Bestseller Amazon: posizione $rank.",
                        imageUrl = targetDownloadUrl,
                        localCoverPath = localCoverPath,
                        giuntiUrl = giuntiUrl,
                        amazonUrl = amazonUrl,
                        goodreadsUrl = goodreadsUrl,
                        badge = "🏆 $rank Amazon"
                    )
                )
            }

            // Arricchimento asincrono sinossi in parallelo
            coroutineScope {
                val deferred = books.map { book ->
                    async {
                        val asin = book.id.removePrefix("amazon_")
                        val fullDesc = withTimeoutOrNull(4000L) { fetchAmazonSynopsis(asin) }
                        if (!fullDesc.isNullOrEmpty()) {
                            book.copy(description = fullDesc)
                        } else {
                            book
                        }
                    }
                }
                deferred.awaitAll()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchAmazonSynopsis(asin: String): String? = withContext(Dispatchers.IO) {
        if (asin.isEmpty() || asin.startsWith("bestseller_")) return@withContext null
        try {
            val req = Request.Builder()
                .url("https://www.amazon.it/dp/$asin")
                .header("User-Agent", USER_AGENT_BROWSER)
                .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val html = resp.body?.string() ?: return@withContext null
                val doc = Jsoup.parse(html)
                val descElem = doc.selectFirst("#bookDescription_feature_div, #productDescription")
                val text = descElem?.text()?.trim()
                if (!text.isNullOrEmpty() && text.length > 20) {
                    return@withContext text
                }
            }
        } catch (_: Exception) {}
        null
    }

    suspend fun fetchGiunti(limit: Int = 20): List<Book> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://giuntialpunto.it/collections/novita-da-non-perdere/products.json?limit=$limit")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val bodyString = response.body?.string() ?: return@withContext emptyList()
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

                val author = parseGiuntiAuthors(tags, handle)
                val isbn = parseIsbn(handle, tags)

                val variants = p["variants"]?.jsonArray
                var priceStr = ""
                if (!variants.isNullOrEmpty()) {
                    val firstVar = variants[0].jsonObject
                    val rawPrice = firstVar["price"]?.jsonPrimitive?.content ?: ""
                    if (rawPrice.isNotEmpty()) {
                        priceStr = "€ $rawPrice"
                    }
                }

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
            books
        } catch (e: Exception) {
            emptyList()
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

    private fun normalizeTitle(title: String): String {
        var t = title.lowercase()
        t = t.replace(Regex("(?i)\\bediz\\.?\\s+italiana\\b"), "")
        t = t.replace(Regex("(?i)\\bediz\\.?\\s+a\\s+colori\\b"), "")
        t = t.replace(Regex("(?i)\\blimited\\s+edition\\b"), "")
        t = t.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
        return t.split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
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

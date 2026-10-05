package com.ar3ac.bookradar.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil.load
import com.ar3ac.bookradar.data.model.Book
import com.ar3ac.bookradar.data.repository.BookRepository
import com.ar3ac.bookradar.data.worker.BookSyncWorker
import com.ar3ac.bookradar.databinding.ActivityMainBinding
import com.ar3ac.bookradar.widget.BookRadarWidgetProvider
import com.ar3ac.bookradar.widget.WidgetReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: BookRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = BookRepository.getInstance(this)

        setupWorkManager()
        setupListeners()
        loadCurrentBookUI()

        // Avvia rotazione automatica del widget se abilitata
        com.ar3ac.bookradar.widget.WidgetAutoCycleManager.scheduleNextTick(this)

        // Controlla se la cache è vuota, se sì avvia il primo sync
        if (repo.getCachedBooks().isEmpty()) {
            syncFeed()
        }
    }

    private fun setupWorkManager() {
        // Schedula sincronizzazione periodica ogni 4 ore
        val syncRequest = PeriodicWorkRequestBuilder<BookSyncWorker>(4, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "BookRadarPeriodicSync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun setupListeners() {
        binding.btnPrev.setOnClickListener {
            val book = repo.prevBook()
            displayBook(book)
            BookRadarWidgetProvider.updateAllWidgets(this)
        }

        binding.btnNext.setOnClickListener {
            val book = repo.nextBook()
            displayBook(book)
            BookRadarWidgetProvider.updateAllWidgets(this)
        }

        binding.btnGoodreads.setOnClickListener {
            val book = repo.getCurrentBook() ?: return@setOnClickListener
            val url = book.goodreadsUrl.ifEmpty { "https://www.goodreads.com" }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        binding.btnAmazon.setOnClickListener {
            val book = repo.getCurrentBook() ?: return@setOnClickListener
            val url = book.amazonUrl.ifEmpty { book.giuntiUrl.ifEmpty { "https://www.amazon.it" } }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        binding.btnSyncNow.setOnClickListener {
            syncFeed()
        }

        binding.btnAddWidget.setOnClickListener {
            pinWidgetToHomeScreen()
        }
    }

    private fun loadCurrentBookUI() {
        val book = repo.getCurrentBook()
        displayBook(book)
    }

    private fun displayBook(book: Book?) {
        if (book == null) {
            binding.mainBookTitle.text = "Nessun libro in memoria"
            binding.mainBookAuthor.text = "Tocca 'Sincronizza Feed' per iniziare"
            binding.mainBookDescription.text = ""
            binding.mainBookPrice.text = ""
            return
        }

        binding.mainBookTitle.text = book.title
        binding.mainBookAuthor.text = book.author
        binding.mainBookBadge.text = book.badge
        binding.mainBookPrice.text = book.price.ifEmpty { "N/D" }
        binding.mainBookDescription.text = book.description.ifEmpty { "Nessuna sinossi disponibile." }

        if (!book.localCoverPath.isNullOrEmpty() && File(book.localCoverPath).exists()) {
            binding.mainBookCover.load(File(book.localCoverPath))
        } else if (book.imageUrl.isNotEmpty()) {
            binding.mainBookCover.load(book.imageUrl)
        }
    }

    private fun syncFeed() {
        binding.btnSyncNow.isEnabled = false
        binding.btnSyncNow.text = "⏳ Sincronizzazione in corso..."

        lifecycleScope.launch {
            val result = repo.refreshBooks()
            withContext(Dispatchers.Main) {
                binding.btnSyncNow.isEnabled = true
                binding.btnSyncNow.text = "🔄 Sincronizza Feed Adesso"

                if (result.isSuccess) {
                    val books = result.getOrNull() ?: emptyList()
                    Toast.makeText(this@MainActivity, "Sincronizzati ${books.size} libri!", Toast.LENGTH_SHORT).show()
                    loadCurrentBookUI()
                    BookRadarWidgetProvider.updateAllWidgets(this@MainActivity)
                } else {
                    Toast.makeText(this@MainActivity, "Errore sincronizzazione: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pinWidgetToHomeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = getSystemService(AppWidgetManager::class.java)
            val myProvider = ComponentName(this, BookRadarWidgetProvider::class.java)

            if (appWidgetManager.isRequestPinAppWidgetSupported) {
                val successCallback = PendingIntent.getBroadcast(
                    this,
                    0,
                    Intent(this, WidgetReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                appWidgetManager.requestPinAppWidget(myProvider, null, successCallback)
            } else {
                Toast.makeText(this, "Il tuo launcher non supporta l'aggiunta automatica. Trascina il widget dalla home!", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Tieni premuto sulla home screen e seleziona 'Widget' per aggiungere Book Radar.", Toast.LENGTH_LONG).show()
        }
    }
}

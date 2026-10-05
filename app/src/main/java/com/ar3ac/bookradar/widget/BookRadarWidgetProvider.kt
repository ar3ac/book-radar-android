package com.ar3ac.bookradar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.widget.RemoteViews
import com.ar3ac.bookradar.R
import com.ar3ac.bookradar.data.repository.BookRepository
import com.ar3ac.bookradar.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BookRadarWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        CoroutineScope(Dispatchers.IO).launch {
            val repo = BookRepository.getInstance(context)
            if (repo.getCachedBooks().isEmpty()) {
                repo.refreshBooks()
            }
            for (id in appWidgetIds) {
                updateWidgetUI(context, appWidgetManager, id)
            }
        }
    }

    companion object {
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, BookRadarWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

            for (appWidgetId in appWidgetIds) {
                updateWidgetUI(context, appWidgetManager, appWidgetId)
            }
        }

        fun updateWidgetUI(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val repo = BookRepository.getInstance(context)
            val book = repo.getCurrentBook()

            val views = RemoteViews(context.packageName, R.layout.widget_book_radar)

            if (book != null) {
                views.setTextViewText(R.id.widget_book_title, book.title)
                views.setTextViewText(R.id.widget_book_author, book.author)
                views.setTextViewText(R.id.widget_book_badge, book.badge)

                if (book.price.isNotEmpty()) {
                    views.setViewVisibility(R.id.widget_book_price, android.view.View.VISIBLE)
                    views.setTextViewText(R.id.widget_book_price, book.price)
                } else {
                    views.setViewVisibility(R.id.widget_book_price, android.view.View.GONE)
                }

                // Carica copertina e applica angoli arrotondati
                val coverBmp = repo.getCoverBitmap(book, 450, 650)
                if (coverBmp != null) {
                    val roundedBmp = getRoundedCornerBitmap(coverBmp, 20f)
                    views.setImageViewBitmap(R.id.widget_book_cover, roundedBmp)
                } else {
                    views.setImageViewResource(R.id.widget_book_cover, R.drawable.ic_book_placeholder)
                }

                // Tasto Goodreads (Apre scheda libro con 1 clic)
                val grUrl = book.goodreadsUrl.ifEmpty { "https://www.goodreads.com" }
                val grIntent = Intent(Intent.ACTION_VIEW, Uri.parse(grUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val grPending = PendingIntent.getActivity(context, 10, grIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(R.id.widget_btn_goodreads, grPending)

                // Tasto Amazon / Store
                val amzUrl = book.amazonUrl.ifEmpty { book.giuntiUrl.ifEmpty { "https://www.amazon.it" } }
                val amzIntent = Intent(Intent.ACTION_VIEW, Uri.parse(amzUrl)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val amzPending = PendingIntent.getActivity(context, 11, amzIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(R.id.widget_btn_amazon, amzPending)

                // Clic su copertina o titolo: Apre MainActivity
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("EXTRA_BOOK_ID", book.id)
                }
                val mainPending = PendingIntent.getActivity(context, 12, mainIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(R.id.widget_book_cover, mainPending)
                views.setOnClickPendingIntent(R.id.widget_book_title, mainPending)

            } else {
                views.setTextViewText(R.id.widget_book_title, "Nessun libro sincronizzato")
                views.setTextViewText(R.id.widget_book_author, "Tocca 🔄 per aggiornare")
                views.setTextViewText(R.id.widget_book_badge, "Book Radar")
                views.setViewVisibility(R.id.widget_book_price, android.view.View.GONE)
                views.setImageViewResource(R.id.widget_book_cover, R.drawable.ic_book_placeholder)
            }

            // Tasto Precedente
            val prevIntent = Intent(context, WidgetReceiver::class.java).apply {
                action = WidgetReceiver.ACTION_PREV
            }
            views.setOnClickPendingIntent(R.id.widget_btn_prev, PendingIntent.getBroadcast(context, 1, prevIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            // Tasto Successivo
            val nextIntent = Intent(context, WidgetReceiver::class.java).apply {
                action = WidgetReceiver.ACTION_NEXT
            }
            views.setOnClickPendingIntent(R.id.widget_btn_next, PendingIntent.getBroadcast(context, 2, nextIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            // Tasto Sincronizza
            val syncIntent = Intent(context, WidgetReceiver::class.java).apply {
                action = WidgetReceiver.ACTION_SYNC
            }
            views.setOnClickPendingIntent(R.id.widget_btn_sync, PendingIntent.getBroadcast(context, 3, syncIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun getRoundedCornerBitmap(bitmap: Bitmap, pixels: Float): Bitmap {
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply {
                isAntiAlias = true
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawRoundRect(rect, pixels, pixels, paint)
            return output
        }
    }
}

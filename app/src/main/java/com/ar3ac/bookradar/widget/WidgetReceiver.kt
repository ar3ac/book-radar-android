package com.ar3ac.bookradar.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ar3ac.bookradar.data.repository.BookRepository
import com.ar3ac.bookradar.data.worker.BookSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WidgetReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PREV = "com.ar3ac.bookradar.ACTION_PREV"
        const val ACTION_NEXT = "com.ar3ac.bookradar.ACTION_NEXT"
        const val ACTION_SYNC = "com.ar3ac.bookradar.ACTION_SYNC"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val repo = BookRepository.getInstance(context)

        when (action) {
            ACTION_PREV -> {
                repo.prevBook()
                BookRadarWidgetProvider.updateAllWidgets(context)
            }
            ACTION_NEXT -> {
                repo.nextBook()
                BookRadarWidgetProvider.updateAllWidgets(context)
            }
            ACTION_SYNC -> {
                val syncWork = OneTimeWorkRequestBuilder<BookSyncWorker>().build()
                WorkManager.getInstance(context).enqueue(syncWork)

                // Avvia anche un refresh asincrono immediato
                CoroutineScope(Dispatchers.IO).launch {
                    repo.refreshBooks()
                    BookRadarWidgetProvider.updateAllWidgets(context)
                }
            }
        }
    }
}

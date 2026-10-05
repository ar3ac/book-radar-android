package com.ar3ac.bookradar.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ar3ac.bookradar.data.repository.BookRepository
import com.ar3ac.bookradar.widget.BookRadarWidgetProvider

class BookSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repo = BookRepository.getInstance(applicationContext)
        val result = repo.refreshBooks()

        if (result.isSuccess) {
            BookRadarWidgetProvider.updateAllWidgets(applicationContext)
            return Result.success()
        }

        return Result.retry()
    }
}

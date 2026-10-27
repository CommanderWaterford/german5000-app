package com.vocab5000.core.billing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Implemented by the Application so workers can reach the shared repository. */
interface EntitlementRepositoryProvider {
    val entitlementRepository: EntitlementRepository
}

/** Durable acknowledgement retry; Play requires acknowledgement within three days. */
class AcknowledgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val provider = applicationContext as? EntitlementRepositoryProvider ?: return Result.failure()
        return if (provider.entitlementRepository.retryAcknowledgement()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "acknowledge-premium"

        fun scheduler(context: Context) = AcknowledgementScheduler {
            val request = OneTimeWorkRequestBuilder<AcknowledgeWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

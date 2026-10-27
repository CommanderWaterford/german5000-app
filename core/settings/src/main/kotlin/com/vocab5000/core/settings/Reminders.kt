package com.vocab5000.core.settings

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vocab5000.core.learning.ReminderCalculator
import com.vocab5000.core.model.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** App-provided check: is there due or new eligible work, and is today's goal still open? */
fun interface ReminderEligibility {
    suspend fun shouldRemind(): Boolean
}

/** Implemented by the Application so the worker and receiver reach shared objects. */
interface ReminderDependencies {
    val reminderScheduler: ReminderScheduler
    val reminderEligibility: ReminderEligibility
    val preferencesRepository: PreferencesRepository
}

/**
 * Schedules at most one reminder per local date as a unique one-time WorkManager request
 * computed for the next selected local time (spec §11). Timing is inexact by design;
 * no exact-alarm permission is used.
 */
class ReminderScheduler(
    private val context: Context,
    private val preferences: PreferencesRepository,
    private val clock: AppClock,
) {
    suspend fun reschedule() {
        val prefs = preferences.current()
        val work = WorkManager.getInstance(context)
        if (!prefs.reminderEnabled) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val next = ReminderCalculator.nextOccurrence(
            now = Instant.ofEpochMilli(clock.nowMillis()),
            zone = clock.zone(),
            time = prefs.reminderTime,
            days = prefs.reminderDays,
            lastNotifiedDate = prefs.lastNotifiedLocalDate?.let(LocalDate::parse),
        )
        if (next == null) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val delay = (next.toEpochMilli() - clock.nowMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "daily-reminder"
        const val CHANNEL_ID = "study_reminders"
        const val NOTIFICATION_ID = 5000
        const val EXTRA_OPEN_TODAY = "open_today"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Study reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "One optional reminder per day when German words are ready"
                },
            )
        }

        fun canPostNotifications(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = applicationContext as? ReminderDependencies ?: return Result.failure()
        val prefs = deps.preferencesRepository.current()
        val today = LocalDate.now().toString()
        // Re-check eligibility in the worker to prevent duplicates and stale reminders.
        if (prefs.reminderEnabled &&
            prefs.lastNotifiedLocalDate != today &&
            LocalDate.now().dayOfWeek in prefs.reminderDays &&
            deps.reminderEligibility.shouldRemind() &&
            ReminderScheduler.canPostNotifications(applicationContext)
        ) {
            post()
            deps.preferencesRepository.setLastNotified(today)
        }
        deps.reminderScheduler.reschedule()
        return Result.success()
    }

    @Suppress("MissingPermission") // checked by canPostNotifications
    private fun post() {
        ReminderScheduler.ensureChannel(applicationContext)
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ReminderScheduler.EXTRA_OPEN_TODAY, true)
            }
        val pending = launch?.let {
            PendingIntent.getActivity(applicationContext, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        // Generic text: no vocabulary or personal progress on the lock screen.
        val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(applicationContext.applicationInfo.icon)
            .setContentTitle("Time for German")
            .setContentText("A few German words are ready to review.")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(ReminderScheduler.NOTIFICATION_ID, notification)
    }
}

/** Recomputes the next reminder after time zone or clock changes. */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED && intent.action != Intent.ACTION_TIME_CHANGED) return
        val deps = context.applicationContext as? ReminderDependencies ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                deps.reminderScheduler.reschedule()
            } finally {
                pending.finish()
            }
        }
    }
}

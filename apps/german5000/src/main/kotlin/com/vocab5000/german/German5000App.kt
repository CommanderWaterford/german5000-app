package com.vocab5000.german

import android.app.Application
import com.vocab5000.core.billing.EntitlementRepository
import com.vocab5000.core.billing.EntitlementRepositoryProvider
import com.vocab5000.core.settings.PreferencesRepository
import com.vocab5000.core.settings.ReminderDependencies
import com.vocab5000.core.settings.ReminderEligibility
import com.vocab5000.core.settings.ReminderScheduler
import kotlinx.coroutines.launch

open class German5000App : Application(), EntitlementRepositoryProvider, ReminderDependencies {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ReminderScheduler.ensureChannel(this)
        // Recompute the next reminder on launch (spec §11).
        container.appScope.launch { container.reminderScheduler.reschedule() }
    }

    override val entitlementRepository: EntitlementRepository get() = container.entitlements
    override val reminderScheduler: ReminderScheduler get() = container.reminderScheduler
    override val reminderEligibility: ReminderEligibility get() = container.reminderEligibility
    override val preferencesRepository: PreferencesRepository get() = container.preferences
}

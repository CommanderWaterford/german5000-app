package com.vocab5000.german

import android.app.Application
import android.util.Log
import com.vocab5000.core.audio.AudioPlayer
import com.vocab5000.core.billing.AcknowledgeWorker
import com.vocab5000.core.billing.EntitlementRepository
import com.vocab5000.core.billing.PlayBillingGateway
import com.vocab5000.core.billing.PurchaseVerifier
import com.vocab5000.core.content.ContentPack
import com.vocab5000.core.content.ContentValidationException
import com.vocab5000.core.content.StudyCatalog
import com.vocab5000.core.database.AssessmentRepository
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.database.StatsRepository
import com.vocab5000.core.database.UserDatabase
import com.vocab5000.core.learning.AnswerEvaluator
import com.vocab5000.core.model.AppClock
import com.vocab5000.core.model.LanguagePack
import com.vocab5000.core.model.SystemAppClock
import com.vocab5000.core.settings.BackupIdentity
import com.vocab5000.core.settings.ClockChangeDetector
import com.vocab5000.core.settings.PreferencesRepository
import com.vocab5000.core.settings.ProgressBackup
import com.vocab5000.core.settings.ReminderEligibility
import com.vocab5000.core.settings.ReminderScheduler
import com.vocab5000.languages.german.GermanLanguagePack
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

sealed interface ContentState {
    data class Ready(val catalog: StudyCatalog, val audio: AudioPlayer) : ContentState
    data class Failed(val problems: List<String>) : ContentState
}

/**
 * Manual dependency wiring for the German5000 app shell. A second language app would
 * provide a different [LanguagePack] and content pack ID and reuse everything else.
 */
class AppContainer(private val app: Application) {
    val languagePack: LanguagePack = GermanLanguagePack
    val clock: AppClock = SystemAppClock
    /** Background work must never crash the process; failures are logged and retried later. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.w(TAG, "Background task failed", e) },
    )

    val database: UserDatabase by lazy { UserDatabase.create(app) }
    val preferences = PreferencesRepository(app)
    val learning by lazy { LearningRepository(database, clock) }
    val stats by lazy { StatsRepository(database, clock) }
    val assessments by lazy { AssessmentRepository(database) }
    val evaluator = AnswerEvaluator(languagePack.answerNormalizer)
    val reminderScheduler = ReminderScheduler(app, preferences, clock)
    val clockChangeDetector = ClockChangeDetector(preferences)

    val entitlements: EntitlementRepository by lazy {
        EntitlementRepository(
            gateway = PlayBillingGateway(app),
            dao = database.entitlementDao(),
            verifier = PurchaseVerifier(BuildConfig.PLAY_LICENSE_KEY),
            clock = clock,
            scope = appScope,
            ackScheduler = AcknowledgeWorker.scheduler(app),
        )
    }

    private val content: Deferred<ContentState> = appScope.async(Dispatchers.IO) { loadContent() }

    suspend fun content(): ContentState = content.await()

    private fun loadContent(): ContentState = try {
        val text = app.assets.open("content/${BuildConfig.CONTENT_PACK_ID}/pack.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val pack = ContentPack.load(text, languagePack)
        ContentState.Ready(StudyCatalog(pack, languagePack), AudioPlayer(app, pack, languagePack.speechLocaleTag))
    } catch (e: ContentValidationException) {
        ContentState.Failed(e.errors)
    } catch (e: Exception) {
        ContentState.Failed(listOf(e.message ?: e.javaClass.simpleName))
    }

    fun progressBackup(catalog: StudyCatalog) = ProgressBackup(
        identity = BackupIdentity(
            appFamily = APP_FAMILY,
            packId = catalog.content.manifest.packId,
            languageId = catalog.content.manifest.languageId,
            contentVersion = catalog.content.manifest.contentVersion,
        ),
        knownEntryIds = catalog.content.entries.map { it.entryId }.toSet(),
        nowMillis = clock::nowMillis,
    )

    /** Spec §11: remind only if work exists and today's goal/study day is still open. */
    val reminderEligibility = ReminderEligibility {
        val state = content() as? ContentState.Ready ?: return@ReminderEligibility false
        val catalog = state.catalog
        val prefs = preferences.current()
        val premium = entitlements.isPremium.value
        val cards = learning.cardsSnapshot()
        val introducedToday = learning.introducedToday()
        val gradedToday = stats.activity.first().gradedByDate[clock.today()] ?: 0
        val due = catalog.dueCount(cards, premium, prefs.listeningEnabled, clock.nowMillis())
        val newAvailable = !prefs.newWordsPaused &&
            introducedToday < prefs.dailyNewGoal &&
            catalog.newCandidates(learning.introducedIds(), premium, prefs.selectedBand, 1).isNotEmpty()
        val goalMet = if (prefs.dailyNewGoal == 0) {
            com.vocab5000.core.learning.ProgressMetrics.isStudyDay(gradedToday, introducedToday)
        } else {
            introducedToday >= prefs.dailyNewGoal
        }
        !goalMet && (due > 0 || newAvailable)
    }

    companion object {
        const val APP_FAMILY = "vocab5000"
        private const val TAG = "German5000"
    }
}

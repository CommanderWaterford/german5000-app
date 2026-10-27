package com.vocab5000.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vocab5000.core.model.ScheduledAnswerStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalTime

enum class OnboardingStep { WELCOME, START_CHOICE, GOAL, REMINDERS, STORAGE_NOTE, DONE }

enum class StartChoice { BEGINNING, ASSESSMENT }

data class UserPreferences(
    val onboardingStep: OnboardingStep = OnboardingStep.WELCOME,
    val startChoice: StartChoice = StartChoice.BEGINNING,
    val dailyNewGoal: Int = DEFAULT_DAILY_GOAL,
    val newWordsPaused: Boolean = false,
    val selectedBand: Int = 1,
    val reminderEnabled: Boolean = false,
    val reminderTime: LocalTime = LocalTime.of(19, 0),
    val reminderDays: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val lastNotifiedLocalDate: String? = null,
    val theme: String = "SYSTEM",
    val slowAudio: Boolean = false,
    val speechFallback: Boolean = true,
    val listeningEnabled: Boolean = false,
    val answerStyle: ScheduledAnswerStyle = ScheduledAnswerStyle.TYPED,
    val pendingAssessmentAfterOnboarding: Boolean = false,
) {
    val onboardingComplete: Boolean get() = onboardingStep == OnboardingStep.DONE

    companion object {
        const val DEFAULT_DAILY_GOAL = 10
        val GOAL_PRESETS = listOf(5, 10, 15, 20)
        val GOAL_RANGE = 0..30
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "german5000_preferences")

/** Small preferences only; review history lives in Room (spec §9.1). */
class PreferencesRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val onboardingStep = stringPreferencesKey("onboarding_step")
        val startChoice = stringPreferencesKey("start_choice")
        val dailyGoal = intPreferencesKey("daily_new_goal")
        val newPaused = booleanPreferencesKey("new_words_paused")
        val band = intPreferencesKey("selected_band")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderMinutes = intPreferencesKey("reminder_minutes")
        val reminderDays = stringSetPreferencesKey("reminder_days")
        val lastNotified = stringPreferencesKey("last_notified_local_date")
        val theme = stringPreferencesKey("theme")
        val slowAudio = booleanPreferencesKey("slow_audio")
        val speechFallback = booleanPreferencesKey("speech_fallback")
        val listening = booleanPreferencesKey("listening_enabled")
        val answerStyle = stringPreferencesKey("answer_style")
        val pendingAssessment = booleanPreferencesKey("pending_assessment")
        val lastWallClock = longPreferencesKey("clock_last_wall")
        val lastElapsed = longPreferencesKey("clock_last_elapsed")
    }

    val preferences: Flow<UserPreferences> = store.data.map { p ->
        UserPreferences(
            onboardingStep = p[Keys.onboardingStep]?.let { runCatching { OnboardingStep.valueOf(it) }.getOrNull() } ?: OnboardingStep.WELCOME,
            startChoice = p[Keys.startChoice]?.let { runCatching { StartChoice.valueOf(it) }.getOrNull() } ?: StartChoice.BEGINNING,
            dailyNewGoal = (p[Keys.dailyGoal] ?: UserPreferences.DEFAULT_DAILY_GOAL).coerceIn(UserPreferences.GOAL_RANGE),
            newWordsPaused = p[Keys.newPaused] ?: false,
            selectedBand = p[Keys.band] ?: 1,
            reminderEnabled = p[Keys.reminderEnabled] ?: false,
            reminderTime = (p[Keys.reminderMinutes] ?: (19 * 60)).let { LocalTime.of(it / 60, it % 60) },
            reminderDays = p[Keys.reminderDays]?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet() ?: DayOfWeek.entries.toSet(),
            lastNotifiedLocalDate = p[Keys.lastNotified],
            theme = p[Keys.theme] ?: "SYSTEM",
            slowAudio = p[Keys.slowAudio] ?: false,
            speechFallback = p[Keys.speechFallback] ?: true,
            listeningEnabled = p[Keys.listening] ?: false,
            answerStyle = p[Keys.answerStyle]?.let { runCatching { ScheduledAnswerStyle.valueOf(it) }.getOrNull() } ?: ScheduledAnswerStyle.TYPED,
            pendingAssessmentAfterOnboarding = p[Keys.pendingAssessment] ?: false,
        )
    }

    suspend fun current(): UserPreferences = preferences.first()

    suspend fun setOnboardingStep(step: OnboardingStep) = store.edit { it[Keys.onboardingStep] = step.name }
    suspend fun setStartChoice(choice: StartChoice) = store.edit {
        it[Keys.startChoice] = choice.name
        it[Keys.pendingAssessment] = choice == StartChoice.ASSESSMENT
    }
    suspend fun clearPendingAssessment() = store.edit { it[Keys.pendingAssessment] = false }
    suspend fun setDailyGoal(goal: Int) = store.edit { it[Keys.dailyGoal] = goal.coerceIn(UserPreferences.GOAL_RANGE) }
    suspend fun setNewWordsPaused(paused: Boolean) = store.edit { it[Keys.newPaused] = paused }
    suspend fun setSelectedBand(band: Int) = store.edit { it[Keys.band] = band.coerceAtLeast(1) }
    suspend fun setReminder(enabled: Boolean, time: LocalTime, days: Set<DayOfWeek>) = store.edit {
        it[Keys.reminderEnabled] = enabled
        it[Keys.reminderMinutes] = time.hour * 60 + time.minute
        it[Keys.reminderDays] = days.map(DayOfWeek::name).toSet()
    }
    suspend fun setReminderEnabled(enabled: Boolean) = store.edit { it[Keys.reminderEnabled] = enabled }
    suspend fun setLastNotified(localDate: String) = store.edit { it[Keys.lastNotified] = localDate }
    suspend fun setTheme(theme: String) = store.edit { it[Keys.theme] = theme }
    suspend fun setSlowAudio(slow: Boolean) = store.edit { it[Keys.slowAudio] = slow }
    suspend fun setSpeechFallback(enabled: Boolean) = store.edit { it[Keys.speechFallback] = enabled }
    suspend fun setListeningEnabled(enabled: Boolean) = store.edit { it[Keys.listening] = enabled }
    suspend fun setAnswerStyle(style: ScheduledAnswerStyle) = store.edit { it[Keys.answerStyle] = style.name }

    /** Restores learning preferences from a backup. Reminders stay disabled (spec §13.3). */
    suspend fun applyImported(imported: ImportedPreferences) = store.edit {
        imported.dailyNewGoal?.let { g -> it[Keys.dailyGoal] = g.coerceIn(UserPreferences.GOAL_RANGE) }
        imported.selectedBand?.let { b -> it[Keys.band] = b.coerceAtLeast(1) }
        imported.newWordsPaused?.let { v -> it[Keys.newPaused] = v }
        imported.listeningEnabled?.let { v -> it[Keys.listening] = v }
        imported.slowAudio?.let { v -> it[Keys.slowAudio] = v }
        imported.answerStyle?.let { v -> it[Keys.answerStyle] = v.name }
        it[Keys.reminderEnabled] = false
    }

    /** Reset keeps display preferences; delete-all clears everything. */
    suspend fun resetLearningPreferences() = store.edit {
        it.remove(Keys.band)
        it.remove(Keys.newPaused)
        it.remove(Keys.pendingAssessment)
    }

    suspend fun clearAll() = store.edit { it.clear() }

    /** Returns the previous (wall, elapsed) sample and stores the new one. */
    suspend fun swapClockSample(wall: Long, elapsed: Long): Pair<Long, Long>? {
        var previous: Pair<Long, Long>? = null
        store.edit {
            val w = it[Keys.lastWallClock]
            val e = it[Keys.lastElapsed]
            if (w != null && e != null) previous = w to e
            it[Keys.lastWallClock] = wall
            it[Keys.lastElapsed] = elapsed
        }
        return previous
    }
}

data class ImportedPreferences(
    val dailyNewGoal: Int? = null,
    val selectedBand: Int? = null,
    val newWordsPaused: Boolean? = null,
    val listeningEnabled: Boolean? = null,
    val slowAudio: Boolean? = null,
    val answerStyle: ScheduledAnswerStyle? = null,
)

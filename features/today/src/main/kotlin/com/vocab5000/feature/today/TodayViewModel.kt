package com.vocab5000.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocab5000.core.content.StudyCatalog
import com.vocab5000.core.database.ActivitySummary
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.database.StatsRepository
import com.vocab5000.core.learning.SessionPlanner
import com.vocab5000.core.model.AppClock
import com.vocab5000.core.settings.PreferencesRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

data class TodayState(
    val loaded: Boolean = false,
    val dueCount: Int = 0,
    val newAllowance: Int = 0,
    val dailyGoal: Int = 10,
    val introducedToday: Int = 0,
    val gradedToday: Int = 0,
    val streak: Int = 0,
    val hasActiveSession: Boolean = false,
    val newWordsPaused: Boolean = false,
    val noNewWordsLeft: Boolean = false,
    val freeLimitReached: Boolean = false,
    val premium: Boolean = false,
    val date: LocalDate? = null,
    /** Activity level per day for the streak map, Monday-first columns; -1 marks days after today. */
    val heat: List<Int> = emptyList(),
) {
    val goalMet: Boolean get() = dailyGoal > 0 && introducedToday >= dailyGoal
    val backlogWarning: Boolean get() = SessionPlanner.shouldWarnAboutBacklog(dueCount)
}

class TodayViewModel(
    private val catalog: StudyCatalog,
    private val repository: LearningRepository,
    stats: StatsRepository,
    private val preferences: PreferencesRepository,
    premium: StateFlow<Boolean>,
    private val clock: AppClock,
) : ViewModel() {
    /** Re-evaluates due counts as time passes without polling the database. */
    private val tick = MutableStateFlow(0L)

    init {
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                tick.value = clock.nowMillis()
            }
        }
    }

    private val base = combine(
        repository.cardStates,
        repository.userEntries,
        preferences.preferences,
        premium,
        tick,
    ) { cards, userEntries, prefs, isPremium, _ ->
        val today = clock.today().toString()
        val introducedToday = userEntries.values.count { it.introductionLocalDate == today }
        val introduced = userEntries.values.filter { it.introducedAt != null }.map { it.entryId }.toSet()
        val candidates = catalog.newCandidates(introduced, isPremium, prefs.selectedBand, 1)
        val allLocked = candidates.isEmpty() && !isPremium && catalog.content.byRank.any { it.entryId !in introduced }
        TodayState(
            loaded = true,
            dueCount = catalog.dueCount(cards, isPremium, prefs.listeningEnabled, clock.nowMillis()),
            newAllowance = if (prefs.newWordsPaused || candidates.isEmpty()) 0 else (prefs.dailyNewGoal - introducedToday).coerceAtLeast(0),
            dailyGoal = prefs.dailyNewGoal,
            introducedToday = introducedToday,
            newWordsPaused = prefs.newWordsPaused,
            noNewWordsLeft = candidates.isEmpty(),
            freeLimitReached = allLocked,
            premium = isPremium,
        )
    }

    val state: StateFlow<TodayState> = combine(base, stats.activity, repository.observeActiveSession(), repository.observeGradedToday()) { s, activity, session, graded ->
        val today = clock.today()
        s.copy(
            streak = activity.streak(today),
            hasActiveSession = session != null,
            gradedToday = graded,
            date = today,
            heat = streakMap(activity, today),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    fun setPaused(paused: Boolean) = viewModelScope.launch { preferences.setNewWordsPaused(paused) }
    fun setGoal(goal: Int) = viewModelScope.launch { preferences.setDailyGoal(goal) }
}

const val STREAK_MAP_WEEKS = 12

/** One cell per day, column by column, ending with the week that contains [today]. */
internal fun streakMap(activity: ActivitySummary, today: LocalDate): List<Int> {
    val start = today.with(DayOfWeek.MONDAY).minusWeeks(STREAK_MAP_WEEKS - 1L)
    return List(STREAK_MAP_WEEKS * 7) { i ->
        val date = start.plusDays(i.toLong())
        if (date.isAfter(today)) {
            -1
        } else {
            val answers = (activity.gradedByDate[date] ?: 0) + (activity.introducedByDate[date] ?: 0)
            when {
                answers == 0 -> 0
                answers < 10 -> 1
                answers < 30 -> 2
                else -> 3
            }
        }
    }
}

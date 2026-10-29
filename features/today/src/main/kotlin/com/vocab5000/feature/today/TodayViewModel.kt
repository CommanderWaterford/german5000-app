package com.vocab5000.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocab5000.core.content.StudyCatalog
import com.vocab5000.core.database.ActivitySummary
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.database.StatsRepository
import com.vocab5000.core.learning.SessionPlanner
import com.vocab5000.core.model.AppClock
import com.vocab5000.core.model.Entry
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

/** One word learned today, split so a goal tile can colour its article. */
data class GoalWord(val article: String?, val word: String)

/** Today's featured word: a free entry picked deterministically from the date. */
data class WordOfDay(
    val entryId: String,
    val headline: String,
    val citation: String,
    val gloss: String,
    val exampleDe: String?,
    val exampleEn: String?,
    val wordAudioId: String?,
    val exampleAudioId: String?,
)

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
    /** Words introduced today in the order they were learned; fills the goal tiles. */
    val goalWords: List<GoalWord> = emptyList(),
    val wordOfDay: WordOfDay? = null,
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
        val todayDate = clock.today()
        val today = todayDate.toString()
        val todaysEntries = userEntries.values.filter { it.introductionLocalDate == today }
        val introduced = userEntries.values.filter { it.introducedAt != null }.map { it.entryId }.toSet()
        val candidates = catalog.newCandidates(introduced, isPremium, prefs.selectedBand, 1)
        val allLocked = candidates.isEmpty() && !isPremium && catalog.content.byRank.any { it.entryId !in introduced }
        TodayState(
            loaded = true,
            dueCount = catalog.dueCount(cards, isPremium, prefs.listeningEnabled, clock.nowMillis()),
            newAllowance = if (prefs.newWordsPaused || candidates.isEmpty()) 0 else (prefs.dailyNewGoal - todaysEntries.size).coerceAtLeast(0),
            dailyGoal = prefs.dailyNewGoal,
            introducedToday = todaysEntries.size,
            newWordsPaused = prefs.newWordsPaused,
            noNewWordsLeft = candidates.isEmpty(),
            freeLimitReached = allLocked,
            premium = isPremium,
            goalWords = todaysEntries.sortedBy { it.introducedAt ?: 0L }.mapNotNull { catalog.content.entry(it.entryId) }.map(::goalWord),
            wordOfDay = wordOfDay(todayDate, isPremium),
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

    private fun goalWord(entry: Entry): GoalWord {
        val citation = catalog.languagePack.grammarRenderer.citationForm(entry)
        val first = citation.substringBefore(' ')
        return if (first != citation && first in ARTICLES) GoalWord(first, citation.substring(first.length + 1)) else GoalWord(null, citation)
    }

    /** Same word for everyone on a given day, cycling through the words the learner can open. */
    private fun wordOfDay(today: LocalDate, isPremium: Boolean): WordOfDay? {
        val pool = catalog.content.byRank.filter { catalog.isUnlocked(it, isPremium) }
        if (pool.isEmpty()) return null
        val entry = pool[(today.toEpochDay() % pool.size).toInt()]
        val renderer = catalog.languagePack.grammarRenderer
        val example = entry.primaryExample
        return WordOfDay(
            entryId = entry.entryId,
            headline = renderer.headline(entry),
            citation = renderer.citationForm(entry),
            gloss = entry.primarySense.glossEn,
            exampleDe = example?.textDe,
            exampleEn = example?.textEn,
            wordAudioId = entry.wordAudioId,
            exampleAudioId = example?.audioId,
        )
    }

    private companion object {
        val ARTICLES = setOf("der", "die", "das")
    }
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

package com.vocab5000.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocab5000.core.content.StudyCatalog
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.database.StatsRepository
import com.vocab5000.core.learning.ProgressMetrics
import com.vocab5000.core.model.AppClock
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardStateType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class ProgressState(
    val loaded: Boolean = false,
    val totalEntries: Int = 0,
    val freeEntries: Int = 0,
    val introduced: Int = 0,
    val introducedFree: Int = 0,
    val inLearning: Int = 0,
    val mastered: Int = 0,
    val masteredFree: Int = 0,
    val due: Int = 0,
    val suspended: Int = 0,
    val listeningMastered: Int = 0,
    val listeningCards: Int = 0,
    val accuracy: ProgressMetrics.Accuracy = ProgressMetrics.Accuracy(0, 0),
    val streak: Int = 0,
    val calendar: List<Pair<LocalDate, Boolean>> = emptyList(),
)

class ProgressViewModel(
    catalog: StudyCatalog,
    repository: LearningRepository,
    stats: StatsRepository,
    premium: StateFlow<Boolean>,
    clock: AppClock,
) : ViewModel() {
    val state: StateFlow<ProgressState> = combine(
        repository.cardStates,
        repository.userEntries,
        stats.activity,
        stats.accuracy(),
        premium,
    ) { cards, users, activity, accuracy, isPremium ->
        val freeLimit = catalog.content.manifest.freeEntryLimit
        fun isFree(entryId: String) = (catalog.content.rankOf(entryId) ?: Int.MAX_VALUE) <= freeLimit
        val productive = cards.filter { it.skill == CardSkill.PRODUCTIVE && catalog.content.entry(it.entryId) != null }
        val listening = cards.filter { it.skill == CardSkill.LISTENING }
        val mastered = productive.filter(ProgressMetrics::isMastered)
        val introduced = users.values.filter { it.introducedAt != null && catalog.content.entry(it.entryId) != null }
        val today = clock.today()
        ProgressState(
            loaded = true,
            totalEntries = catalog.content.manifest.entryCount,
            freeEntries = freeLimit,
            introduced = introduced.size,
            introducedFree = introduced.count { isFree(it.entryId) },
            inLearning = productive.count(ProgressMetrics::isInLearning),
            mastered = mastered.size,
            masteredFree = mastered.count { isFree(it.entryId) },
            due = catalog.dueCount(cards, isPremium, listeningEnabled = true, now = clock.nowMillis()),
            suspended = cards.count { it.state == CardStateType.SUSPENDED },
            listeningMastered = listening.count { it.state == CardStateType.REVIEW && it.intervalDays >= ProgressMetrics.MASTERY_MIN_INTERVAL_DAYS },
            listeningCards = listening.size,
            accuracy = accuracy,
            streak = activity.streak(today),
            calendar = (0L until CALENDAR_DAYS).map { offset -> today.minusDays(CALENDAR_DAYS - 1 - offset) }.map { it to (it in activity.qualifyingDates) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressState())

    companion object {
        const val CALENDAR_DAYS = 28L
    }
}

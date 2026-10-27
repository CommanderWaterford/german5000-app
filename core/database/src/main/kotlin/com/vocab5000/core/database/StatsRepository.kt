package com.vocab5000.core.database

import com.vocab5000.core.learning.ProgressMetrics
import com.vocab5000.core.model.AppClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class ActivitySummary(
    val qualifyingDates: Set<LocalDate>,
    val gradedByDate: Map<LocalDate, Int>,
    val introducedByDate: Map<LocalDate, Int>,
) {
    fun streak(today: LocalDate): Int = ProgressMetrics.streak(qualifyingDates, today)
}

/** Read-only progress queries; all metrics are computed locally and work offline. */
class StatsRepository(db: UserDatabase, private val clock: AppClock) {
    private val events = db.reviewEventDao()
    private val entries = db.userEntryDao()

    val activity: Flow<ActivitySummary> = combine(events.observeGradedByDate(), entries.observeIntroductionsByDate()) { graded, introduced ->
        val gradedMap = graded.associate { LocalDate.parse(it.localDate) to it.count }
        val introducedMap = introduced.associate { LocalDate.parse(it.localDate) to it.count }
        val dates = (gradedMap.keys + introducedMap.keys).filter {
            ProgressMetrics.isStudyDay(gradedMap[it] ?: 0, introducedMap[it] ?: 0)
        }.toSet()
        ActivitySummary(dates, gradedMap, introducedMap)
    }

    fun accuracy(): Flow<ProgressMetrics.Accuracy> =
        events.observeFirstReviewAttemptsSince(ProgressMetrics.accuracyWindowStart(clock.today()).toString())
            .map { results -> ProgressMetrics.Accuracy(results.count { it }, results.size) }
}

package com.vocab5000.core.learning

import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import java.time.LocalDate

/** Exact v1 metric definitions (spec §10). */
object ProgressMetrics {
    const val MASTERY_MIN_INTERVAL_DAYS = 21
    const val MASTERY_MIN_CONSECUTIVE = 2
    const val STUDY_DAY_MIN_GRADED = 5
    const val ACCURACY_WINDOW_DAYS = 30L
    const val ACCURACY_MIN_SAMPLE = 10

    fun isMastered(card: CardState): Boolean =
        card.skill == CardSkill.PRODUCTIVE &&
            card.state == CardStateType.REVIEW &&
            card.intervalDays >= MASTERY_MIN_INTERVAL_DAYS &&
            card.consecutiveReviewSuccesses >= MASTERY_MIN_CONSECUTIVE

    fun isInLearning(card: CardState): Boolean =
        card.skill == CardSkill.PRODUCTIVE &&
            (card.state == CardStateType.LEARNING || card.state == CardStateType.RELEARNING)

    fun isStudyDay(gradedScheduledAnswers: Int, introductions: Int): Boolean =
        gradedScheduledAnswers >= STUDY_DAY_MIN_GRADED || introductions >= 1

    /**
     * Consecutive qualifying local dates ending today, or ending yesterday when today has
     * no qualifying activity yet (the streak is kept until today ends).
     */
    fun streak(qualifyingDates: Set<LocalDate>, today: LocalDate): Int {
        var cursor = if (today in qualifyingDates) today else today.minusDays(1)
        var count = 0
        while (cursor in qualifyingDates) {
            count++
            cursor = cursor.minusDays(1)
        }
        return count
    }

    data class Accuracy(val successes: Int, val attempts: Int) {
        /** Null below the minimum sample so a percentage is never shown for tiny samples. */
        val percent: Int? get() = if (attempts < ACCURACY_MIN_SAMPLE) null else Math.round(successes * 100.0 / attempts).toInt()
    }

    fun accuracyWindowStart(today: LocalDate): LocalDate = today.minusDays(ACCURACY_WINDOW_DAYS - 1)
}

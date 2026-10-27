package com.vocab5000.core.learning

import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.Rating

/**
 * Deterministic scheduler `g5000_srs_v1` (spec §8.3).
 *
 * A product algorithm inspired by conventional spaced review; it is not SM-2 or FSRS.
 * All arithmetic is integer-based (ease in permille) so results are exact and portable
 * to a future web client via the golden fixtures in the test suite.
 */
object SrsV1 {
    const val VERSION = "g5000_srs_v1"

    const val MINUTE_MS = 60_000L
    const val DAY_MS = 86_400_000L

    const val MIN_EASE = 1300
    const val MAX_EASE = 3000
    const val MAX_INTERVAL_DAYS = 365
    const val DIFFICULT_LAPSE_THRESHOLD = 8

    /** First presentation teaches the entry and makes recall immediately eligible. */
    fun introduce(card: CardState, now: Long): CardState {
        require(card.state == CardStateType.NEW) { "Only new cards can be introduced" }
        return card.copy(
            state = CardStateType.LEARNING,
            step = 0,
            dueAt = now,
            schedulerVersion = VERSION,
        )
    }

    /** Starts an additional skill card (e.g. listening) for an already introduced entry. */
    fun startAdditionalSkill(card: CardState, now: Long): CardState = introduce(card, now)

    fun schedule(card: CardState, rating: Rating, now: Long): CardState = when (card.state) {
        CardStateType.LEARNING -> learning(card, rating, now)
        CardStateType.RELEARNING -> relearning(card, rating, now)
        CardStateType.REVIEW -> review(card, rating, now)
        CardStateType.NEW -> error("New card ${card.cardId} must be introduced before grading")
        CardStateType.SUSPENDED -> error("Suspended card ${card.cardId} cannot be graded")
    }.copy(schedulerVersion = VERSION)

    private fun learning(card: CardState, rating: Rating, now: Long): CardState = when (card.step) {
        0 -> when (rating) {
            Rating.AGAIN -> card.copy(step = 0, dueAt = now + 1 * MINUTE_MS)
            Rating.HARD -> card.copy(step = 0, dueAt = now + 5 * MINUTE_MS)
            Rating.GOOD -> card.copy(step = 1, dueAt = now + 10 * MINUTE_MS)
            Rating.EASY -> graduate(card, 4, now)
        }
        else -> when (rating) {
            Rating.AGAIN -> card.copy(step = 0, dueAt = now + 1 * MINUTE_MS)
            Rating.HARD -> card.copy(step = 1, dueAt = now + 10 * MINUTE_MS)
            Rating.GOOD -> graduate(card, 1, now)
            Rating.EASY -> graduate(card, 4, now)
        }
    }

    private fun graduate(card: CardState, days: Int, now: Long) = card.copy(
        state = CardStateType.REVIEW,
        step = 0,
        intervalDays = days,
        easePermille = CardState.DEFAULT_EASE_PERMILLE,
        dueAt = now + days * DAY_MS,
    )

    private fun relearning(card: CardState, rating: Rating, now: Long): CardState = when (rating) {
        Rating.AGAIN -> card.copy(step = 0, dueAt = now + 10 * MINUTE_MS)
        Rating.HARD -> card.copy(step = 0, dueAt = now + 15 * MINUTE_MS)
        Rating.GOOD -> returnToReview(card, card.recoveryIntervalDays, now)
        Rating.EASY -> returnToReview(card, maxOf(card.recoveryIntervalDays, 2), now)
    }

    private fun returnToReview(card: CardState, days: Int, now: Long): CardState {
        val interval = days.coerceIn(1, MAX_INTERVAL_DAYS)
        return card.copy(state = CardStateType.REVIEW, step = 0, intervalDays = interval, dueAt = now + interval * DAY_MS)
    }

    private fun review(card: CardState, rating: Rating, now: Long): CardState {
        val i = card.intervalDays.toLong()
        val e = card.easePermille.toLong()
        return when (rating) {
            Rating.AGAIN -> card.copy(
                state = CardStateType.RELEARNING,
                step = 0,
                lapses = card.lapses + 1,
                easePermille = maxOf(MIN_EASE, card.easePermille - 200),
                recoveryIntervalDays = maxOf(1L, roundHalfUp(i, 2)).toInt(),
                consecutiveReviewSuccesses = 0,
                dueAt = now + 10 * MINUTE_MS,
            )
            Rating.HARD -> reviewed(
                card,
                interval = capped(maxOf(i + 1, ceilDiv(i * 12, 10))),
                ease = maxOf(MIN_EASE, card.easePermille - 150),
                successes = 0,
                now = now,
            )
            Rating.GOOD -> reviewed(
                card,
                interval = capped(maxOf(i + 1, roundHalfUp(i * e, 1000))),
                ease = card.easePermille,
                successes = card.consecutiveReviewSuccesses + 1,
                now = now,
            )
            Rating.EASY -> reviewed(
                card,
                interval = capped(maxOf(i + 1, roundHalfUp(i * e * 13, 10_000))),
                ease = minOf(MAX_EASE, card.easePermille + 150),
                successes = card.consecutiveReviewSuccesses + 1,
                now = now,
            )
        }
    }

    private fun reviewed(card: CardState, interval: Int, ease: Int, successes: Int, now: Long) = card.copy(
        state = CardStateType.REVIEW,
        step = 0,
        intervalDays = interval,
        easePermille = ease,
        consecutiveReviewSuccesses = successes,
        dueAt = now + interval * DAY_MS,
    )

    private fun capped(days: Long): Int = minOf(MAX_INTERVAL_DAYS.toLong(), days).toInt()

    /** Round half up of numerator/denominator for non-negative values. */
    internal fun roundHalfUp(numerator: Long, denominator: Long): Long = (2 * numerator + denominator) / (2 * denominator)

    private fun ceilDiv(numerator: Long, denominator: Long): Long = (numerator + denominator - 1) / denominator

    fun suspend(card: CardState): CardState {
        if (card.state == CardStateType.SUSPENDED) return card
        return card.copy(priorState = card.state, state = CardStateType.SUSPENDED)
    }

    /** Unsuspending keeps history and makes the card due now in its prior active state. */
    fun unsuspend(card: CardState, now: Long): CardState {
        if (card.state != CardStateType.SUSPENDED) return card
        return card.copy(state = card.priorState ?: CardStateType.LEARNING, priorState = null, dueAt = now)
    }

    fun isDifficult(card: CardState): Boolean = card.lapses >= DIFFICULT_LAPSE_THRESHOLD
}

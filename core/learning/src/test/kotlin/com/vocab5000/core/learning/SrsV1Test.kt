package com.vocab5000.core.learning

import com.vocab5000.core.learning.SrsV1.DAY_MS
import com.vocab5000.core.learning.SrsV1.MINUTE_MS
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.Rating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SrsV1Test {
    private val now = 1_800_000_000_000L
    private fun newCard() = CardState.newCard("de.noun.tisch.01", CardSkill.PRODUCTIVE, SrsV1.VERSION)
    private fun learning(step: Int) = SrsV1.introduce(newCard(), now).copy(step = step)
    private fun review(interval: Int, ease: Int = 2500, successes: Int = 0) = newCard().copy(
        state = CardStateType.REVIEW, intervalDays = interval, easePermille = ease, consecutiveReviewSuccesses = successes, dueAt = now,
    )

    @Test fun introductionCreatesLearningStepZeroDueNow() {
        val card = SrsV1.introduce(newCard(), now)
        assertEquals(CardStateType.LEARNING, card.state)
        assertEquals(0, card.step)
        assertEquals(now, card.dueAt)
    }

    @Test fun learningStepZeroTransitions() {
        val c = learning(0)
        SrsV1.schedule(c, Rating.AGAIN, now).let { assertEquals(0, it.step); assertEquals(now + MINUTE_MS, it.dueAt) }
        SrsV1.schedule(c, Rating.HARD, now).let { assertEquals(0, it.step); assertEquals(now + 5 * MINUTE_MS, it.dueAt) }
        SrsV1.schedule(c, Rating.GOOD, now).let { assertEquals(1, it.step); assertEquals(now + 10 * MINUTE_MS, it.dueAt) }
        SrsV1.schedule(c, Rating.EASY, now).let {
            assertEquals(CardStateType.REVIEW, it.state); assertEquals(4, it.intervalDays); assertEquals(2500, it.easePermille); assertEquals(now + 4 * DAY_MS, it.dueAt)
        }
    }

    @Test fun learningStepOneTransitions() {
        val c = learning(1)
        SrsV1.schedule(c, Rating.AGAIN, now).let { assertEquals(0, it.step); assertEquals(now + MINUTE_MS, it.dueAt) }
        SrsV1.schedule(c, Rating.HARD, now).let { assertEquals(1, it.step); assertEquals(now + 10 * MINUTE_MS, it.dueAt) }
        SrsV1.schedule(c, Rating.GOOD, now).let { assertEquals(CardStateType.REVIEW, it.state); assertEquals(1, it.intervalDays); assertEquals(now + DAY_MS, it.dueAt) }
        SrsV1.schedule(c, Rating.EASY, now).let { assertEquals(4, it.intervalDays) }
    }

    @Test fun graduationDoesNotCountAsReviewSuccess() {
        assertEquals(0, SrsV1.schedule(learning(1), Rating.GOOD, now).consecutiveReviewSuccesses)
    }

    @Test fun specExampleIntervalTenEaseTwoPointFive() {
        val c = review(10)
        assertEquals(12, SrsV1.schedule(c, Rating.HARD, now).intervalDays)
        assertEquals(25, SrsV1.schedule(c, Rating.GOOD, now).intervalDays)
        assertEquals(33, SrsV1.schedule(c, Rating.EASY, now).intervalDays)
        val again = SrsV1.schedule(c, Rating.AGAIN, now)
        assertEquals(CardStateType.RELEARNING, again.state)
        assertEquals(5, again.recoveryIntervalDays)
        assertEquals(now + 10 * MINUTE_MS, again.dueAt)
        assertEquals(1, again.lapses)
        assertEquals(2300, again.easePermille)
    }

    /** AT-05: review 10d/2.5 → Again → +10m relearning → Good → 5d (not 25d). */
    @Test fun againThenRelearningGoodUsesRecoveryInterval() {
        val relearning = SrsV1.schedule(review(10), Rating.AGAIN, now)
        val later = now + 10 * MINUTE_MS
        val back = SrsV1.schedule(relearning, Rating.GOOD, later)
        assertEquals(CardStateType.REVIEW, back.state)
        assertEquals(5, back.intervalDays)
        assertEquals(later + 5 * DAY_MS, back.dueAt)
        assertEquals(2300, back.easePermille)
    }

    @Test fun relearningTransitions() {
        val r = SrsV1.schedule(review(10), Rating.AGAIN, now)
        assertEquals(now + 10 * MINUTE_MS, SrsV1.schedule(r, Rating.AGAIN, now).dueAt)
        assertEquals(now + 15 * MINUTE_MS, SrsV1.schedule(r, Rating.HARD, now).dueAt)
        assertEquals(5, SrsV1.schedule(r, Rating.EASY, now).intervalDays)
        val small = SrsV1.schedule(review(1), Rating.AGAIN, now)
        assertEquals(1, small.recoveryIntervalDays)
        assertEquals(2, SrsV1.schedule(small, Rating.EASY, now).intervalDays)
        assertEquals(1, small.lapses)
        assertEquals(1, SrsV1.schedule(small, Rating.AGAIN, now).lapses) // relearning Again is not a new lapse
    }

    @Test fun intervalsAlwaysGrowByAtLeastOneDay() {
        assertEquals(2, SrsV1.schedule(review(1, 1300), Rating.HARD, now).intervalDays)
        assertEquals(2, SrsV1.schedule(review(1, 1300), Rating.GOOD, now).intervalDays)
    }

    @Test fun capAtThreeHundredSixtyFiveDays() {
        listOf(Rating.HARD, Rating.GOOD, Rating.EASY).forEach {
            assertEquals(365, SrsV1.schedule(review(365), it, now).intervalDays)
        }
        assertEquals(365, SrsV1.schedule(review(200), Rating.GOOD, now).intervalDays)
        assertEquals(360, SrsV1.schedule(review(300), Rating.HARD, now).intervalDays)
    }

    @Test fun easeIsClamped() {
        assertEquals(1300, SrsV1.schedule(review(10, 1400), Rating.AGAIN, now).easePermille)
        assertEquals(1300, SrsV1.schedule(review(10, 1350), Rating.HARD, now).easePermille)
        assertEquals(3000, SrsV1.schedule(review(10, 2950), Rating.EASY, now).easePermille)
    }

    @Test fun roundingIsHalfUp() {
        // 3 × 2.5 = 7.5 → 8 ; 5 × 0.5 = 2.5 → 3 ; 1 × 2.5 × 1.3 = 3.25 → 3
        assertEquals(8, SrsV1.schedule(review(3), Rating.GOOD, now).intervalDays)
        assertEquals(3, SrsV1.schedule(review(5), Rating.AGAIN, now).recoveryIntervalDays)
        assertEquals(3, SrsV1.schedule(review(1), Rating.EASY, now).intervalDays)
        assertEquals(4L, SrsV1.roundHalfUp(7, 2))
    }

    @Test fun consecutiveSuccessesCountOnlyReviewGoodOrEasy() {
        val good = SrsV1.schedule(review(10, successes = 1), Rating.GOOD, now)
        assertEquals(2, good.consecutiveReviewSuccesses)
        assertEquals(0, SrsV1.schedule(good, Rating.HARD, now).consecutiveReviewSuccesses)
        assertEquals(0, SrsV1.schedule(good, Rating.AGAIN, now).consecutiveReviewSuccesses)
    }

    @Test fun suspendAndUnsuspendPreservePriorState() {
        val c = review(10)
        val suspended = SrsV1.suspend(c)
        assertEquals(CardStateType.SUSPENDED, suspended.state)
        assertEquals(CardStateType.REVIEW, suspended.priorState)
        val back = SrsV1.unsuspend(suspended, now + DAY_MS)
        assertEquals(CardStateType.REVIEW, back.state)
        assertEquals(now + DAY_MS, back.dueAt)
        assertEquals(10, back.intervalDays)
        assertNull(back.priorState)
    }

    @Test fun difficultAfterEightLapses() {
        assertFalse(SrsV1.isDifficult(review(1).copy(lapses = 7)))
        assertTrue(SrsV1.isDifficult(review(1).copy(lapses = 8)))
    }

    @Test(expected = IllegalStateException::class)
    fun newCardsCannotBeGradedDirectly() {
        SrsV1.schedule(newCard(), Rating.GOOD, now)
    }

    /** Golden fixture sequence for cross-platform (future web client) compatibility. */
    @Test fun goldenSequence() {
        var c = SrsV1.introduce(newCard(), now)
        var t = now
        val ratings = listOf(Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.EASY, Rating.HARD, Rating.AGAIN, Rating.GOOD, Rating.GOOD)
        val expected = listOf(
            Triple(CardStateType.LEARNING, 0, 2500),
            Triple(CardStateType.REVIEW, 1, 2500),
            Triple(CardStateType.REVIEW, 3, 2500),
            Triple(CardStateType.REVIEW, 10, 2650),
            Triple(CardStateType.REVIEW, 12, 2500),
            Triple(CardStateType.RELEARNING, 12, 2300),
            Triple(CardStateType.REVIEW, 6, 2300),
            Triple(CardStateType.REVIEW, 14, 2300),
        )
        ratings.forEachIndexed { i, r ->
            c = SrsV1.schedule(c, r, t)
            assertEquals("step $i state", expected[i].first, c.state)
            assertEquals("step $i interval", expected[i].second, c.intervalDays)
            assertEquals("step $i ease", expected[i].third, c.easePermille)
            t = c.dueAt
        }
    }
}

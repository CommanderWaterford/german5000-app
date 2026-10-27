package com.vocab5000.core.learning

import com.vocab5000.core.model.AnswerNormalizer
import com.vocab5000.core.model.AnswerSpec
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.CodepointSpan
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

class RatingPolicyTest {
    @Test fun wrongSkippedOrRevealedIsAgain() {
        assertEquals(listOf(Rating.AGAIN), RatingPolicy.allowedRatings(AttemptFacts(correct = false)))
        assertEquals(Rating.AGAIN, RatingPolicy.maxRating(AttemptFacts(correct = true, revealedBeforeAnswer = true)))
        assertEquals(Rating.AGAIN, RatingPolicy.maxRating(AttemptFacts(correct = false, skipped = true)))
    }

    @Test fun correctDefaultsToGoodAndAllowsHardOrEasy() {
        val facts = AttemptFacts(correct = true)
        assertEquals(Rating.GOOD, RatingPolicy.defaultRating(facts))
        assertEquals(listOf(Rating.HARD, Rating.GOOD, Rating.EASY), RatingPolicy.allowedRatings(facts))
    }

    @Test fun hintCapsAtHardAndOverrideCapsAtGood() {
        assertEquals(Rating.HARD, RatingPolicy.clamp(Rating.EASY, AttemptFacts(correct = true, usedHint = true)))
        assertEquals(Rating.GOOD, RatingPolicy.clamp(Rating.EASY, AttemptFacts(correct = false, validAnswerOverride = true)))
        assertEquals(Rating.GOOD, RatingPolicy.defaultRating(AttemptFacts(correct = false, validAnswerOverride = true)))
    }
}

class AnswerEvaluatorTest {
    private val simple = object : AnswerNormalizer {
        override fun normalize(input: String) = input
        override fun foldCase(input: String) = input.lowercase(Locale.ROOT)
    }
    private val evaluator = AnswerEvaluator(simple)

    @Test fun normalizesWhitespaceAndNfc() {
        // "schön" with a combining diaeresis must match the precomposed form.
        val spec = AnswerSpec(canonical = "schön")
        assertEquals(AnswerVerdict.CORRECT, evaluator.evaluate("  schön ", spec).verdict)
        assertEquals(AnswerVerdict.CORRECT, evaluator.evaluate("guten   tag", AnswerSpec("guten tag")).verdict)
    }

    @Test fun caseOnlyErrorsAreCorrectWithFeedback() {
        assertEquals(AnswerVerdict.CORRECT_CASE_ONLY, evaluator.evaluate("tisch", AnswerSpec("Tisch")).verdict)
        assertEquals(AnswerVerdict.INCORRECT, evaluator.evaluate("tisch", AnswerSpec("Tisch", caseSensitive = true)).verdict.let { if (it == AnswerVerdict.NEAR_MISS) AnswerVerdict.INCORRECT else it })
    }

    @Test fun nearMissIsNeverSuccess() {
        val result = evaluator.evaluate("Tisсh", AnswerSpec("Tisch")) // Cyrillic с
        assertFalse(result.verdict.isSuccess)
        assertEquals(AnswerVerdict.NEAR_MISS, evaluator.evaluate("Tsich", AnswerSpec("Tisch")).verdict)
    }

    @Test fun optionalAndForbiddenPrefixes() {
        val spec = AnswerSpec("Tisch", optionalPrefixes = listOf("der"), forbiddenPrefixes = listOf("die", "das"))
        assertEquals(AnswerVerdict.CORRECT, evaluator.evaluate("der Tisch", spec).verdict)
        assertEquals(AnswerVerdict.INCORRECT, evaluator.evaluate("die Tisch", spec).verdict)
    }

    @Test fun keyboardAlternativesOnlyWhenListed() {
        assertEquals(AnswerVerdict.CORRECT_KEYBOARD_ALTERNATIVE, evaluator.evaluate("schoen", AnswerSpec("schön", keyboardAlternatives = listOf("schoen"))).verdict)
        assertFalse(evaluator.evaluate("schoen", AnswerSpec("schön")).verdict.isSuccess)
    }

    @Test fun emptyInput() {
        assertEquals(AnswerVerdict.EMPTY, evaluator.evaluate("   ", AnswerSpec("ja")).verdict)
    }
}

class SessionPlannerTest {
    private val now = 1_800_000_000_000L
    private fun card(id: String, state: CardStateType, due: Long) =
        CardState.newCard(id, CardSkill.PRODUCTIVE, SrsV1.VERSION).copy(state = state, dueAt = due, intervalDays = if (state == CardStateType.REVIEW) 3 else 0)

    @Test fun learningBeforeReviewThenNewInMixedMode() {
        val cards = listOf(card("b", CardStateType.REVIEW, now - 5), card("a", CardStateType.LEARNING, now - 1))
        val next = SessionPlanner.next(PlannerInput(now, SessionMode.MIXED, cards, listOf("n"), 1, 0))
        assertEquals("a", (next as PlannedItem.Review).card.entryId)
        val onlyNew = SessionPlanner.next(PlannerInput(now, SessionMode.MIXED, emptyList(), listOf("n"), 1, 0))
        assertEquals(PlannedItem.Introduce("n"), onlyNew)
    }

    @Test fun learnNewModePrefersIntroductionOverReviews() {
        val cards = listOf(card("r", CardStateType.REVIEW, now - 5))
        assertEquals(PlannedItem.Introduce("n"), SessionPlanner.next(PlannerInput(now, SessionMode.LEARN_NEW, cards, listOf("n"), 1, 0)))
    }

    @Test fun reviewOnlyNeverIntroduces() {
        assertNull(SessionPlanner.next(PlannerInput(now, SessionMode.REVIEW_ONLY, emptyList(), listOf("n"), 5, 0)))
    }

    @Test fun notYetDueCardsAreNeverShownEarly() {
        val cards = listOf(card("a", CardStateType.LEARNING, now + 60_000))
        assertNull(SessionPlanner.next(PlannerInput(now, SessionMode.REVIEW_ONLY, cards, emptyList(), 0, 0)))
        assertEquals(now + 60_000, SessionPlanner.nextShortStepDue(cards, now))
    }

    @Test fun avoidsAdjacentSameEntryWhenAlternativeExists() {
        val cards = listOf(card("a", CardStateType.LEARNING, now - 10), card("b", CardStateType.LEARNING, now - 5))
        val next = SessionPlanner.next(PlannerInput(now, SessionMode.MIXED, cards, emptyList(), 0, 0, lastEntryId = "a"))
        assertEquals("b", next!!.entryId)
        val only = SessionPlanner.next(PlannerInput(now, SessionMode.MIXED, cards.take(1), emptyList(), 0, 0, lastEntryId = "a"))
        assertEquals("a", only!!.entryId)
    }

    @Test fun stopsAtMaximumGradedPrompts() {
        val cards = listOf(card("a", CardStateType.REVIEW, now - 1))
        assertNull(SessionPlanner.next(PlannerInput(now, SessionMode.MIXED, cards, emptyList(), 0, gradedCount = 20)))
    }

    @Test fun tieBreakByCardId() {
        val cards = listOf(card("z", CardStateType.REVIEW, now - 1), card("m", CardStateType.REVIEW, now - 1))
        assertEquals("m", SessionPlanner.next(PlannerInput(now, SessionMode.REVIEW_ONLY, cards, emptyList(), 0, 0))!!.entryId)
    }

    @Test fun suspendedCardsAreExcluded() {
        val cards = listOf(card("s", CardStateType.SUSPENDED, now - 1))
        assertNull(SessionPlanner.next(PlannerInput(now, SessionMode.REVIEW_ONLY, cards, emptyList(), 0, 0)))
    }
}

class AssessmentTest {
    private fun results(band: Int, rec: Int, prod: Int) =
        List(4) { AssessmentItemResult(band, AssessmentItemKind.RECOGNITION, it < rec) } +
            List(2) { AssessmentItemResult(band, AssessmentItemKind.PRODUCTION, it < prod) }

    @Test fun scoringFollowsSpecFormula() {
        val r = Assessment.score(results(1, 4, 1) + results(2, 2, 2) + results(3, 4, 0), 5)
        assertEquals(0.75, r.bandScores[0].score, 1e-9)
        assertTrue(r.bandScores[0].provisionallyFamiliar)
        assertEquals(0.75, r.bandScores[1].score, 1e-9)
        assertTrue(r.bandScores[1].provisionallyFamiliar)
        assertFalse(r.bandScores[2].provisionallyFamiliar) // needs a production item
        assertEquals(3, r.suggestedBand)
    }

    @Test fun allFamiliarSuggestsLastBand() {
        val r = Assessment.score((1..5).flatMap { results(it, 4, 2) }, 5)
        assertEquals(5, r.suggestedBand)
        assertTrue(r.allBandsFamiliar)
    }

    @Test fun planIsDeterministicPerSeed() {
        val pools = (1..5).associateWith { band -> (1..20).map { "b$band-$it" } }
        val a = Assessment.plan(42, pools) { true }
        assertEquals(a, Assessment.plan(42, pools) { true })
        assertEquals(30, a.size)
        assertEquals(20, a.count { it.kind == AssessmentItemKind.RECOGNITION })
    }
}

class ProgressMetricsTest {
    private val today = LocalDate.of(2026, 10, 27)

    @Test fun masteryDefinition() {
        val base = CardState.newCard("x", CardSkill.PRODUCTIVE, SrsV1.VERSION).copy(state = CardStateType.REVIEW, intervalDays = 21, consecutiveReviewSuccesses = 2)
        assertTrue(ProgressMetrics.isMastered(base))
        assertFalse(ProgressMetrics.isMastered(base.copy(intervalDays = 20)))
        assertFalse(ProgressMetrics.isMastered(base.copy(consecutiveReviewSuccesses = 1)))
        assertFalse(ProgressMetrics.isMastered(base.copy(skill = CardSkill.LISTENING)))
        assertFalse(ProgressMetrics.isMastered(base.copy(state = CardStateType.SUSPENDED)))
    }

    @Test fun streakKeepsYesterdayUntilTodayEnds() {
        val dates = setOf(today.minusDays(1), today.minusDays(2), today.minusDays(4))
        assertEquals(2, ProgressMetrics.streak(dates, today))
        assertEquals(3, ProgressMetrics.streak(dates + today, today))
        assertEquals(0, ProgressMetrics.streak(setOf(today.minusDays(2)), today))
    }

    @Test fun studyDayAndAccuracyThresholds() {
        assertTrue(ProgressMetrics.isStudyDay(5, 0))
        assertTrue(ProgressMetrics.isStudyDay(0, 1))
        assertFalse(ProgressMetrics.isStudyDay(4, 0))
        assertNull(ProgressMetrics.Accuracy(9, 9).percent)
        assertEquals(80, ProgressMetrics.Accuracy(8, 10).percent)
    }
}

class ReminderCalculatorTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val all = DayOfWeek.entries.toSet()

    @Test fun nextOccurrenceTodayOrTomorrow() {
        val now = LocalDateTime.of(2026, 10, 27, 18, 0).atZone(berlin).toInstant()
        val next = ReminderCalculator.nextOccurrence(now, berlin, LocalTime.of(19, 0), all, null)
        assertEquals(LocalDateTime.of(2026, 10, 27, 19, 0).atZone(berlin).toInstant(), next)
        val afterNotified = ReminderCalculator.nextOccurrence(now, berlin, LocalTime.of(19, 0), all, LocalDate.of(2026, 10, 27))
        assertEquals(LocalDateTime.of(2026, 10, 28, 19, 0).atZone(berlin).toInstant(), afterNotified)
    }

    @Test fun missedTimeIsNotCaughtUp() {
        val now = LocalDateTime.of(2026, 10, 27, 21, 0).atZone(berlin).toInstant()
        assertEquals(LocalDateTime.of(2026, 10, 28, 19, 0).atZone(berlin).toInstant(), ReminderCalculator.nextOccurrence(now, berlin, LocalTime.of(19, 0), all, null))
    }

    /** AT-20: spring-forward gap 02:30 → first valid time 03:00. */
    @Test fun dstGapUsesNextValidTime() {
        val resolved = ReminderCalculator.resolve(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30), berlin)
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"), resolved)
    }

    /** Autumn overlap 02:30 occurs twice; use the first occurrence. */
    @Test fun dstOverlapUsesFirstOccurrence() {
        val resolved = ReminderCalculator.resolve(LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), berlin)
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), resolved)
    }

    @Test fun respectsSelectedDays() {
        val tuesday = LocalDateTime.of(2026, 10, 27, 20, 0).atZone(berlin).toInstant()
        val next = ReminderCalculator.nextOccurrence(tuesday, berlin, LocalTime.of(19, 0), setOf(DayOfWeek.SATURDAY), null)
        assertEquals(LocalDateTime.of(2026, 10, 31, 19, 0).atZone(berlin).toInstant(), next)
        assertNull(ReminderCalculator.nextOccurrence(tuesday, berlin, LocalTime.of(19, 0), emptySet(), null))
    }
}

class TextSpansTest {
    @Test fun codepointSpansSurviveNonBmpCharacters() {
        val text = "😀 Das Buch liegt auf dem Tisch."
        val span = TextSpans.locateUnique(text, "Tisch")!!
        assertEquals("Tisch", TextSpans.extract(text, span))
        assertEquals(CodepointSpan(25, 30), span)
    }

    @Test fun wholeWordsOnly() {
        assertEquals(CodepointSpan(11, 13), TextSpans.locateUnique("Wir wohnen in Berlin.", "in"))
        assertNull(TextSpans.locateUnique("in in", "in"))
        assertNull(TextSpans.locateUnique("Berlin", "in"))
    }
}

package com.vocab5000.feature.study

import com.vocab5000.core.learning.AnswerEvaluator
import com.vocab5000.core.learning.AnswerResult
import com.vocab5000.core.learning.AnswerVerdict
import com.vocab5000.core.learning.AttemptFacts
import com.vocab5000.core.learning.RatingPolicy
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.TypedPrompt

/** Per-prompt answer interaction, shared by sessions, practice and assessment. */
sealed interface AnswerPhase {
    data class Typing(
        val input: String = "",
        val hintShown: Boolean = false,
        /** A near typo allows one retry without revealing the answer. */
        val nearMissRetry: Boolean = false,
        val nearMissUsed: Boolean = false,
    ) : AnswerPhase

    data class TypedFeedback(
        val input: String,
        val result: AnswerResult?,
        val facts: AttemptFacts,
        val allowed: List<Rating>,
        val selected: Rating,
    ) : AnswerPhase {
        val success: Boolean get() = facts.correct || facts.validAnswerOverride
    }

    data class ChoiceFeedback(val chosen: Int?, val correct: Boolean) : AnswerPhase

    data class Flashcard(val revealed: Boolean) : AnswerPhase

    data object Introduction : AnswerPhase
}

object AnswerFlow {
    fun submitTyped(phase: AnswerPhase.Typing, prompt: TypedPrompt, evaluator: AnswerEvaluator): AnswerPhase {
        val result = evaluator.evaluate(phase.input, prompt.answer)
        if (result.verdict == AnswerVerdict.EMPTY) return phase
        if (result.verdict == AnswerVerdict.NEAR_MISS && !phase.nearMissUsed) {
            return phase.copy(nearMissRetry = true, nearMissUsed = true)
        }
        val facts = AttemptFacts(correct = result.verdict.isSuccess, usedHint = phase.hintShown)
        return feedback(phase.input, result, facts)
    }

    fun reveal(phase: AnswerPhase.Typing): AnswerPhase =
        feedback(phase.input, null, AttemptFacts(correct = false, revealedBeforeAnswer = true, usedHint = phase.hintShown))

    fun skip(phase: AnswerPhase.Typing): AnswerPhase =
        feedback(phase.input, null, AttemptFacts(correct = false, skipped = true))

    /** "My answer was correct": recorded locally, capped at Good (spec §7.3). */
    fun overrideCorrect(phase: AnswerPhase.TypedFeedback): AnswerPhase =
        feedback(phase.input, phase.result, phase.facts.copy(validAnswerOverride = true))

    fun select(phase: AnswerPhase.TypedFeedback, rating: Rating): AnswerPhase.TypedFeedback =
        if (rating in phase.allowed) phase.copy(selected = rating) else phase

    private fun feedback(input: String, result: AnswerResult?, facts: AttemptFacts) = AnswerPhase.TypedFeedback(
        input = input,
        result = result,
        facts = facts,
        allowed = RatingPolicy.allowedRatings(facts),
        selected = RatingPolicy.defaultRating(facts),
    )
}

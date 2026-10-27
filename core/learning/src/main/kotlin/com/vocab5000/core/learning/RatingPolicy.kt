package com.vocab5000.core.learning

import com.vocab5000.core.model.Rating

/** Objective facts about one scheduled attempt (spec §8.2). */
data class AttemptFacts(
    val correct: Boolean,
    val skipped: Boolean = false,
    val revealedBeforeAnswer: Boolean = false,
    val usedHint: Boolean = false,
    val validAnswerOverride: Boolean = false,
)

object RatingPolicy {
    /** Highest rating the learner may choose for these facts. */
    fun maxRating(facts: AttemptFacts): Rating = when {
        facts.skipped || facts.revealedBeforeAnswer -> Rating.AGAIN
        facts.validAnswerOverride -> Rating.GOOD
        !facts.correct -> Rating.AGAIN
        facts.usedHint -> Rating.HARD
        else -> Rating.EASY
    }

    /** Rating preselected for the learner. */
    fun defaultRating(facts: AttemptFacts): Rating = when (val max = maxRating(facts)) {
        Rating.EASY -> Rating.GOOD
        else -> max
    }

    /** Ratings the learner may choose; never more generous than [maxRating]. */
    fun allowedRatings(facts: AttemptFacts): List<Rating> {
        val max = maxRating(facts)
        if (max == Rating.AGAIN) return listOf(Rating.AGAIN)
        return Rating.entries.filter { it != Rating.AGAIN && it.ordinal <= max.ordinal }
    }

    fun clamp(chosen: Rating, facts: AttemptFacts): Rating {
        val max = maxRating(facts)
        if (max == Rating.AGAIN) return Rating.AGAIN
        return if (chosen.ordinal > max.ordinal) max else chosen
    }

    /** Flashcards: self-grade after reveal; reveal is the expected path here. */
    fun flashcardSuccess(rating: Rating): Boolean = rating == Rating.GOOD || rating == Rating.EASY
}

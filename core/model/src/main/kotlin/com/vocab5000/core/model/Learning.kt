package com.vocab5000.core.model

enum class CardSkill(val suffix: String) {
    PRODUCTIVE("productive"),
    LISTENING("listening"),
}

enum class CardStateType { NEW, LEARNING, REVIEW, RELEARNING, SUSPENDED }

enum class Rating { AGAIN, HARD, GOOD, EASY }

fun cardIdFor(entryId: String, skill: CardSkill): String = "$entryId#${skill.suffix}"

/**
 * Scheduling state of one card. Ease is stored in permille (2500 == 2.5) so that all
 * interval arithmetic is exact integer math and reproducible on every platform.
 */
data class CardState(
    val cardId: String,
    val entryId: String,
    val skill: CardSkill,
    val state: CardStateType,
    val step: Int,
    val easePermille: Int,
    val intervalDays: Int,
    val recoveryIntervalDays: Int,
    val dueAt: Long,
    val lapses: Int,
    val consecutiveReviewSuccesses: Int,
    val priorState: CardStateType?,
    val schedulerVersion: String,
) {
    val ease: Double get() = easePermille / 1000.0

    companion object {
        const val DEFAULT_EASE_PERMILLE = 2500

        fun newCard(entryId: String, skill: CardSkill, schedulerVersion: String) = CardState(
            cardId = cardIdFor(entryId, skill),
            entryId = entryId,
            skill = skill,
            state = CardStateType.NEW,
            step = 0,
            easePermille = DEFAULT_EASE_PERMILLE,
            intervalDays = 0,
            recoveryIntervalDays = 0,
            dueAt = 0,
            lapses = 0,
            consecutiveReviewSuccesses = 0,
            priorState = null,
            schedulerVersion = schedulerVersion,
        )
    }
}

enum class ExerciseMode { INTRODUCTION, RECOGNITION, TYPED_RECALL, LISTENING, CLOZE, GRAMMAR, FLASHCARD }

enum class SessionMode {
    /** Due work first, then new entries if the daily allowance remains. */
    MIXED,
    /** Learn new entries, interleaving their first recall steps. */
    LEARN_NEW,
    /** Only due reviews. */
    REVIEW_ONLY,
}

/** How scheduled productive cards are asked. Flashcard mode is the accessible alternative. */
enum class ScheduledAnswerStyle { TYPED, FLASHCARD }

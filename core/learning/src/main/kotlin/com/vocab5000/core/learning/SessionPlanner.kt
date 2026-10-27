package com.vocab5000.core.learning

import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.SessionMode

sealed interface PlannedItem {
    val entryId: String

    data class Review(val card: CardState) : PlannedItem {
        override val entryId: String get() = card.entryId
    }

    data class Introduce(override val entryId: String) : PlannedItem
}

/** Snapshot of what is eligible right now. All cards are active, unlocked and not suspended. */
data class PlannerInput(
    val now: Long,
    val mode: SessionMode,
    val cards: List<CardState>,
    /** Next not-yet-introduced entries in curriculum order for the selected band. */
    val newCandidates: List<String>,
    /** min(daily allowance remaining, per-session cap remaining). */
    val newAllowance: Int,
    val gradedCount: Int,
    val maxGraded: Int = SessionPlanner.DEFAULT_MAX_GRADED,
    val lastEntryId: String? = null,
)

/**
 * Chooses the next prompt (spec §8.4). Pure and deterministic: items are generated one at
 * a time so that a card introduced or failed earlier in the session is asked only once it
 * is actually due.
 */
object SessionPlanner {
    const val DEFAULT_MAX_GRADED = 20
    const val MAX_NEW_PER_SESSION = 5
    const val BACKLOG_WARNING_THRESHOLD = 100

    private val dueOrder = compareBy<CardState>({ it.dueAt }, { it.cardId })

    fun dueCards(cards: List<CardState>, now: Long): Pair<List<CardState>, List<CardState>> {
        val due = cards.filter { it.state != CardStateType.SUSPENDED && it.state != CardStateType.NEW && it.dueAt <= now }
        val learning = due.filter { it.state == CardStateType.LEARNING || it.state == CardStateType.RELEARNING }.sortedWith(dueOrder)
        val review = due.filter { it.state == CardStateType.REVIEW }.sortedWith(dueOrder)
        return learning to review
    }

    fun next(input: PlannerInput): PlannedItem? {
        if (input.gradedCount >= input.maxGraded) return null
        val (learning, review) = dueCards(input.cards, input.now)
        val introductions = if (input.mode == SessionMode.REVIEW_ONLY || input.newAllowance <= 0) {
            emptyList()
        } else {
            input.newCandidates.take(1).map { PlannedItem.Introduce(it) }
        }
        val learningItems = learning.map { PlannedItem.Review(it) }
        val reviewItems = review.map { PlannedItem.Review(it) }
        val ordered: List<PlannedItem> = when (input.mode) {
            SessionMode.MIXED -> learningItems + reviewItems + introductions
            SessionMode.LEARN_NEW -> learningItems + introductions + reviewItems
            SessionMode.REVIEW_ONLY -> learningItems + reviewItems
        }
        if (ordered.isEmpty()) return null
        // Avoid adjacent prompts for the same entry if other eligible work exists.
        return ordered.firstOrNull { it.entryId != input.lastEntryId } ?: ordered.first()
    }

    /** Earliest pending short step, for "Next review in about …" in the summary. */
    fun nextShortStepDue(cards: List<CardState>, now: Long): Long? = cards
        .filter { (it.state == CardStateType.LEARNING || it.state == CardStateType.RELEARNING) && it.dueAt > now }
        .minOfOrNull { it.dueAt }

    fun shouldWarnAboutBacklog(dueCount: Int): Boolean = dueCount >= BACKLOG_WARNING_THRESHOLD
}

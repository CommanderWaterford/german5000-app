package com.vocab5000.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.SessionMode

/** Learner-specific facts about an entry. Content lives in the separate read-only pack. */
@Entity(tableName = "user_entries")
data class UserEntryEntity(
    @PrimaryKey val entryId: String,
    val introducedAt: Long? = null,
    val introductionLocalDate: String? = null,
    val favorite: Boolean = false,
    val selectedSenseId: String? = null,
)

@Entity(
    tableName = "card_states",
    indices = [Index(value = ["state", "dueAt"]), Index(value = ["entryId"])],
)
data class CardStateEntity(
    @PrimaryKey val cardId: String,
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
    fun toModel() = CardState(
        cardId, entryId, skill, state, step, easePermille, intervalDays, recoveryIntervalDays,
        dueAt, lapses, consecutiveReviewSuccesses, priorState, schedulerVersion,
    )

    companion object {
        fun from(card: CardState) = CardStateEntity(
            card.cardId, card.entryId, card.skill, card.state, card.step, card.easePermille,
            card.intervalDays, card.recoveryIntervalDays, card.dueAt, card.lapses,
            card.consecutiveReviewSuccesses, card.priorState, card.schedulerVersion,
        )
    }
}

/** Immutable record of one graded scheduled answer. */
@Entity(
    tableName = "review_events",
    indices = [
        Index(value = ["attemptId"], unique = true),
        Index(value = ["cardId", "occurredAt"]),
        Index(value = ["localDate"]),
    ],
)
data class ReviewEventEntity(
    @PrimaryKey val eventId: String,
    val attemptId: String,
    val cardId: String,
    val entryId: String,
    val sessionId: String?,
    val promptRevision: Int,
    val contentVersion: String,
    val mode: ExerciseMode,
    val correct: Boolean,
    /** Correct without reveal, or flashcard Good/Easy. */
    val success: Boolean,
    val rating: Rating,
    val usedHint: Boolean,
    val revealed: Boolean,
    val validAnswerOverride: Boolean,
    val overrideReason: String?,
    val beforeState: CardStateType,
    val beforeStep: Int,
    val beforeIntervalDays: Int,
    val beforeEasePermille: Int,
    val beforeDueAt: Long,
    val afterState: CardStateType,
    val afterStep: Int,
    val afterIntervalDays: Int,
    val afterEasePermille: Int,
    val afterDueAt: Long,
    val schedulerVersion: String,
    val occurredAt: Long,
    val localDate: String,
    val zoneId: String,
    val durationMs: Long,
)

enum class SessionStatus { ACTIVE, COMPLETED, ABANDONED }

@Entity(tableName = "sessions", indices = [Index(value = ["status"])])
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val seed: Long,
    val mode: SessionMode,
    val status: SessionStatus,
    val createdAt: Long,
    val completedAt: Long? = null,
    val gradedCount: Int = 0,
    val introducedCount: Int = 0,
    val contentVersion: String,
)

enum class SessionItemStatus { PENDING, ANSWERED, OBSOLETE }

@Entity(
    tableName = "session_items",
    indices = [Index(value = ["sessionId", "position"], unique = true), Index(value = ["attemptId"], unique = true)],
)
data class SessionItemEntity(
    @PrimaryKey val itemId: String,
    val sessionId: String,
    val position: Int,
    val attemptId: String,
    val entryId: String,
    val cardId: String?,
    /** Serialized [com.vocab5000.core.model.Prompt] snapshot kept stable across content upgrades. */
    val promptJson: String,
    val scheduled: Boolean,
    val status: SessionItemStatus,
    val answeredAt: Long? = null,
)

@Entity(tableName = "practice_results", indices = [Index(value = ["localDate"]), Index(value = ["entryId"])])
data class PracticeResultEntity(
    @PrimaryKey val resultId: String,
    val entryId: String,
    val mode: ExerciseMode,
    val correct: Boolean,
    val occurredAt: Long,
    val localDate: String,
)

enum class AssessmentStatus { IN_PROGRESS, COMPLETED, ABANDONED }

/** Placement assessment; stored separately so it never affects SRS, streaks or mastery. */
@Entity(tableName = "assessments")
data class AssessmentEntity(
    @PrimaryKey val assessmentId: String,
    val seed: Long,
    val status: AssessmentStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    /** JSON list of planned items and recorded results. */
    val itemsJson: String,
    val resultsJson: String,
    val recommendedBand: Int? = null,
    val bandScoresJson: String? = null,
)

/** Local cache of a verified Play entitlement. Never imported, never exported. */
@Entity(tableName = "entitlement_cache")
data class EntitlementCacheEntity(
    @PrimaryKey val productId: String,
    val state: String,
    val verificationSource: String,
    val checkedAt: Long,
    val purchaseToken: String?,
    val acknowledged: Boolean,
)

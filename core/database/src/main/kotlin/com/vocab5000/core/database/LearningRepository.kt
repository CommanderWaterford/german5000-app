package com.vocab5000.core.database

import androidx.room.withTransaction
import com.vocab5000.core.learning.AttemptFacts
import com.vocab5000.core.learning.RatingPolicy
import com.vocab5000.core.learning.SrsV1
import com.vocab5000.core.model.AppClock
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.SessionMode
import com.vocab5000.core.model.cardIdFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import kotlin.random.Random

sealed interface CommitResult {
    data class Committed(val card: CardState?) : CommitResult
    /** The attempt was already stored; repeated submissions are no-ops. */
    data object AlreadyCommitted : CommitResult
}

data class ScheduledAnswer(
    val itemId: String,
    val rating: Rating,
    val facts: AttemptFacts,
    val mode: ExerciseMode,
    val overrideReason: String? = null,
    val durationMs: Long,
    val contentVersion: String,
)

/**
 * The only writer of learning state. Each answer is one transaction that stores an
 * immutable review event, updates the card, marks the session item answered and updates
 * session bookkeeping (spec §8.5). A unique attempt ID makes resubmission idempotent.
 */
class LearningRepository(private val db: UserDatabase, private val clock: AppClock) {
    private val entries = db.userEntryDao()
    private val cards = db.cardDao()
    private val events = db.reviewEventDao()
    private val sessions = db.sessionDao()

    val cardStates: Flow<List<CardState>> = cards.observeAll().map { list -> list.map { it.toModel() } }
    val userEntries: Flow<Map<String, UserEntryEntity>> = entries.observeAll().map { list -> list.associateBy { it.entryId } }

    fun observeUserEntry(entryId: String): Flow<UserEntryEntity?> = entries.observe(entryId)
    fun observeCardsForEntry(entryId: String): Flow<List<CardState>> = cards.observeForEntry(entryId).map { l -> l.map { it.toModel() } }

    suspend fun cardsSnapshot(): List<CardState> = cards.all().map { it.toModel() }
    suspend fun introducedIds(): Set<String> = entries.introducedIds().toSet()
    suspend fun introducedToday(): Int = entries.introducedOn(clock.today().toString())
    fun observeIntroducedToday(): Flow<Int> = entries.observeIntroducedOn(clock.today().toString())
    fun observeGradedToday(): Flow<Int> = events.observeGradedOn(clock.today().toString())

    // ---- Sessions -------------------------------------------------------------------

    suspend fun activeSession(): SessionEntity? = sessions.activeSession()
    fun observeActiveSession(): Flow<SessionEntity?> = sessions.observeActiveSession()
    suspend fun session(sessionId: String): SessionEntity? = sessions.session(sessionId)

    suspend fun startSession(mode: SessionMode, contentVersion: String): SessionEntity = db.withTransaction {
        sessions.activeSession()?.let { sessions.setStatus(it.sessionId, SessionStatus.ABANDONED, clock.nowMillis()) }
        val session = SessionEntity(
            sessionId = UUID.randomUUID().toString(),
            seed = Random.nextLong(),
            mode = mode,
            status = SessionStatus.ACTIVE,
            createdAt = clock.nowMillis(),
            contentVersion = contentVersion,
        )
        sessions.insertSession(session)
        session
    }

    suspend fun pendingItem(sessionId: String): SessionItemEntity? = sessions.pendingItem(sessionId)
    suspend fun sessionItems(sessionId: String): List<SessionItemEntity> = sessions.items(sessionId)

    suspend fun appendItem(sessionId: String, entryId: String, cardId: String?, promptJson: String, scheduled: Boolean): SessionItemEntity =
        db.withTransaction {
            sessions.pendingItem(sessionId)?.let { return@withTransaction it }
            val position = sessions.maxPosition(sessionId) + 1
            val item = SessionItemEntity(
                itemId = "$sessionId:$position",
                sessionId = sessionId,
                position = position,
                attemptId = UUID.randomUUID().toString(),
                entryId = entryId,
                cardId = cardId,
                promptJson = promptJson,
                scheduled = scheduled,
                status = SessionItemStatus.PENDING,
            )
            sessions.insertItem(item)
            item
        }

    /** Ends an unanswered prompt made obsolete by a content upgrade, without a review. */
    suspend fun markItemObsolete(itemId: String) = sessions.setItemStatus(itemId, SessionItemStatus.OBSOLETE, null)

    suspend fun completeSession(sessionId: String) = sessions.setStatus(sessionId, SessionStatus.COMPLETED, clock.nowMillis())

    // ---- Answers ------------------------------------------------------------------------

    /** First presentation of a new entry. Creates the productive card in learning step 0. */
    suspend fun commitIntroduction(itemId: String): CommitResult = db.withTransaction {
        val item = sessions.item(itemId) ?: error("Unknown session item $itemId")
        if (item.status != SessionItemStatus.PENDING) return@withTransaction CommitResult.AlreadyCommitted
        val now = clock.nowMillis()
        val existing = entries.get(item.entryId)
        val cardId = cardIdFor(item.entryId, CardSkill.PRODUCTIVE)
        val card = cards.get(cardId)?.toModel()
        val introduced = if (card == null || card.state == CardStateType.NEW) {
            SrsV1.introduce(card ?: CardState.newCard(item.entryId, CardSkill.PRODUCTIVE, SrsV1.VERSION), now)
                .also { cards.upsert(CardStateEntity.from(it)) }
        } else {
            card
        }
        if (existing?.introducedAt == null) {
            entries.upsert(
                (existing ?: UserEntryEntity(item.entryId)).copy(
                    introducedAt = now,
                    introductionLocalDate = clock.today().toString(),
                ),
            )
            sessions.incrementIntroduced(item.sessionId)
        }
        sessions.setItemStatus(itemId, SessionItemStatus.ANSWERED, now)
        CommitResult.Committed(introduced)
    }

    suspend fun commitScheduledAnswer(answer: ScheduledAnswer): CommitResult = db.withTransaction {
        val item = sessions.item(answer.itemId) ?: error("Unknown session item ${answer.itemId}")
        if (item.status != SessionItemStatus.PENDING || events.attemptExists(item.attemptId)) {
            return@withTransaction CommitResult.AlreadyCommitted
        }
        val cardId = item.cardId ?: error("Scheduled item ${item.itemId} has no card")
        val before = cards.get(cardId)?.toModel() ?: error("Unknown card $cardId")
        val now = clock.nowMillis()
        val rating = RatingPolicy.clamp(answer.rating, answer.facts)
        val after = SrsV1.schedule(before, rating, now)
        val success = if (answer.mode == ExerciseMode.FLASHCARD) {
            RatingPolicy.flashcardSuccess(rating)
        } else {
            answer.facts.correct && !answer.facts.revealedBeforeAnswer && !answer.facts.skipped
        }
        events.insert(
            ReviewEventEntity(
                eventId = UUID.randomUUID().toString(),
                attemptId = item.attemptId,
                cardId = cardId,
                entryId = item.entryId,
                sessionId = item.sessionId,
                promptRevision = 0,
                contentVersion = answer.contentVersion,
                mode = answer.mode,
                correct = answer.facts.correct,
                success = success,
                rating = rating,
                usedHint = answer.facts.usedHint,
                revealed = answer.facts.revealedBeforeAnswer,
                validAnswerOverride = answer.facts.validAnswerOverride,
                overrideReason = answer.overrideReason,
                beforeState = before.state,
                beforeStep = before.step,
                beforeIntervalDays = before.intervalDays,
                beforeEasePermille = before.easePermille,
                beforeDueAt = before.dueAt,
                afterState = after.state,
                afterStep = after.step,
                afterIntervalDays = after.intervalDays,
                afterEasePermille = after.easePermille,
                afterDueAt = after.dueAt,
                schedulerVersion = SrsV1.VERSION,
                occurredAt = now,
                localDate = clock.today().toString(),
                zoneId = clock.zone().id,
                durationMs = answer.durationMs.coerceAtLeast(0),
            ),
        )
        cards.upsert(CardStateEntity.from(after))
        sessions.setItemStatus(item.itemId, SessionItemStatus.ANSWERED, now)
        sessions.incrementGraded(item.sessionId)
        CommitResult.Committed(after)
    }

    /** Practice answers never change scheduling. */
    suspend fun recordPractice(entryId: String, mode: ExerciseMode, correct: Boolean) {
        db.practiceDao().insert(
            PracticeResultEntity(UUID.randomUUID().toString(), entryId, mode, correct, clock.nowMillis(), clock.today().toString()),
        )
    }

    // ---- Learner controls -----------------------------------------------------------------

    suspend fun setFavorite(entryId: String, favorite: Boolean) = db.withTransaction {
        entries.upsert((entries.get(entryId) ?: UserEntryEntity(entryId)).copy(favorite = favorite))
    }

    suspend fun suspendCard(cardId: String) = db.withTransaction {
        cards.get(cardId)?.toModel()?.let { cards.upsert(CardStateEntity.from(SrsV1.suspend(it))) }
    }

    suspend fun unsuspendCard(cardId: String) = db.withTransaction {
        cards.get(cardId)?.toModel()?.let { cards.upsert(CardStateEntity.from(SrsV1.unsuspend(it, clock.nowMillis()))) }
    }

    /** Adds listening cards for introduced entries; does not count as new introductions. */
    suspend fun ensureListeningCards(): Int = db.withTransaction {
        val now = clock.nowMillis()
        var created = 0
        for (entryId in entries.introducedIds()) {
            val id = cardIdFor(entryId, CardSkill.LISTENING)
            if (cards.get(id) == null) {
                cards.upsert(CardStateEntity.from(SrsV1.startAdditionalSkill(CardState.newCard(entryId, CardSkill.LISTENING, SrsV1.VERSION), now)))
                created++
            }
        }
        created
    }

    /** Called after an entry is introduced while listening practice is enabled. */
    suspend fun ensureListeningCard(entryId: String) = db.withTransaction {
        val id = cardIdFor(entryId, CardSkill.LISTENING)
        if (cards.get(id) == null) {
            cards.upsert(CardStateEntity.from(SrsV1.startAdditionalSkill(CardState.newCard(entryId, CardSkill.LISTENING, SrsV1.VERSION), clock.nowMillis())))
        }
    }

    /** Deletes learning, session and assessment history and favorites; keeps entitlement. */
    suspend fun resetLearningProgress() = db.withTransaction { clearLearningTables() }

    /** Clears everything local including the entitlement cache (restorable from Play). */
    suspend fun deleteAllLocalData() = db.withTransaction {
        clearLearningTables()
        db.entitlementDao().deleteAll()
    }

    // ---- Backup ----------------------------------------------------------------------------

    /** Receives validated backup records in bounded batches during a replace import. */
    interface ImportSink {
        suspend fun userEntries(batch: List<UserEntryEntity>)
        suspend fun cards(batch: List<CardStateEntity>)
        suspend fun reviewEvents(batch: List<ReviewEventEntity>)
    }

    /**
     * Replaces all learning data atomically. If [block] throws, the transaction rolls back
     * and the previous data is untouched (spec §13.3). Entitlement data is never imported.
     */
    suspend fun replaceLearningData(block: suspend (ImportSink) -> Unit) = db.withTransaction {
        clearLearningTables()
        block(
            object : ImportSink {
                override suspend fun userEntries(batch: List<UserEntryEntity>) = entries.insertAll(batch)
                override suspend fun cards(batch: List<CardStateEntity>) = cards.insertAll(batch)
                override suspend fun reviewEvents(batch: List<ReviewEventEntity>) = events.insertAll(batch)
            },
        )
    }

    suspend fun exportUserEntries(): List<UserEntryEntity> = entries.all()
    suspend fun exportCards(): List<CardStateEntity> = cards.all()
    suspend fun reviewEventCount(): Int = events.count()
    suspend fun reviewEventPage(limit: Int, offset: Int): List<ReviewEventEntity> = events.page(limit, offset)

    internal suspend fun clearLearningTables() {
        events.deleteAll()
        cards.deleteAll()
        entries.deleteAll()
        sessions.deleteAllItems()
        sessions.deleteAllSessions()
        db.practiceDao().deleteAll()
        db.assessmentDao().deleteAll()
    }
}

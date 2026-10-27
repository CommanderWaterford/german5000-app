package com.vocab5000.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class DateCount(val localDate: String, val count: Int)

@Dao
interface UserEntryDao {
    @Query("SELECT * FROM user_entries")
    fun observeAll(): Flow<List<UserEntryEntity>>

    @Query("SELECT * FROM user_entries")
    suspend fun all(): List<UserEntryEntity>

    @Query("SELECT * FROM user_entries WHERE entryId = :entryId")
    suspend fun get(entryId: String): UserEntryEntity?

    @Query("SELECT * FROM user_entries WHERE entryId = :entryId")
    fun observe(entryId: String): Flow<UserEntryEntity?>

    @Upsert
    suspend fun upsert(entity: UserEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<UserEntryEntity>)

    @Query("SELECT entryId FROM user_entries WHERE introducedAt IS NOT NULL")
    suspend fun introducedIds(): List<String>

    @Query("SELECT COUNT(*) FROM user_entries WHERE introductionLocalDate = :localDate")
    fun observeIntroducedOn(localDate: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_entries WHERE introductionLocalDate = :localDate")
    suspend fun introducedOn(localDate: String): Int

    @Query("SELECT introductionLocalDate AS localDate, COUNT(*) AS count FROM user_entries WHERE introductionLocalDate IS NOT NULL GROUP BY introductionLocalDate")
    fun observeIntroductionsByDate(): Flow<List<DateCount>>

    @Query("UPDATE user_entries SET favorite = 0")
    suspend fun clearFavorites()

    @Query("DELETE FROM user_entries")
    suspend fun deleteAll()
}

@Dao
interface CardDao {
    @Query("SELECT * FROM card_states")
    fun observeAll(): Flow<List<CardStateEntity>>

    @Query("SELECT * FROM card_states")
    suspend fun all(): List<CardStateEntity>

    @Query("SELECT * FROM card_states WHERE cardId = :cardId")
    suspend fun get(cardId: String): CardStateEntity?

    @Query("SELECT * FROM card_states WHERE entryId = :entryId")
    fun observeForEntry(entryId: String): Flow<List<CardStateEntity>>

    @Upsert
    suspend fun upsert(entity: CardStateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<CardStateEntity>)

    @Query("DELETE FROM card_states")
    suspend fun deleteAll()
}

@Dao
interface ReviewEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: ReviewEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(events: List<ReviewEventEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM review_events WHERE attemptId = :attemptId)")
    suspend fun attemptExists(attemptId: String): Boolean

    @Query("SELECT COUNT(*) FROM review_events")
    suspend fun count(): Int

    @Query("SELECT * FROM review_events ORDER BY occurredAt, eventId LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ReviewEventEntity>

    @Query("SELECT localDate, COUNT(*) AS count FROM review_events GROUP BY localDate")
    fun observeGradedByDate(): Flow<List<DateCount>>

    @Query("SELECT COUNT(*) FROM review_events WHERE localDate = :localDate")
    suspend fun gradedOn(localDate: String): Int

    @Query("SELECT COUNT(*) FROM review_events WHERE localDate = :localDate")
    fun observeGradedOn(localDate: String): Flow<Int>

    /** First review-state attempt per card per local date within the window (spec §10). */
    @Query(
        """
        SELECT e.success FROM review_events e
        WHERE e.beforeState = 'REVIEW' AND e.localDate >= :fromDate
          AND e.occurredAt = (
            SELECT MIN(x.occurredAt) FROM review_events x
            WHERE x.cardId = e.cardId AND x.localDate = e.localDate AND x.beforeState = 'REVIEW'
          )
        """,
    )
    fun observeFirstReviewAttemptsSince(fromDate: String): Flow<List<Boolean>>

    @Query("DELETE FROM review_events")
    suspend fun deleteAll()
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1")
    suspend fun activeSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1")
    fun observeActiveSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun session(sessionId: String): SessionEntity?

    @Query("UPDATE sessions SET status = :status, completedAt = :completedAt WHERE sessionId = :sessionId")
    suspend fun setStatus(sessionId: String, status: SessionStatus, completedAt: Long?)

    @Query("UPDATE sessions SET gradedCount = gradedCount + 1 WHERE sessionId = :sessionId")
    suspend fun incrementGraded(sessionId: String)

    @Query("UPDATE sessions SET introducedCount = introducedCount + 1 WHERE sessionId = :sessionId")
    suspend fun incrementIntroduced(sessionId: String)

    @Insert
    suspend fun insertItem(item: SessionItemEntity)

    @Query("SELECT * FROM session_items WHERE sessionId = :sessionId ORDER BY position")
    suspend fun items(sessionId: String): List<SessionItemEntity>

    @Query("SELECT * FROM session_items WHERE sessionId = :sessionId AND status = 'PENDING' ORDER BY position LIMIT 1")
    suspend fun pendingItem(sessionId: String): SessionItemEntity?

    @Query("SELECT * FROM session_items WHERE itemId = :itemId")
    suspend fun item(itemId: String): SessionItemEntity?

    @Query("UPDATE session_items SET status = :status, answeredAt = :answeredAt WHERE itemId = :itemId")
    suspend fun setItemStatus(itemId: String, status: SessionItemStatus, answeredAt: Long?)

    @Query("SELECT COALESCE(MAX(position), -1) FROM session_items WHERE sessionId = :sessionId")
    suspend fun maxPosition(sessionId: String): Int

    @Query("DELETE FROM session_items")
    suspend fun deleteAllItems()

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}

@Dao
interface PracticeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(result: PracticeResultEntity)

    @Query("SELECT COUNT(*) FROM practice_results WHERE localDate = :localDate")
    fun observeCountOn(localDate: String): Flow<Int>

    @Query("DELETE FROM practice_results")
    suspend fun deleteAll()
}

@Dao
interface AssessmentDao {
    @Upsert
    suspend fun upsert(entity: AssessmentEntity)

    @Query("SELECT * FROM assessments WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    suspend fun inProgress(): AssessmentEntity?

    @Query("SELECT * FROM assessments WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT 1")
    fun observeLatestCompleted(): Flow<AssessmentEntity?>

    @Query("SELECT * FROM assessments WHERE status = 'COMPLETED' ORDER BY completedAt DESC LIMIT 1")
    suspend fun latestCompleted(): AssessmentEntity?

    @Query("DELETE FROM assessments")
    suspend fun deleteAll()
}

@Dao
interface EntitlementDao {
    @Query("SELECT * FROM entitlement_cache WHERE productId = :productId")
    fun observe(productId: String): Flow<EntitlementCacheEntity?>

    @Query("SELECT * FROM entitlement_cache WHERE productId = :productId")
    suspend fun get(productId: String): EntitlementCacheEntity?

    @Upsert
    suspend fun upsert(entity: EntitlementCacheEntity)

    @Query("DELETE FROM entitlement_cache")
    suspend fun deleteAll()
}

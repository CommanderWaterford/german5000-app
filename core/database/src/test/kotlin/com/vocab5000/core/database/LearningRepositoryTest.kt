package com.vocab5000.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vocab5000.core.learning.AttemptFacts
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.FixedClock
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.SessionMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LearningRepositoryTest {
    private lateinit var db: UserDatabase
    private lateinit var repo: LearningRepository
    private val clock = FixedClock(1_800_000_000_000L)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LearningRepository(db, clock)
    }

    @After fun tearDown() = db.close()

    private suspend fun introduce(entryId: String, sessionId: String): String {
        val item = repo.appendItem(sessionId, entryId, "$entryId#productive", "{}", scheduled = true)
        repo.commitIntroduction(item.itemId)
        return "$entryId#productive"
    }

    private fun answer(itemId: String, rating: Rating = Rating.GOOD) = ScheduledAnswer(
        itemId = itemId, rating = rating, facts = AttemptFacts(correct = true), mode = ExerciseMode.TYPED_RECALL, durationMs = 1200, contentVersion = "t",
    )

    /** AT-03: an answer submitted twice produces one event, one transition, one answered item. */
    @Test fun repeatedSubmissionIsIdempotent() = runBlocking {
        val session = repo.startSession(SessionMode.LEARN_NEW, "t")
        val cardId = introduce("de.noun.tisch.01", session.sessionId)
        val item = repo.appendItem(session.sessionId, "de.noun.tisch.01", cardId, "{}", scheduled = true)
        val results = (1..3).map { async { repo.commitScheduledAnswer(answer(item.itemId)) } }.awaitAll()
        assertEquals(1, results.count { it is CommitResult.Committed })
        assertEquals(1, db.reviewEventDao().count())
        val card = db.cardDao().get(cardId)!!
        assertEquals(1, card.step)
        assertEquals(1, repo.session(session.sessionId)!!.gradedCount)
    }

    /** AT-04: after a committed answer, a restored session has no pending item and correct counts. */
    @Test fun committedAnswerSurvivesRestart() = runBlocking {
        val session = repo.startSession(SessionMode.LEARN_NEW, "t")
        val cardId = introduce("de.noun.tisch.01", session.sessionId)
        val item = repo.appendItem(session.sessionId, "de.noun.tisch.01", cardId, "{}", scheduled = true)
        repo.commitScheduledAnswer(answer(item.itemId))
        val reopened = LearningRepository(db, clock)
        assertEquals(session.sessionId, reopened.activeSession()!!.sessionId)
        assertNull(reopened.pendingItem(session.sessionId))
        assertEquals(1, reopened.session(session.sessionId)!!.gradedCount)
        assertEquals(1, reopened.session(session.sessionId)!!.introducedCount)
    }

    @Test fun pendingItemIsReturnedInsteadOfPlanningANewOne() = runBlocking {
        val session = repo.startSession(SessionMode.MIXED, "t")
        val first = repo.appendItem(session.sessionId, "a", null, "{}", scheduled = true)
        val second = repo.appendItem(session.sessionId, "b", null, "{}", scheduled = true)
        assertEquals(first.itemId, second.itemId)
    }

    /** AT-19-style rollback: a failure inside the transaction leaves no partial writes. */
    @Test fun failedCommitLeavesNothingBehind() = runBlocking {
        val session = repo.startSession(SessionMode.MIXED, "t")
        val item = repo.appendItem(session.sessionId, "missing", "missing#productive", "{}", scheduled = true)
        val failure = runCatching { repo.commitScheduledAnswer(answer(item.itemId)) }
        assertTrue(failure.isFailure)
        assertEquals(0, db.reviewEventDao().count())
        assertEquals(SessionItemStatus.PENDING, repo.pendingItem(session.sessionId)!!.status)
    }

    @Test fun introductionCountsOncePerEntryAndDate() = runBlocking {
        val session = repo.startSession(SessionMode.LEARN_NEW, "t")
        introduce("de.noun.tisch.01", session.sessionId)
        assertEquals(1, repo.introducedToday())
        val card = db.cardDao().get("de.noun.tisch.01#productive")!!
        assertEquals(CardStateType.LEARNING, card.state)
        assertEquals(clock.millis, card.dueAt)
    }

    @Test fun resetKeepsEntitlementButDeleteAllClearsIt() = runBlocking {
        val session = repo.startSession(SessionMode.LEARN_NEW, "t")
        introduce("x", session.sessionId)
        db.entitlementDao().upsert(EntitlementCacheEntity("p", "premium_verified", "SIGNATURE", 0, "tok", true))
        repo.resetLearningProgress()
        assertEquals(0, repo.cardsSnapshot().size)
        assertEquals("premium_verified", db.entitlementDao().get("p")!!.state)
        repo.deleteAllLocalData()
        assertNull(db.entitlementDao().get("p"))
    }
}

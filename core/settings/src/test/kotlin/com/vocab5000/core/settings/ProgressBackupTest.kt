package com.vocab5000.core.settings

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.vocab5000.core.database.CommitResult
import com.vocab5000.core.database.EntitlementCacheEntity
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.database.ScheduledAnswer
import com.vocab5000.core.database.UserDatabase
import com.vocab5000.core.learning.AttemptFacts
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.FixedClock
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.SessionMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressBackupTest {
    private lateinit var db: UserDatabase
    private lateinit var repo: LearningRepository
    private val clock = FixedClock(1_800_000_000_000L)
    private val identity = BackupIdentity("vocab5000", "german5000", "de-DE", "0.1.0")
    private val backup = ProgressBackup(identity, setOf("de.noun.tisch.01")) { clock.millis }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UserDatabase::class.java).allowMainThreadQueries().build()
        repo = LearningRepository(db, clock)
    }

    @After fun tearDown() = db.close()

    private suspend fun studySomething() {
        val session = repo.startSession(SessionMode.LEARN_NEW, "t")
        val intro = repo.appendItem(session.sessionId, "de.noun.tisch.01", "de.noun.tisch.01#productive", "{}", true)
        repo.commitIntroduction(intro.itemId)
        repo.setFavorite("de.noun.tisch.01", true)
        val item = repo.appendItem(session.sessionId, "de.noun.tisch.01", "de.noun.tisch.01#productive", "{}", true)
        val r = repo.commitScheduledAnswer(ScheduledAnswer(item.itemId, Rating.GOOD, AttemptFacts(true), ExerciseMode.TYPED_RECALL, durationMs = 900, contentVersion = "t"))
        assertTrue(r is CommitResult.Committed)
    }

    private suspend fun exportBytes(): ByteArray = ByteArrayOutputStream().also { backup.export(it, repo, UserPreferences(dailyNewGoal = 15)) }.toByteArray()

    @Test fun roundTripRestoresHistoryButNeverEntitlement() = runBlocking {
        studySomething()
        db.entitlementDao().upsert(EntitlementCacheEntity("german5000_premium_lifetime", "premium_verified", "SIGNATURE", 0, "secret-token", true))
        val bytes = exportBytes()
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue("purchase tokens must never be exported", "secret-token" !in text)

        repo.deleteAllLocalData()
        val preview = backup.validate(ByteArrayInputStream(bytes))
        assertEquals(1, preview.introducedEntries)
        assertEquals(1, preview.reviewEvents)
        assertEquals(1, preview.favorites)
        assertEquals(15, preview.preferences.dailyNewGoal)

        backup.apply(ByteArrayInputStream(bytes), repo)
        assertEquals(1, repo.reviewEventCount())
        assertEquals(1, repo.cardsSnapshot().size)
        assertTrue(repo.exportUserEntries().single().favorite)
        // AT-15: importing never grants premium.
        assertEquals(null, db.entitlementDao().get("german5000_premium_lifetime"))
    }

    /** AT-14: corrupted or wrong-language backups are rejected and local data is untouched. */
    @Test fun invalidBackupsChangeNothing() = runBlocking {
        studySomething()
        val good = exportBytes().toString(Charsets.UTF_8)
        val cases = listOf(
            "not json at all",
            good.replace("\"de-DE\"", "\"es-ES\""),
            good.replace("\"easePermille\":2500", "\"easePermille\":99999"),
            good.substring(0, good.length / 2),
        )
        for (bad in cases) {
            try {
                backup.validate(ByteArrayInputStream(bad.toByteArray()))
                fail("expected rejection for: ${bad.take(40)}")
            } catch (expected: BackupValidationException) {
                // ok
            }
            val applied = runCatching { backup.apply(ByteArrayInputStream(bad.toByteArray()), repo) }
            assertTrue(applied.isFailure)
            assertEquals(1, repo.reviewEventCount())
            assertEquals(1, repo.cardsSnapshot().size)
        }
    }

    @Test fun duplicateEventsAreRejected() = runBlocking {
        studySomething()
        val good = exportBytes().toString(Charsets.UTF_8)
        val start = good.indexOf("\"reviewEvents\":[") + "\"reviewEvents\":[".length
        val end = good.lastIndexOf("]}")
        val event = good.substring(start, end)
        val duplicated = good.substring(0, start) + event + "," + event + good.substring(end)
        val result = runCatching { backup.validate(ByteArrayInputStream(duplicated.toByteArray())) }
        assertTrue(result.exceptionOrNull() is BackupValidationException)
    }
}

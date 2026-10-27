package com.vocab5000.feature.study

import com.vocab5000.core.content.StudyCatalog
import com.vocab5000.core.database.CommitResult
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.database.ScheduledAnswer
import com.vocab5000.core.database.SessionEntity
import com.vocab5000.core.database.SessionItemEntity
import com.vocab5000.core.database.SessionItemStatus
import com.vocab5000.core.learning.PlannedItem
import com.vocab5000.core.learning.PlannerInput
import com.vocab5000.core.learning.SessionPlanner
import com.vocab5000.core.model.AppClock
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.Prompt
import com.vocab5000.core.model.SessionMode
import com.vocab5000.core.model.cardIdFor
import com.vocab5000.core.settings.PreferencesRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

data class ActiveItem(val item: SessionItemEntity, val prompt: Prompt, val entry: Entry, val card: CardState?)

data class SessionSummary(val graded: Int, val introduced: Int, val nextShortStepAt: Long?, val dueRemaining: Int)

/**
 * Drives one study session. Items are planned lazily and persisted with a prompt snapshot
 * before display, so a restored session shows the same next unanswered prompt.
 */
class SessionEngine(
    private val catalog: StudyCatalog,
    private val repository: LearningRepository,
    private val preferences: PreferencesRepository,
    private val premium: StateFlow<Boolean>,
    private val clock: AppClock,
) {
    private val json = Json { classDiscriminator = "type" }

    suspend fun resumeOrStart(mode: SessionMode?): SessionEntity {
        val active = repository.activeSession()
        if (active != null && (mode == null || active.mode == mode)) return active
        return repository.startSession(mode ?: SessionMode.MIXED, catalog.content.manifest.contentVersion)
    }

    suspend fun session(id: String) = repository.session(id)

    suspend fun nextItem(session: SessionEntity): ActiveItem? {
        while (true) {
            val pending = repository.pendingItem(session.sessionId) ?: break
            val resolved = resolve(pending)
            if (resolved != null) return resolved
            // Content upgrade made the snapshot obsolete: end it without fabricating a review.
            repository.markItemObsolete(pending.itemId)
        }
        val fresh = repository.session(session.sessionId) ?: return null
        val planned = plan(fresh) ?: return null
        val entry = catalog.content.requireEntry(planned.entryId)
        val prefs = preferences.current()
        val (prompt, cardId) = when (planned) {
            is PlannedItem.Introduce -> {
                val cardId = cardIdFor(entry.entryId, CardSkill.PRODUCTIVE)
                catalog.promptFactory.introduction(entry, CardState.newCard(entry.entryId, CardSkill.PRODUCTIVE, "")) to cardId
            }
            is PlannedItem.Review -> catalog.promptFactory.scheduled(entry, planned.card, prefs.answerStyle) to planned.card.cardId
        }
        val item = repository.appendItem(
            sessionId = session.sessionId,
            entryId = entry.entryId,
            cardId = cardId,
            promptJson = json.encodeToString(Prompt.serializer(), prompt),
            scheduled = true,
        )
        return resolve(item)
    }

    private suspend fun resolve(item: SessionItemEntity): ActiveItem? {
        if (item.status != SessionItemStatus.PENDING) return null
        val entry = catalog.content.entry(item.entryId) ?: return null
        val prompt = runCatching { json.decodeFromString(Prompt.serializer(), item.promptJson) }.getOrNull() ?: return null
        if (prompt.promptRevision != entry.revision) return null
        if (!catalog.isUnlocked(entry, premium.value)) return null
        val card = item.cardId?.let { id -> repository.cardsSnapshot().firstOrNull { it.cardId == id } }
        return ActiveItem(item, prompt, entry, card)
    }

    private suspend fun plan(session: SessionEntity): PlannedItem? {
        val prefs = preferences.current()
        val premiumNow = premium.value
        val cards = catalog.schedulableCards(repository.cardsSnapshot(), premiumNow, prefs.listeningEnabled)
        val introducedToday = repository.introducedToday()
        val allowance = if (prefs.newWordsPaused) {
            0
        } else {
            minOf(prefs.dailyNewGoal - introducedToday, SessionPlanner.MAX_NEW_PER_SESSION - session.introducedCount)
        }
        val candidates = if (allowance > 0) {
            catalog.newCandidates(repository.introducedIds(), premiumNow, prefs.selectedBand, limit = 1)
        } else {
            emptyList()
        }
        val last = repository.sessionItems(session.sessionId).lastOrNull { it.status == SessionItemStatus.ANSWERED }
        return SessionPlanner.next(
            PlannerInput(
                now = clock.nowMillis(),
                mode = session.mode,
                cards = cards,
                newCandidates = candidates,
                newAllowance = allowance,
                gradedCount = session.gradedCount,
                lastEntryId = last?.entryId,
            ),
        )
    }

    suspend fun commitIntroduction(item: SessionItemEntity): CommitResult {
        val result = repository.commitIntroduction(item.itemId)
        if (preferences.current().listeningEnabled) repository.ensureListeningCard(item.entryId)
        return result
    }

    suspend fun commitAnswer(answer: ScheduledAnswer): CommitResult = repository.commitScheduledAnswer(answer)

    suspend fun summary(session: SessionEntity): SessionSummary {
        val fresh = repository.session(session.sessionId) ?: session
        val prefs = preferences.current()
        val cards = catalog.schedulableCards(repository.cardsSnapshot(), premium.value, prefs.listeningEnabled)
        val now = clock.nowMillis()
        return SessionSummary(
            graded = fresh.gradedCount,
            introduced = fresh.introducedCount,
            nextShortStepAt = SessionPlanner.nextShortStepDue(cards, now),
            dueRemaining = cards.count { it.dueAt <= now && it.state != com.vocab5000.core.model.CardStateType.NEW },
        )
    }

    suspend fun complete(session: SessionEntity) = repository.completeSession(session.sessionId)
}

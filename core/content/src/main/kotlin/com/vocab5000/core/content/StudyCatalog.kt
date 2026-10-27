package com.vocab5000.core.content

import com.vocab5000.core.learning.AccessPolicy
import com.vocab5000.core.learning.RankBands
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.LanguagePack

/** Content + access rules shared by Today, Study, Library, Progress and reminders. */
class StudyCatalog(val content: ContentPack, val languagePack: LanguagePack) {
    val access = AccessPolicy(content.manifest.freeEntryLimit)
    val bands = RankBands(content.manifest.bandSize, content.manifest.entryCount)
    val promptFactory = PromptFactory(content, languagePack)

    fun isUnlocked(entry: Entry, premium: Boolean) = access.isUnlocked(entry.curriculumRank, premium)

    /**
     * Cards eligible for scheduling: known content, unlocked, not suspended, and listening
     * cards only while listening practice is enabled. Locked cards keep their history.
     */
    fun schedulableCards(cards: List<CardState>, premium: Boolean, listeningEnabled: Boolean): List<CardState> = cards.filter { card ->
        val rank = content.rankOf(card.entryId) ?: return@filter false
        access.isUnlocked(rank, premium) &&
            card.state != CardStateType.SUSPENDED &&
            (card.skill == CardSkill.PRODUCTIVE || listeningEnabled)
    }

    fun dueCount(cards: List<CardState>, premium: Boolean, listeningEnabled: Boolean, now: Long): Int =
        schedulableCards(cards, premium, listeningEnabled).count { it.state != CardStateType.NEW && it.dueAt <= now }

    /**
     * Not-yet-introduced, unlocked entries: from the selected band upwards in rank order,
     * then earlier ranks (earlier entries stay available after a placement suggestion).
     */
    fun newCandidates(introduced: Set<String>, premium: Boolean, startBand: Int, limit: Int): List<String> {
        val startRank = bands.range(startBand.coerceIn(1, maxOf(1, bands.count))).first
        val ordered = content.byRank.filter { it.curriculumRank >= startRank } + content.byRank.filter { it.curriculumRank < startRank }
        return ordered.asSequence()
            .filter { it.entryId !in introduced && isUnlocked(it, premium) }
            .map { it.entryId }
            .take(limit)
            .toList()
    }

    fun firstLockedBand(premium: Boolean): Int? = (1..bands.count).firstOrNull { band ->
        !access.isUnlocked(bands.range(band).first, premium)
    }

    fun isBandUnlocked(band: Int, premium: Boolean) = access.isUnlocked(bands.range(band).first, premium)
}

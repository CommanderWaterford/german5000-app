package com.vocab5000.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocab5000.core.content.StudyCatalog
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.learning.ProgressMetrics
import com.vocab5000.core.learning.SrsV1
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.CardStateType
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.PartOfSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class StudyStatusFilter(val label: String) { ALL("all"), NOT_STARTED("not started"), LEARNING("learning"), STRONG("currently strong"), FAVORITES("favourites") }

data class LibraryFilters(val query: String = "", val band: Int? = null, val pos: PartOfSpeech? = null, val status: StudyStatusFilter = StudyStatusFilter.ALL)

data class LibraryRow(val entry: Entry, val locked: Boolean, val status: String, val favorite: Boolean)

data class EntryDetail(
    val entry: Entry,
    val locked: Boolean,
    val favorite: Boolean,
    val status: String,
    val productive: CardState?,
    val difficult: Boolean,
)

class LibraryViewModel(
    private val catalog: StudyCatalog,
    private val repository: LearningRepository,
    premium: StateFlow<Boolean>,
) : ViewModel() {
    private val _filters = MutableStateFlow(LibraryFilters())
    val filters: StateFlow<LibraryFilters> = _filters

    @OptIn(FlowPreview::class)
    val rows: StateFlow<List<LibraryRow>> = combine(_filters.debounce(120), repository.cardStates, repository.userEntries, premium) { f, cards, users, isPremium ->
        val productive = cards.filter { it.skill == CardSkill.PRODUCTIVE }.associateBy { it.entryId }
        catalog.content.search(f.query, limit = 300) { entry ->
            (f.band == null || catalog.bands.bandOf(entry.curriculumRank) == f.band) &&
                (f.pos == null || entry.pos == f.pos) &&
                when (f.status) {
                    StudyStatusFilter.ALL -> true
                    StudyStatusFilter.NOT_STARTED -> users[entry.entryId]?.introducedAt == null
                    StudyStatusFilter.LEARNING -> productive[entry.entryId]?.let(ProgressMetrics::isInLearning) == true
                    StudyStatusFilter.STRONG -> productive[entry.entryId]?.let(ProgressMetrics::isMastered) == true
                    StudyStatusFilter.FAVORITES -> users[entry.entryId]?.favorite == true
                }
        }.map { entry ->
            LibraryRow(
                entry = entry,
                locked = !catalog.isUnlocked(entry, isPremium),
                status = statusLabel(productive[entry.entryId]),
                favorite = users[entry.entryId]?.favorite == true,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val premiumState = premium

    fun setFilters(filters: LibraryFilters) {
        _filters.value = filters
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun detail(entryId: String): Flow<EntryDetail?> = combine(
        repository.observeUserEntry(entryId),
        repository.observeCardsForEntry(entryId),
        premiumState,
    ) { user, cards, isPremium ->
        val entry = catalog.content.entry(entryId) ?: return@combine null
        val productive = cards.firstOrNull { it.skill == CardSkill.PRODUCTIVE }
        EntryDetail(
            entry = entry,
            locked = !catalog.isUnlocked(entry, isPremium),
            favorite = user?.favorite == true,
            status = statusLabel(productive),
            productive = productive,
            difficult = productive?.let(SrsV1::isDifficult) == true,
        )
    }

    fun setFavorite(entryId: String, favorite: Boolean) = viewModelScope.launch { repository.setFavorite(entryId, favorite) }
    fun suspend(cardId: String) = viewModelScope.launch { repository.suspendCard(cardId) }
    fun unsuspend(cardId: String) = viewModelScope.launch { repository.unsuspendCard(cardId) }

    companion object {
        fun statusLabel(card: CardState?): String = when {
            card == null || card.state == CardStateType.NEW -> "not started"
            card.state == CardStateType.SUSPENDED -> "suspended"
            ProgressMetrics.isMastered(card) -> "currently strong"
            card.state == CardStateType.REVIEW -> "reviewing"
            else -> "learning"
        }
    }
}

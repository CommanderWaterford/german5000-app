package com.vocab5000.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocab5000.core.content.StudyCatalog
import com.vocab5000.core.database.AssessmentEntity
import com.vocab5000.core.database.AssessmentRepository
import com.vocab5000.core.database.AssessmentStatus
import com.vocab5000.core.learning.AnswerEvaluator
import com.vocab5000.core.learning.Assessment
import com.vocab5000.core.learning.AssessmentItemKind
import com.vocab5000.core.learning.AssessmentItemPlan
import com.vocab5000.core.learning.AssessmentItemResult
import com.vocab5000.core.learning.AssessmentRecommendation
import com.vocab5000.core.model.AppClock
import com.vocab5000.core.model.Prompt
import com.vocab5000.core.settings.PreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.random.Random

@Serializable
internal data class StoredItem(val band: Int, val entryId: String, val kind: String)

@Serializable
internal data class StoredResult(val index: Int, val correct: Boolean)

sealed interface AssessmentUiState {
    data object Loading : AssessmentUiState
    data object Intro : AssessmentUiState
    data class Question(val index: Int, val total: Int, val prompt: Prompt, val phase: AnswerPhase) : AssessmentUiState
    data class Result(val recommendation: AssessmentRecommendation, val suggestedBandLabel: String, val suggestedBandUnlocked: Boolean, val bandLabels: Map<Int, String>) : AssessmentUiState
    data class Unavailable(val message: String) : AssessmentUiState
}

/**
 * Skippable placement assessment (spec §7.1). Answers are stored separately and never
 * touch SRS, streaks or mastery; feedback is given only after completion.
 */
class AssessmentViewModel(
    private val catalog: StudyCatalog,
    private val repository: AssessmentRepository,
    private val preferences: PreferencesRepository,
    private val evaluator: AnswerEvaluator,
    private val premium: StateFlow<Boolean>,
    private val clock: AppClock,
) : ViewModel() {
    private val json = Json
    private val _state = MutableStateFlow<AssessmentUiState>(AssessmentUiState.Loading)
    val state: StateFlow<AssessmentUiState> = _state.asStateFlow()

    private var entity: AssessmentEntity? = null
    private var items: List<AssessmentItemPlan> = emptyList()
    private val results = mutableListOf<StoredResult>()

    init {
        viewModelScope.launch {
            val existing = repository.inProgress()
            if (existing != null) {
                entity = existing
                items = json.decodeFromString(ListSerializer(StoredItem.serializer()), existing.itemsJson)
                    .map { AssessmentItemPlan(it.band, it.entryId, AssessmentItemKind.valueOf(it.kind)) }
                    .filter { catalog.content.entry(it.entryId) != null }
                results += json.decodeFromString(ListSerializer(StoredResult.serializer()), existing.resultsJson)
                showCurrent()
            } else {
                _state.value = AssessmentUiState.Intro
            }
        }
    }

    fun start() {
        viewModelScope.launch {
            val seed = Random.nextLong()
            val pools = (1..catalog.bands.count).associateWith { band ->
                catalog.bands.range(band).mapNotNull { rank -> catalog.content.byRank.getOrNull(rank - 1) }
                    .filter { it.assessmentEligible }
                    .map { it.entryId }
            }
            items = Assessment.plan(seed, pools) { id -> catalog.promptFactory.hasSafeDistractors(catalog.content.requireEntry(id)) }
            if (items.isEmpty()) {
                _state.value = AssessmentUiState.Unavailable("The assessment needs reviewed items in each band; none are available in this content pack.")
                return@launch
            }
            results.clear()
            entity = AssessmentEntity(
                assessmentId = UUID.randomUUID().toString(),
                seed = seed,
                status = AssessmentStatus.IN_PROGRESS,
                startedAt = clock.nowMillis(),
                itemsJson = json.encodeToString(ListSerializer(StoredItem.serializer()), items.map { StoredItem(it.band, it.entryId, it.kind.name) }),
                resultsJson = "[]",
            ).also { repository.save(it) }
            showCurrent()
        }
    }

    private fun promptFor(index: Int): Prompt {
        val plan = items[index]
        val entry = catalog.content.requireEntry(plan.entryId)
        val seed = (entity?.seed ?: 0L) + index
        return when (plan.kind) {
            AssessmentItemKind.RECOGNITION -> catalog.promptFactory.recognition(entry, Random(seed), allowDontKnow = true)
                ?: catalog.promptFactory.assessmentProduction(entry)
            AssessmentItemKind.PRODUCTION -> catalog.promptFactory.assessmentProduction(entry)
        }
    }

    private suspend fun showCurrent() {
        val index = results.size
        if (index >= items.size) {
            finish()
            return
        }
        _state.value = AssessmentUiState.Question(index, items.size, promptFor(index), AnswerPhase.Typing())
    }

    fun onInput(text: String) {
        val q = _state.value as? AssessmentUiState.Question ?: return
        val typing = q.phase as? AnswerPhase.Typing ?: return
        _state.value = q.copy(phase = typing.copy(input = text))
    }

    fun onSubmitTyped() {
        val q = _state.value as? AssessmentUiState.Question ?: return
        val typing = q.phase as? AnswerPhase.Typing ?: return
        val prompt = q.prompt as? com.vocab5000.core.model.TypedPrompt ?: return
        // No partial credit and no near-miss retry in placement.
        val correct = evaluator.evaluate(typing.input, prompt.answer).verdict.isSuccess
        record(correct)
    }

    fun onChoose(index: Int?) {
        val q = _state.value as? AssessmentUiState.Question ?: return
        val prompt = q.prompt as? com.vocab5000.core.model.ChoicePrompt ?: return
        record(index != null && index == prompt.correctIndex)
    }

    fun onDontKnow() = record(false)

    private fun record(correct: Boolean) {
        viewModelScope.launch {
            results += StoredResult(results.size, correct)
            entity = entity?.copy(resultsJson = json.encodeToString(ListSerializer(StoredResult.serializer()), results))?.also { repository.save(it) }
            showCurrent()
        }
    }

    private suspend fun finish() {
        val scored = results.mapNotNull { r ->
            items.getOrNull(r.index)?.let { AssessmentItemResult(it.band, it.kind, r.correct) }
        }
        val recommendation = Assessment.score(scored, catalog.bands.count)
        entity = entity?.copy(
            status = AssessmentStatus.COMPLETED,
            completedAt = clock.nowMillis(),
            recommendedBand = recommendation.suggestedBand,
            bandScoresJson = recommendation.bandScores.joinToString(";") { "${it.band}:${it.recognitionCorrect}/${it.productionCorrect}" },
        )?.also { repository.save(it) }
        preferences.clearPendingAssessment()
        showResult(recommendation)
    }

    private fun showResult(recommendation: AssessmentRecommendation) {
        _state.value = AssessmentUiState.Result(
            recommendation = recommendation,
            suggestedBandLabel = catalog.bands.label(recommendation.suggestedBand),
            suggestedBandUnlocked = catalog.isBandUnlocked(recommendation.suggestedBand, premium.value),
            bandLabels = (1..catalog.bands.count).associateWith { catalog.bands.label(it) },
        )
    }

    fun skip() {
        viewModelScope.launch {
            entity?.let { repository.save(it.copy(status = AssessmentStatus.ABANDONED)) }
            preferences.clearPendingAssessment()
        }
    }

    fun chooseBand(band: Int) {
        viewModelScope.launch { preferences.setSelectedBand(band) }
    }

    fun isBandUnlocked(band: Int) = catalog.isBandUnlocked(band, premium.value)
}

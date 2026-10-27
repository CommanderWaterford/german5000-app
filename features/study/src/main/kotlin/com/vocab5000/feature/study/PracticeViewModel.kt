package com.vocab5000.feature.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocab5000.core.content.StudyCatalog
import com.vocab5000.core.database.LearningRepository
import com.vocab5000.core.learning.AnswerEvaluator
import com.vocab5000.core.model.ChoicePrompt
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.FlashcardPrompt
import com.vocab5000.core.model.PartOfSpeech
import com.vocab5000.core.model.Prompt
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.TypedPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class PracticeMode(val label: String, val exerciseMode: ExerciseMode) {
    RECOGNITION("Recognise the meaning", ExerciseMode.RECOGNITION),
    TYPED("Type the German word", ExerciseMode.TYPED_RECALL),
    LISTENING("Listening", ExerciseMode.LISTENING),
    CLOZE("Complete the sentence", ExerciseMode.CLOZE),
    GRAMMAR("Grammar: articles, plurals, verb forms, cases", ExerciseMode.GRAMMAR),
    FLASHCARDS("Flashcards", ExerciseMode.FLASHCARD),
}

data class PracticeFilters(val pos: PartOfSpeech? = null, val mistakesOnly: Boolean = false, val band: Int? = null)

sealed interface PracticeUiState {
    data class Setup(val premium: Boolean, val introducedCount: Int, val filters: PracticeFilters = PracticeFilters(), val message: String? = null) : PracticeUiState
    data class Running(val prompts: List<Prompt>, val index: Int, val phase: AnswerPhase, val correct: Int) : PracticeUiState {
        val current: Prompt get() = prompts[index]
    }
    data class Done(val correct: Int, val total: Int) : PracticeUiState
}

/** Practice never changes scheduling; results are stored as PracticeResult only (spec §8.2). */
class PracticeViewModel(
    private val catalog: StudyCatalog,
    private val repository: LearningRepository,
    private val evaluator: AnswerEvaluator,
    private val premium: StateFlow<Boolean>,
) : ViewModel() {
    private val _state = MutableStateFlow<PracticeUiState>(PracticeUiState.Setup(premium.value, 0))
    val state: StateFlow<PracticeUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = PracticeUiState.Setup(premium.value, repository.introducedIds().size)
        }
    }

    fun setFilters(filters: PracticeFilters) = _state.update {
        // Combined advanced filters are a premium feature (spec §12.1).
        if (it is PracticeUiState.Setup && it.premium) it.copy(filters = filters) else it
    }

    fun start(mode: PracticeMode) {
        val setup = _state.value as? PracticeUiState.Setup ?: return
        viewModelScope.launch {
            val introduced = repository.introducedIds()
            val cards = repository.cardsSnapshot()
            val withMistakes = cards.filter { it.lapses > 0 || it.state == com.vocab5000.core.model.CardStateType.RELEARNING }.map { it.entryId }.toSet()
            val f = setup.filters
            val pool = catalog.content.byRank.filter { entry ->
                entry.entryId in introduced &&
                    catalog.isUnlocked(entry, premium.value) &&
                    (f.pos == null || entry.pos == f.pos) &&
                    (!f.mistakesOnly || entry.entryId in withMistakes) &&
                    (f.band == null || catalog.bands.bandOf(entry.curriculumRank) == f.band)
            }
            val random = Random(System.nanoTime())
            val factory = catalog.promptFactory
            val prompts = pool.shuffled(random).flatMap { entry ->
                when (mode) {
                    PracticeMode.RECOGNITION -> listOf(factory.recognition(entry, random) ?: factory.flashcard(entry, null))
                    PracticeMode.TYPED -> listOf(factory.typedRecall(entry, null))
                    PracticeMode.LISTENING -> listOf(factory.recognition(entry, random, listening = true) ?: factory.listening(entry, null))
                    PracticeMode.CLOZE -> listOfNotNull(factory.cloze(entry))
                    PracticeMode.GRAMMAR -> factory.grammar(entry).shuffled(random).take(1)
                    PracticeMode.FLASHCARDS -> listOf(factory.flashcard(entry, null))
                }
            }.take(ROUND_SIZE)
            _state.value = if (prompts.isEmpty()) {
                setup.copy(message = "No words match yet. Learn a few new words first, or change the filters.")
            } else {
                PracticeUiState.Running(prompts, 0, initialPhase(prompts[0]), 0)
            }
        }
    }

    private fun initialPhase(prompt: Prompt): AnswerPhase = when (prompt) {
        is FlashcardPrompt -> AnswerPhase.Flashcard(false)
        else -> AnswerPhase.Typing()
    }

    private fun updateRunning(transform: (PracticeUiState.Running) -> PracticeUiState.Running) = _state.update {
        if (it is PracticeUiState.Running) transform(it) else it
    }

    fun onInput(text: String) = updateRunning { r -> (r.phase as? AnswerPhase.Typing)?.let { r.copy(phase = it.copy(input = text, nearMissRetry = false)) } ?: r }
    fun onHint() = updateRunning { r -> (r.phase as? AnswerPhase.Typing)?.let { r.copy(phase = it.copy(hintShown = true)) } ?: r }

    fun onSubmit() {
        val r = _state.value as? PracticeUiState.Running ?: return
        val prompt = r.current as? TypedPrompt ?: return
        val typing = r.phase as? AnswerPhase.Typing ?: return
        val next = AnswerFlow.submitTyped(typing, prompt, evaluator)
        val correct = (next as? AnswerPhase.TypedFeedback)?.facts?.correct
        if (correct != null) record(prompt, correct)
        _state.value = r.copy(phase = next, correct = r.correct + if (correct == true) 1 else 0)
    }

    fun onReveal() {
        val r = _state.value as? PracticeUiState.Running ?: return
        val typing = r.phase as? AnswerPhase.Typing ?: return
        record(r.current, false)
        _state.value = r.copy(phase = AnswerFlow.reveal(typing))
    }

    fun onChoose(index: Int?) {
        val r = _state.value as? PracticeUiState.Running ?: return
        val prompt = r.current as? ChoicePrompt ?: return
        if (r.phase is AnswerPhase.ChoiceFeedback) return
        val correct = index == prompt.correctIndex
        record(prompt, correct)
        _state.value = r.copy(phase = AnswerPhase.ChoiceFeedback(index, correct), correct = r.correct + if (correct) 1 else 0)
    }

    fun onFlip() = updateRunning { r -> r.copy(phase = AnswerPhase.Flashcard(true)) }

    fun onFlashcardRating(rating: Rating) {
        val r = _state.value as? PracticeUiState.Running ?: return
        val good = rating == Rating.GOOD || rating == Rating.EASY
        record(r.current, good)
        _state.value = r.copy(correct = r.correct + if (good) 1 else 0)
        next()
    }

    fun next() = _state.update { st ->
        val r = st as? PracticeUiState.Running ?: return@update st
        if (r.index + 1 >= r.prompts.size) {
            PracticeUiState.Done(r.correct, r.prompts.size)
        } else {
            r.copy(index = r.index + 1, phase = initialPhase(r.prompts[r.index + 1]))
        }
    }

    fun restart() {
        viewModelScope.launch { _state.value = PracticeUiState.Setup(premium.value, repository.introducedIds().size) }
    }

    private fun record(prompt: Prompt, correct: Boolean) {
        viewModelScope.launch { repository.recordPractice(prompt.entryId, prompt.mode, correct) }
    }

    companion object {
        const val ROUND_SIZE = 10
    }
}

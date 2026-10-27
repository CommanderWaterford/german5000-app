package com.vocab5000.feature.study

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocab5000.core.database.CommitResult
import com.vocab5000.core.database.ScheduledAnswer
import com.vocab5000.core.database.SessionEntity
import com.vocab5000.core.learning.AnswerEvaluator
import com.vocab5000.core.learning.AttemptFacts
import com.vocab5000.core.learning.SessionPlanner
import com.vocab5000.core.model.ChoicePrompt
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.FlashcardPrompt
import com.vocab5000.core.model.IntroductionPrompt
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.SessionMode
import com.vocab5000.core.model.TypedPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SessionUiState {
    data object Loading : SessionUiState
    data class Active(
        val active: ActiveItem,
        val phase: AnswerPhase,
        val answered: Int,
        val maxGraded: Int,
        val committing: Boolean = false,
        val commitError: String? = null,
    ) : SessionUiState
    data class Summary(val summary: SessionSummary) : SessionUiState
    data class Failed(val message: String) : SessionUiState
}

class StudyViewModel(
    private val engine: SessionEngine,
    private val evaluator: AnswerEvaluator,
    private val contentVersion: String,
    private val requestedMode: SessionMode?,
) : ViewModel() {
    private val _state = MutableStateFlow<SessionUiState>(SessionUiState.Loading)
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private var session: SessionEntity? = null
    private var shownAt = SystemClock.elapsedRealtime()

    init {
        viewModelScope.launch {
            try {
                session = engine.resumeOrStart(requestedMode)
                advance()
            } catch (e: Exception) {
                _state.value = SessionUiState.Failed("Could not open the study session: ${e.message}")
            }
        }
    }

    private suspend fun advance() {
        val s = session ?: return
        val next = engine.nextItem(s)
        if (next == null) {
            val summary = engine.summary(s)
            engine.complete(s)
            _state.value = SessionUiState.Summary(summary)
            return
        }
        val fresh = engine.session(s.sessionId) ?: s
        session = fresh
        shownAt = SystemClock.elapsedRealtime()
        val phase = when (next.prompt) {
            is IntroductionPrompt -> AnswerPhase.Introduction
            is FlashcardPrompt -> AnswerPhase.Flashcard(revealed = false)
            is TypedPrompt -> AnswerPhase.Typing()
            is ChoicePrompt -> AnswerPhase.Typing()
        }
        _state.value = SessionUiState.Active(next, phase, fresh.gradedCount, SessionPlanner.DEFAULT_MAX_GRADED)
    }

    private fun updatePhase(transform: (AnswerPhase) -> AnswerPhase) = _state.update { st ->
        if (st is SessionUiState.Active && !st.committing) st.copy(phase = transform(st.phase), commitError = null) else st
    }

    fun onInput(text: String) = updatePhase { if (it is AnswerPhase.Typing) it.copy(input = text, nearMissRetry = false) else it }

    fun onHint() = updatePhase { if (it is AnswerPhase.Typing) it.copy(hintShown = true) else it }

    fun onSubmit() {
        val st = _state.value as? SessionUiState.Active ?: return
        val prompt = st.active.prompt as? TypedPrompt ?: return
        updatePhase { if (it is AnswerPhase.Typing) AnswerFlow.submitTyped(it, prompt, evaluator) else it }
    }

    fun onReveal() = updatePhase { if (it is AnswerPhase.Typing) AnswerFlow.reveal(it) else it }

    fun onOverride() = updatePhase { if (it is AnswerPhase.TypedFeedback && !it.success) AnswerFlow.overrideCorrect(it) else it }

    fun onSelectRating(rating: Rating) = updatePhase { if (it is AnswerPhase.TypedFeedback) AnswerFlow.select(it, rating) else it }

    fun onFlip() = updatePhase { if (it is AnswerPhase.Flashcard) it.copy(revealed = true) else it }

    fun onContinueIntroduction() = commit { active ->
        engine.commitIntroduction(active.item)
    }

    fun onCommitTyped() {
        val st = _state.value as? SessionUiState.Active ?: return
        val phase = st.phase as? AnswerPhase.TypedFeedback ?: return
        val mode = st.active.prompt.mode
        commit { active ->
            engine.commitAnswer(
                ScheduledAnswer(
                    itemId = active.item.itemId,
                    rating = phase.selected,
                    facts = phase.facts,
                    mode = mode,
                    overrideReason = if (phase.facts.validAnswerOverride) "learner_marked_correct: ${phase.input.take(100)}" else null,
                    durationMs = SystemClock.elapsedRealtime() - shownAt,
                    contentVersion = contentVersion,
                ),
            )
        }
    }

    fun onFlashcardRating(rating: Rating) = commit { active ->
        engine.commitAnswer(
            ScheduledAnswer(
                itemId = active.item.itemId,
                rating = rating,
                facts = AttemptFacts(correct = rating != Rating.AGAIN),
                mode = ExerciseMode.FLASHCARD,
                durationMs = SystemClock.elapsedRealtime() - shownAt,
                contentVersion = contentVersion,
            ),
        )
    }

    /** Disables repeat submission while committing; a failed write keeps the prompt open. */
    private fun commit(block: suspend (ActiveItem) -> CommitResult) {
        val st = _state.value as? SessionUiState.Active ?: return
        if (st.committing) return
        _state.value = st.copy(committing = true, commitError = null)
        viewModelScope.launch {
            try {
                block(st.active)
                advance()
            } catch (e: Exception) {
                _state.update {
                    if (it is SessionUiState.Active) {
                        it.copy(committing = false, commitError = "Your answer could not be saved (${e.javaClass.simpleName}). Nothing was recorded. Free up storage if needed and try again.")
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun onStop() {
        // Leaving keeps the session ACTIVE so it can be resumed exactly where it stopped.
    }
}

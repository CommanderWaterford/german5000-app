package com.vocab5000.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.ErrorMessage
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.model.ChoicePrompt
import com.vocab5000.core.model.FlashcardPrompt
import com.vocab5000.core.model.GrammarRenderer
import com.vocab5000.core.model.IntroductionPrompt
import com.vocab5000.core.model.TypedPrompt
import java.util.concurrent.TimeUnit

@Composable
fun StudySessionScreen(
    viewModel: StudyViewModel,
    renderer: GrammarRenderer,
    specialCharacters: List<String>,
    onPlay: PlayAudio,
    onStopAudio: () -> Unit,
    onExit: () -> Unit,
    onMorePractice: () -> Unit,
    nowMillis: () -> Long,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { onStopAudio() } }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            SessionUiState.Loading -> LoadingState(label = "Preparing your session")
            is SessionUiState.Failed -> {
                ErrorMessage(s.message)
                SecondaryAction("Back", onClick = onExit)
            }
            is SessionUiState.Summary -> SummaryView(s.summary, nowMillis(), onExit, onMorePractice)
            is SessionUiState.Active -> {
                val progress = s.answered.toFloat() / s.maxGraded
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.semantics { contentDescription = "${s.answered} of up to ${s.maxGraded} answers in this session" },
                )
                Text("${s.answered} answered · you can stop at any time", style = MaterialTheme.typography.bodySmall)
                when (val prompt = s.active.prompt) {
                    is IntroductionPrompt -> IntroductionView(s.active.entry, renderer, onPlay, viewModel::onContinueIntroduction, s.committing)
                    is TypedPrompt -> TypedPromptView(
                        prompt = prompt,
                        phase = s.phase,
                        specialCharacters = specialCharacters,
                        onPlay = onPlay,
                        onInput = viewModel::onInput,
                        onSubmit = viewModel::onSubmit,
                        onHint = viewModel::onHint,
                        onReveal = viewModel::onReveal,
                        onOverride = viewModel::onOverride,
                        onSelectRating = viewModel::onSelectRating,
                        onContinue = viewModel::onCommitTyped,
                        scheduled = true,
                        busy = s.committing,
                    )
                    is FlashcardPrompt -> FlashcardView(
                        prompt = prompt,
                        revealed = (s.phase as? AnswerPhase.Flashcard)?.revealed == true,
                        onPlay = onPlay,
                        onFlip = viewModel::onFlip,
                        onRate = viewModel::onFlashcardRating,
                        busy = s.committing,
                    )
                    is ChoicePrompt -> Text("Unsupported scheduled prompt")
                }
                s.commitError?.let { ErrorMessage(it) }
                SecondaryAction("Stop for now", onClick = onExit)
            }
        }
    }
}

@Composable
private fun SummaryView(summary: SessionSummary, now: Long, onExit: () -> Unit, onMorePractice: () -> Unit) {
    InfoCard {
        Text("Session complete", style = MaterialTheme.typography.headlineSmall)
        Text("${summary.graded} answers · ${summary.introduced} new words")
        summary.nextShortStepAt?.let {
            val minutes = TimeUnit.MILLISECONDS.toMinutes((it - now).coerceAtLeast(0)) + 1
            Text("Next review in about $minutes min.")
        }
        if (summary.dueRemaining > 0) Text("${summary.dueRemaining} reviews are still due — another short session will help.")
    }
    PrimaryAction("Done", onClick = onExit)
    SecondaryAction("Extra practice", onClick = onMorePractice)
}

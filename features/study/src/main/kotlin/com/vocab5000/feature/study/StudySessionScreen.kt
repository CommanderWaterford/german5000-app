package com.vocab5000.feature.study

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.BackTopBar
import com.vocab5000.core.designsystem.ErrorMessage
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ResultHeader
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
    FocusScaffold(title = "Session", onBack = onExit) { padding ->
        when (val s = state) {
            SessionUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                LoadingState(label = "Preparing your session")
            }
            is SessionUiState.Failed -> Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ErrorMessage(s.message)
                SecondaryAction("Back", onClick = onExit)
            }
            is SessionUiState.Summary -> SummaryView(s.summary, nowMillis(), onExit, onMorePractice, padding)
            is SessionUiState.Active -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val progress = s.answered.toFloat() / s.maxGraded
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${s.answered} of up to ${s.maxGraded} answers in this session" },
                )
                Text(
                    "${s.answered} answered · you can stop at any time",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedContent(
                    targetState = s.active,
                    contentKey = { it.item.itemId },
                    transitionSpec = {
                        (slideInHorizontally(tween(300)) { it / 2 } + fadeIn(tween(300))) togetherWith fadeOut(tween(120))
                    },
                    label = "prompt",
                ) { active ->
                    when (val prompt = active.prompt) {
                        is IntroductionPrompt -> IntroductionView(active.entry, renderer, onPlay, viewModel::onContinueIntroduction, s.committing)
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
                }
                s.commitError?.let { ErrorMessage(it) }
            }
        }
    }
}

/**
 * Full-screen exercise frame with a back arrow. The root already pads the status bar,
 * so only the bottom inset is applied here.
 */
@Composable
internal fun FocusScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = { BackTopBar(title, onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
        content = content,
    )
}

@Composable
private fun SummaryView(summary: SessionSummary, now: Long, onExit: () -> Unit, onMorePractice: () -> Unit, padding: PaddingValues) {
    Column(
        Modifier
            .padding(padding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ResultHeader(
            emoji = when {
                summary.graded >= 10 -> "🏆"
                summary.graded > 0 || summary.introduced > 0 -> "🎉"
                else -> "💪"
            },
            title = "Session complete",
            score = "${summary.graded}",
            scoreLabel = if (summary.graded == 1) "answer" else "answers",
        )
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("✨ ${summary.introduced} new ${if (summary.introduced == 1) "word" else "words"}", style = MaterialTheme.typography.titleMedium)
                summary.nextShortStepAt?.let {
                    val minutes = TimeUnit.MILLISECONDS.toMinutes((it - now).coerceAtLeast(0)) + 1
                    Text("⏱ Next review in about $minutes min")
                }
                if (summary.dueRemaining > 0) Text("📚 ${summary.dueRemaining} reviews still due — another short session will help.")
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryAction("Done", onClick = onExit)
        Spacer(Modifier.height(8.dp))
        SecondaryAction("Extra practice", onClick = onMorePractice)
    }
}

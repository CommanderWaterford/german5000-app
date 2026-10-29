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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.PromptNote
import com.vocab5000.core.designsystem.ResultHeader
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.StatRow
import com.vocab5000.core.designsystem.Wordmark
import com.vocab5000.core.model.ChoicePrompt
import com.vocab5000.core.model.TypedPrompt

@Composable
fun AssessmentScreen(
    viewModel: AssessmentViewModel,
    specialCharacters: List<String>,
    onPlay: PlayAudio,
    onDone: () -> Unit,
    onOpenPremium: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FocusScaffold(title = "Check what I know", onBack = onDone) { padding ->
        when (val s = state) {
            AssessmentUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingState() }
            AssessmentUiState.Intro -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Wordmark()
                Text("Find your starting point", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "About 30 short questions across the word list. There is no timer, and \"I don't know\" is always fine. You'll see a suggested starting point at the end.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                PromptNote("This is a rough estimate from a small sample. It does not mark any words as learned and is not a CEFR level.")
                Spacer(Modifier.height(8.dp))
                PrimaryAction("Start", onClick = viewModel::start)
                SecondaryAction("Skip — start from the beginning", onClick = { viewModel.skip(); onDone() })
            }
            is AssessmentUiState.Unavailable -> Column(
                Modifier.padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(s.message)
                SecondaryAction("Back", onClick = onDone)
            }
            is AssessmentUiState.Question -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LinearProgressIndicator(
                    progress = { s.index.toFloat() / s.total },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Question ${s.index + 1} of ${s.total}" },
                )
                Text(
                    "Question ${s.index + 1} of ${s.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedContent(
                    targetState = s.index,
                    transitionSpec = {
                        (slideInHorizontally(tween(300)) { it / 2 } + fadeIn(tween(300))) togetherWith fadeOut(tween(120))
                    },
                    label = "question",
                ) { _ ->
                    when (val prompt = s.prompt) {
                        is ChoicePrompt -> ChoicePromptView(prompt, AnswerPhase.Typing(), onPlay, viewModel::onChoose, {}, revealFeedback = false)
                        is TypedPrompt -> TypedPromptView(
                            prompt = prompt.copy(hint = null),
                            phase = s.phase,
                            specialCharacters = specialCharacters,
                            onPlay = onPlay,
                            onInput = viewModel::onInput,
                            onSubmit = viewModel::onSubmitTyped,
                            onHint = {},
                            onReveal = viewModel::onDontKnow,
                            onOverride = {},
                            onSelectRating = {},
                            onContinue = {},
                            scheduled = false,
                            busy = false,
                        )
                        else -> Unit
                    }
                }
                SecondaryAction("Stop and continue later", onClick = onDone)
            }
            is AssessmentUiState.Result -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ResultHeader(
                    emoji = "🎯",
                    title = "Suggested starting band",
                    score = "Ranks ${s.suggestedBandLabel}",
                    scoreLabel = "your suggested starting point",
                    scoreStyle = MaterialTheme.typography.displayMedium,
                )
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Based on a small sample, so treat it as a suggestion. Earlier words stay available and unstudied.", style = MaterialTheme.typography.bodyMedium)
                        if (s.recommendation.allBandsFamiliar) Text("You did well everywhere. Consider reviewing earlier words too.")
                    }
                }
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("By band", style = MaterialTheme.typography.titleMedium)
                        s.recommendation.bandScores.forEach { b ->
                            StatRow(
                                "${s.bandLabels[b.band]}" + if (b.provisionallyFamiliar) " · looks familiar" else "",
                                "${b.recognitionCorrect}/4 · ${b.productionCorrect}/2",
                            )
                        }
                        PromptNote("Recognised out of 4 · produced out of 2")
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (s.suggestedBandUnlocked) {
                    PrimaryAction("Start at ${s.suggestedBandLabel}", onClick = { viewModel.chooseBand(s.recommendation.suggestedBand); onDone() })
                } else {
                    InfoCard {
                        Text("Words ranked ${s.suggestedBandLabel} are part of Premium. You can keep learning the free words without purchasing.")
                        SecondaryAction("About Premium", onClick = onOpenPremium)
                    }
                }
                s.bandLabels.keys.filter { it != s.recommendation.suggestedBand && viewModel.isBandUnlocked(it) }.forEach { band ->
                    Spacer(Modifier.height(8.dp))
                    SecondaryAction("Start at ${s.bandLabels[band]}", onClick = { viewModel.chooseBand(band); onDone() })
                }
            }
        }
    }
}

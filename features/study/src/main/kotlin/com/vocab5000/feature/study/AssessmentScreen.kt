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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.LoadingState
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SectionTitle
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = state) {
            AssessmentUiState.Loading -> LoadingState()
            AssessmentUiState.Intro -> {
                ScreenTitle("Check what I know")
                Text("About 30 short questions across the word list. There is no timer, and \"I don't know\" is always fine. You'll see a suggested starting point at the end.")
                Text("This is a rough estimate from a small sample. It does not mark any words as learned and is not a CEFR level.", style = MaterialTheme.typography.bodySmall)
                PrimaryAction("Start", onClick = viewModel::start)
                SecondaryAction("Skip — start from the beginning", onClick = { viewModel.skip(); onDone() })
            }
            is AssessmentUiState.Unavailable -> {
                Text(s.message)
                SecondaryAction("Back", onClick = onDone)
            }
            is AssessmentUiState.Question -> {
                LinearProgressIndicator(
                    progress = { s.index.toFloat() / s.total },
                    modifier = Modifier.semantics { contentDescription = "Question ${s.index + 1} of ${s.total}" },
                )
                Text("Question ${s.index + 1} of ${s.total}", style = MaterialTheme.typography.labelLarge)
                when (val prompt = s.prompt) {
                    is ChoicePrompt -> ChoicePromptView(prompt, AnswerPhase.Typing(), onPlay, viewModel::onChoose, {}, revealFeedback = false)
                    is TypedPrompt -> {
                        TypedPromptView(
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
                    }
                    else -> Unit
                }
                SecondaryAction("Stop and continue later", onClick = onDone)
            }
            is AssessmentUiState.Result -> {
                ScreenTitle("Suggested starting band")
                InfoCard {
                    Text("Words ranked ${s.suggestedBandLabel}", style = MaterialTheme.typography.headlineSmall)
                    Text("Based on a small sample, so treat it as a suggestion. Earlier words stay available and unstudied.", style = MaterialTheme.typography.bodyMedium)
                    if (s.recommendation.allBandsFamiliar) Text("You did well everywhere. Consider reviewing earlier words too.")
                }
                SectionTitle("By band")
                s.recommendation.bandScores.forEach { b ->
                    Text("${s.bandLabels[b.band]}: ${b.recognitionCorrect}/4 recognised, ${b.productionCorrect}/2 produced${if (b.provisionallyFamiliar) " — looks familiar" else ""}")
                }
                if (s.suggestedBandUnlocked) {
                    PrimaryAction("Start at ${s.suggestedBandLabel}", onClick = { viewModel.chooseBand(s.recommendation.suggestedBand); onDone() })
                } else {
                    InfoCard {
                        Text("Words ranked ${s.suggestedBandLabel} are part of Premium. You can keep learning the free words without purchasing.")
                        SecondaryAction("About Premium", onClick = onOpenPremium)
                    }
                }
                s.bandLabels.keys.filter { it != s.recommendation.suggestedBand && viewModel.isBandUnlocked(it) }.forEach { band ->
                    SecondaryAction("Start at ${s.bandLabels[band]}", onClick = { viewModel.chooseBand(band); onDone() })
                }
            }
        }
    }
}

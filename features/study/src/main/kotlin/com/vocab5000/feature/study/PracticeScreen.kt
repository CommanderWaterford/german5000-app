package com.vocab5000.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.MinTouchTarget
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ScreenTitle
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SectionTitle
import com.vocab5000.core.model.ChoicePrompt
import com.vocab5000.core.model.FlashcardPrompt
import com.vocab5000.core.model.PartOfSpeech
import com.vocab5000.core.model.TypedPrompt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PracticeScreen(
    viewModel: PracticeViewModel,
    specialCharacters: List<String>,
    onPlay: PlayAudio,
    onStopAudio: () -> Unit,
    onExit: () -> Unit,
    onOpenPremium: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { onStopAudio() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = state) {
            is PracticeUiState.Setup -> {
                ScreenTitle("Practice")
                Text("Practice uses words you have already met. It never changes your review schedule.", style = MaterialTheme.typography.bodyMedium)
                Text("${s.introducedCount} words available", style = MaterialTheme.typography.bodySmall)
                SectionTitle("Targeted practice")
                if (s.premium) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null, PartOfSpeech.NOUN, PartOfSpeech.VERB, PartOfSpeech.ADJECTIVE, PartOfSpeech.PREPOSITION).forEach { pos ->
                            FilterChip(
                                selected = s.filters.pos == pos,
                                onClick = { viewModel.setFilters(s.filters.copy(pos = pos)) },
                                label = { Text(pos?.name?.lowercase() ?: "all types") },
                                modifier = Modifier.heightIn(min = MinTouchTarget),
                            )
                        }
                        FilterChip(
                            selected = s.filters.mistakesOnly,
                            onClick = { viewModel.setFilters(s.filters.copy(mistakesOnly = !s.filters.mistakesOnly)) },
                            label = { Text("my mistakes") },
                            modifier = Modifier.heightIn(min = MinTouchTarget),
                        )
                    }
                } else {
                    InfoCard {
                        Text("Combine word-type, grammar and mistake filters with Premium. All practice modes below stay free.")
                        SecondaryAction("About Premium", onClick = onOpenPremium)
                    }
                }
                SectionTitle("Choose a mode")
                PracticeMode.entries.forEach { mode -> SecondaryAction(mode.label, onClick = { viewModel.start(mode) }) }
                s.message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
                SecondaryAction("Back", onClick = onExit)
            }
            is PracticeUiState.Running -> {
                Text("Question ${s.index + 1} of ${s.prompts.size}", style = MaterialTheme.typography.labelLarge)
                when (val prompt = s.current) {
                    is TypedPrompt -> TypedPromptView(
                        prompt = prompt,
                        phase = s.phase,
                        specialCharacters = specialCharacters,
                        onPlay = onPlay,
                        onInput = viewModel::onInput,
                        onSubmit = viewModel::onSubmit,
                        onHint = viewModel::onHint,
                        onReveal = viewModel::onReveal,
                        onOverride = {},
                        onSelectRating = {},
                        onContinue = viewModel::next,
                        scheduled = false,
                        busy = false,
                    )
                    is ChoicePrompt -> ChoicePromptView(prompt, s.phase, onPlay, viewModel::onChoose, viewModel::next)
                    is FlashcardPrompt -> FlashcardView(prompt, (s.phase as? AnswerPhase.Flashcard)?.revealed == true, onPlay, viewModel::onFlip, viewModel::onFlashcardRating, busy = false)
                    else -> Unit
                }
                SecondaryAction("Stop practice", onClick = viewModel::restart)
            }
            is PracticeUiState.Done -> {
                InfoCard {
                    Text("Practice complete", style = MaterialTheme.typography.headlineSmall)
                    Text("${s.correct} of ${s.total} correct")
                }
                PrimaryAction("Practise again", onClick = viewModel::restart)
                SecondaryAction("Done", onClick = onExit)
            }
        }
    }
}

package com.vocab5000.feature.study

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.MinTouchTarget
import com.vocab5000.core.designsystem.OptionCard
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.ResultHeader
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SectionTitle
import com.vocab5000.core.designsystem.StatChip
import com.vocab5000.core.designsystem.partialColor
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
    FocusScaffold(title = "Practice", onBack = onExit) { padding ->
        when (val s = state) {
            is PracticeUiState.Setup -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Practice uses words you have already met. It never changes your review schedule.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatChip("📚 ${s.introducedCount} words available", container = MaterialTheme.colorScheme.primaryContainer)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PracticeMode.entries.forEach { mode ->
                        OptionCard(title = mode.label, selected = false, onClick = { viewModel.start(mode) })
                    }
                }
                s.message?.let { Text(it, color = partialColor()) }
            }
            is PracticeUiState.Running -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val answered = s.index + if (s.phase is AnswerPhase.Typing || (s.phase as? AnswerPhase.Flashcard)?.revealed == false) 0 else 1
                LinearProgressIndicator(progress = { answered / s.prompts.size.toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(
                    "Question ${s.index + 1} of ${s.prompts.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedContent(
                    targetState = s.index,
                    transitionSpec = {
                        (slideInHorizontally(tween(300)) { it / 2 } + fadeIn(tween(300))) togetherWith fadeOut(tween(120))
                    },
                    label = "question",
                ) { index ->
                    when (val prompt = s.prompts.getOrNull(index)) {
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
                }
                SecondaryAction("Stop practice", onClick = viewModel::restart)
            }
            is PracticeUiState.Done -> Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ResultHeader(
                    emoji = when {
                        s.total > 0 && s.correct == s.total -> "🏆"
                        s.correct * 2 >= s.total -> "🎉"
                        else -> "💪"
                    },
                    title = when {
                        s.total > 0 && s.correct == s.total -> "Perfect!"
                        s.correct * 2 >= s.total -> "Well done!"
                        else -> "Keep practising!"
                    },
                    score = "${s.correct} / ${s.total}",
                    scoreLabel = "correct",
                )
                Spacer(Modifier.height(24.dp))
                PrimaryAction("Practise again", onClick = viewModel::restart)
                Spacer(Modifier.height(8.dp))
                SecondaryAction("Done", onClick = onExit)
            }
        }
    }
}

package com.vocab5000.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vocab5000.core.designsystem.ArticleText
import com.vocab5000.core.designsystem.AudioButton
import com.vocab5000.core.designsystem.ChoiceOptionCard
import com.vocab5000.core.designsystem.ErrorMessage
import com.vocab5000.core.designsystem.ExampleRow
import com.vocab5000.core.designsystem.FeedbackCard
import com.vocab5000.core.designsystem.GrammarTable
import com.vocab5000.core.designsystem.ListeningPrompt
import com.vocab5000.core.designsystem.MinTouchTarget
import com.vocab5000.core.designsystem.OptionState
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.PromptCard
import com.vocab5000.core.designsystem.PromptLabel
import com.vocab5000.core.designsystem.PromptNote
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SpecialCharacterBar
import com.vocab5000.core.designsystem.correctColor
import com.vocab5000.core.designsystem.partialColor
import com.vocab5000.core.learning.AnswerVerdict
import com.vocab5000.core.model.ChoicePrompt
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.FlashcardPrompt
import com.vocab5000.core.model.GrammarRenderer
import com.vocab5000.core.model.Rating
import com.vocab5000.core.model.TypedPrompt

/** Plays an audio ID with a text fallback (for optional on-device speech). */
typealias PlayAudio = (audioId: String?, fallbackText: String?) -> Unit

@Composable
fun IntroductionView(entry: Entry, renderer: GrammarRenderer, onPlay: PlayAudio, onContinue: () -> Unit, busy: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PromptCard {
            PromptLabel("New word")
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArticleText(renderer.headline(entry), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                AudioButton("Play word", onClick = { onPlay(entry.wordAudioId, renderer.citationForm(entry)) })
            }
            Text(entry.primarySense.glossEn, style = MaterialTheme.typography.titleLarge)
            Text(
                "${renderer.partOfSpeechLabel(entry.pos)} · rank ${entry.curriculumRank}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.corpusDraft) PromptNote("Draft translation and grammar · awaiting review")
            val others = entry.senses.filter { it.senseId != entry.primarySenseId }
            if (others.isNotEmpty()) {
                Text(
                    "Also: " + others.joinToString("; ") { it.glossEn },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        GrammarBlock(entry, renderer)
        entry.primaryExample?.let { ex ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    PromptLabel("Example")
                    ExampleRow(ex.textDe, ex.textEn, onPlay = { onPlay(ex.audioId, ex.textDe) })
                }
            }
        }
        PromptNote("You'll be asked to recall it shortly.")
        PrimaryAction("Got it", onClick = onContinue, enabled = !busy)
    }
}

@Composable
fun GrammarBlock(entry: Entry, renderer: GrammarRenderer) {
    val lines = renderer.grammarLines(entry)
    if (lines.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        GrammarTable(lines.map { it.label to it.value }, Modifier.padding(16.dp))
    }
}

@Composable
fun TypedPromptView(
    prompt: TypedPrompt,
    phase: AnswerPhase,
    specialCharacters: List<String>,
    onPlay: PlayAudio,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
    onHint: () -> Unit,
    onReveal: () -> Unit,
    onOverride: () -> Unit,
    onSelectRating: (Rating) -> Unit,
    onContinue: () -> Unit,
    scheduled: Boolean,
    busy: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PromptCard {
            when (prompt.mode) {
                // The answer is never shown or announced before submission.
                ExerciseMode.LISTENING -> ListeningPrompt(prompt.instruction, onSpeak = { onPlay(prompt.audioId, prompt.answer.canonical) })
                ExerciseMode.CLOZE -> {
                    PromptLabel(prompt.instruction)
                    Text(
                        "${prompt.clozeBefore}_____${prompt.clozeAfter}",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { contentDescription = "${prompt.clozeBefore} blank ${prompt.clozeAfter}" },
                    )
                    Text(prompt.cue, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    PromptLabel(prompt.instruction)
                    ArticleText(prompt.cue, style = MaterialTheme.typography.headlineLarge)
                }
            }
            prompt.contextHint?.let { PromptNote(it) }
        }

        when (phase) {
            is AnswerPhase.Typing -> {
                val focus = remember { FocusRequester() }
                LaunchedEffect(prompt) { runCatching { focus.requestFocus() } }
                OutlinedTextField(
                    value = phase.input,
                    onValueChange = onInput,
                    label = { Text("Your answer") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                SpecialCharacterBar(specialCharacters, onInsert = { onInput(phase.input + it) })
                if (phase.nearMissRetry) {
                    Text(
                        "Almost — check your spelling and try once more.",
                        color = partialColor(),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (phase.hintShown && prompt.hint != null) {
                    Text(
                        "Hint: ${prompt.hint}",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
                PrimaryAction("Check", onClick = onSubmit, enabled = phase.input.isNotBlank())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prompt.hint != null && !phase.hintShown) {
                        TextButton(onClick = onHint, modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget)) { Text("💡 Hint") }
                    }
                    TextButton(onClick = onReveal, modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget)) {
                        Text(if (scheduled) "Show answer" else "I don't know")
                    }
                }
            }
            is AnswerPhase.TypedFeedback -> TypedFeedbackView(prompt, phase, onPlay, onOverride, onSelectRating, onContinue, scheduled, busy)
            else -> Unit
        }
    }
}

@Composable
private fun TypedFeedbackView(
    prompt: TypedPrompt,
    phase: AnswerPhase.TypedFeedback,
    onPlay: PlayAudio,
    onOverride: () -> Unit,
    onSelectRating: (Rating) -> Unit,
    onContinue: () -> Unit,
    scheduled: Boolean,
    busy: Boolean,
) {
    val verdict = phase.result?.verdict
    val (headline, color) = when {
        phase.facts.validAnswerOverride -> "✓ Marked correct by you" to correctColor()
        verdict == AnswerVerdict.CORRECT -> "✓ Correct" to correctColor()
        verdict == AnswerVerdict.CORRECT_CASE_ONLY -> "✓ Correct — note the capitalisation" to partialColor()
        verdict == AnswerVerdict.CORRECT_KEYBOARD_ALTERNATIVE -> "✓ Correct (accepted keyboard spelling)" to partialColor()
        phase.facts.revealedBeforeAnswer || phase.facts.skipped -> "Answer shown" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "✗ Not quite" to MaterialTheme.colorScheme.error
    }
    FeedbackCard(headline, color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        if (phase.input.isNotBlank() && !phase.success) {
            Text("You wrote: ${phase.input}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Answer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ArticleText(prompt.answer.canonical, style = MaterialTheme.typography.titleLarge)
            }
            if (prompt.mode != ExerciseMode.CLOZE) AudioButton("Play word", onClick = { onPlay(prompt.audioId, prompt.answer.canonical) })
        }
        Text(prompt.explanation, style = MaterialTheme.typography.bodyMedium)
        if (scheduled) {
            if (phase.success) {
                Text("How did that feel?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                RatingRow(phase.allowed, phase.selected, onSelectRating)
            } else if (!phase.facts.revealedBeforeAnswer && !phase.facts.skipped) {
                SecondaryAction("My answer was correct", onClick = onOverride, enabled = !busy)
            }
        }
        PrimaryAction(if (busy) "Saving…" else "Continue", onClick = onContinue, enabled = !busy)
    }
}

private val ratingExplanations = mapOf(
    Rating.AGAIN to "Again — show it again soon",
    Rating.HARD to "Hard — correct, but it took effort",
    Rating.GOOD to "Good — correct",
    Rating.EASY to "Easy — effortless",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingRow(allowed: List<Rating>, selected: Rating?, onSelect: (Rating) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        allowed.forEach { rating ->
            FilterChip(
                selected = rating == selected,
                onClick = { onSelect(rating) },
                label = { Text(ratingExplanations.getValue(rating)) },
                modifier = Modifier.heightIn(min = MinTouchTarget),
            )
        }
    }
}

@Composable
fun ChoicePromptView(
    prompt: ChoicePrompt,
    phase: AnswerPhase,
    onPlay: PlayAudio,
    onChoose: (Int?) -> Unit,
    onContinue: () -> Unit,
    revealFeedback: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PromptCard {
            if (prompt.audioId != null && prompt.mode == ExerciseMode.LISTENING) {
                ListeningPrompt(prompt.instruction, onSpeak = { onPlay(prompt.audioId, null) })
            } else {
                PromptLabel(prompt.instruction)
                ArticleText(prompt.cue, style = MaterialTheme.typography.headlineLarge)
            }
        }
        val feedback = phase as? AnswerPhase.ChoiceFeedback
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prompt.options.forEachIndexed { index, option ->
                val state = when {
                    feedback == null || !revealFeedback -> OptionState.NEUTRAL
                    index == prompt.correctIndex -> OptionState.CORRECT
                    index == feedback.chosen -> OptionState.WRONG
                    else -> OptionState.NEUTRAL
                }
                ChoiceOptionCard(option, state, enabled = feedback == null, onClick = { onChoose(index) })
            }
        }
        if (feedback == null && prompt.allowDontKnow) {
            TextButton(onClick = { onChoose(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) { Text("I don't know") }
        }
        if (feedback != null) {
            if (revealFeedback) {
                FeedbackCard(
                    title = if (feedback.correct) "✓ Correct" else "✗ Not quite",
                    color = if (feedback.correct) correctColor() else MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    if (!feedback.correct) Text("Answer: ${prompt.options[prompt.correctIndex]}", style = MaterialTheme.typography.titleMedium)
                    Text(prompt.explanation, style = MaterialTheme.typography.bodyMedium)
                    PrimaryAction("Continue", onClick = onContinue)
                }
            } else {
                PrimaryAction("Continue", onClick = onContinue)
            }
        }
    }
}

@Composable
fun FlashcardView(
    prompt: FlashcardPrompt,
    revealed: Boolean,
    onPlay: PlayAudio,
    onFlip: () -> Unit,
    onRate: (Rating) -> Unit,
    busy: Boolean,
    error: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PromptCard {
            PromptLabel("Recall the German word, then reveal")
            Text(prompt.front, style = MaterialTheme.typography.headlineLarge)
            prompt.frontHint?.let { PromptNote(it) }
        }
        if (!revealed) {
            PrimaryAction("Reveal", onClick = onFlip)
        } else {
            FeedbackCard("Answer", MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ArticleText(prompt.back, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    AudioButton("Play word", onClick = { onPlay(prompt.audioId, prompt.back) })
                }
                prompt.backDetail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("How well did you recall it?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Rating.entries.forEach { rating ->
                    ChoiceOptionCard(ratingExplanations.getValue(rating), OptionState.NEUTRAL, enabled = !busy, onClick = { onRate(rating) })
                }
            }
        }
        error?.let { ErrorMessage(it) }
    }
}

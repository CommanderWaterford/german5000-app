package com.vocab5000.feature.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import com.vocab5000.core.designsystem.AudioButton
import com.vocab5000.core.designsystem.ErrorMessage
import com.vocab5000.core.designsystem.InfoCard
import com.vocab5000.core.designsystem.MinTouchTarget
import com.vocab5000.core.designsystem.PrimaryAction
import com.vocab5000.core.designsystem.SecondaryAction
import com.vocab5000.core.designsystem.SpecialCharacterBar
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("New word", style = MaterialTheme.typography.labelLarge)
        Text(renderer.headline(entry), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text("${renderer.partOfSpeechLabel(entry.pos)} · rank ${entry.curriculumRank}", style = MaterialTheme.typography.bodyMedium)
        AudioButton("Play word", onClick = { onPlay(entry.wordAudioId, renderer.citationForm(entry)) })
        Text(entry.primarySense.glossEn, style = MaterialTheme.typography.titleLarge)
        if (entry.corpusDraft) Text("Draft translation and grammar · awaiting review", style = MaterialTheme.typography.bodySmall)
        val others = entry.senses.filter { it.senseId != entry.primarySenseId }
        if (others.isNotEmpty()) Text("Also: " + others.joinToString("; ") { it.glossEn }, style = MaterialTheme.typography.bodyMedium)
        GrammarBlock(entry, renderer)
        entry.primaryExample?.let { ex ->
            InfoCard {
                Text(ex.textDe, style = MaterialTheme.typography.bodyLarge)
                Text(ex.textEn, style = MaterialTheme.typography.bodyMedium)
                AudioButton("Play example", onClick = { onPlay(ex.audioId, ex.textDe) })
            }
        }
        Text("You'll be asked to recall it shortly.", style = MaterialTheme.typography.bodySmall)
        PrimaryAction("Got it", onClick = onContinue, enabled = !busy)
    }
}

@Composable
fun GrammarBlock(entry: Entry, renderer: GrammarRenderer) {
    val lines = renderer.grammarLines(entry)
    if (lines.isEmpty()) return
    InfoCard {
        lines.forEach { line ->
            Row(Modifier.semantics(mergeDescendants = true) {}) {
                Text("${line.label}: ", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(line.value, style = MaterialTheme.typography.bodyMedium)
            }
        }
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(prompt.instruction, style = MaterialTheme.typography.labelLarge)
        if (prompt.mode == ExerciseMode.LISTENING) {
            // The answer is never shown or announced before submission.
            AudioButton("Play recording", onClick = { onPlay(prompt.audioId, prompt.answer.canonical) })
        } else if (prompt.mode == ExerciseMode.CLOZE) {
            Text("${prompt.clozeBefore}_____${prompt.clozeAfter}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { contentDescription = "${prompt.clozeBefore} blank ${prompt.clozeAfter}" })
            Text(prompt.cue, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(prompt.cue, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        prompt.contextHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        when (phase) {
            is AnswerPhase.Typing -> {
                val focus = remember { FocusRequester() }
                LaunchedEffect(prompt) { runCatching { focus.requestFocus() } }
                OutlinedTextField(
                    value = phase.input,
                    onValueChange = onInput,
                    label = { Text("Your answer") },
                    singleLine = true,
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
                    Text("Almost — check your spelling and try once more.", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (phase.hintShown && prompt.hint != null) Text("Hint: ${prompt.hint}", style = MaterialTheme.typography.bodyMedium)
                PrimaryAction("Check", onClick = onSubmit, enabled = phase.input.isNotBlank())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prompt.hint != null && !phase.hintShown) TextButton(onClick = onHint, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("Hint") }
                    TextButton(onClick = onReveal, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text(if (scheduled) "Show answer" else "I don't know") }
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
    val headline = when {
        phase.facts.validAnswerOverride -> "✓ Marked correct by you"
        verdict == AnswerVerdict.CORRECT -> "✓ Correct"
        verdict == AnswerVerdict.CORRECT_CASE_ONLY -> "✓ Correct — note the capitalisation"
        verdict == AnswerVerdict.CORRECT_KEYBOARD_ALTERNATIVE -> "✓ Correct (accepted keyboard spelling)"
        phase.facts.revealedBeforeAnswer || phase.facts.skipped -> "Answer shown"
        else -> "✗ Not quite"
    }
    InfoCard {
        Text(headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (phase.input.isNotBlank() && !phase.success) Text("You wrote: ${phase.input}", style = MaterialTheme.typography.bodyMedium)
        Text("Answer: ${prompt.answer.canonical}", style = MaterialTheme.typography.titleLarge)
        Text(prompt.explanation, style = MaterialTheme.typography.bodyMedium)
        if (prompt.mode != ExerciseMode.CLOZE) AudioButton("Play word", onClick = { onPlay(prompt.audioId, prompt.answer.canonical) })
    }
    if (scheduled) {
        if (phase.success) {
            Text("How did that feel?", style = MaterialTheme.typography.labelLarge)
            RatingRow(phase.allowed, phase.selected, onSelectRating)
        } else if (!phase.facts.revealedBeforeAnswer && !phase.facts.skipped) {
            SecondaryAction("My answer was correct", onClick = onOverride, enabled = !busy)
        }
    }
    PrimaryAction(if (busy) "Saving…" else "Continue", onClick = onContinue, enabled = !busy)
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(prompt.instruction, style = MaterialTheme.typography.labelLarge)
        if (prompt.audioId != null && prompt.mode == ExerciseMode.LISTENING) {
            AudioButton("Play recording", onClick = { onPlay(prompt.audioId, null) })
        } else {
            Text(prompt.cue, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        val feedback = phase as? AnswerPhase.ChoiceFeedback
        prompt.options.forEachIndexed { index, option ->
            val marker = when {
                feedback == null || !revealFeedback -> ""
                index == prompt.correctIndex -> "✓ "
                index == feedback.chosen -> "✗ "
                else -> ""
            }
            OutlinedButton(
                onClick = { onChoose(index) },
                enabled = feedback == null,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            ) { Text(marker + option) }
        }
        if (feedback == null && prompt.allowDontKnow) SecondaryAction("I don't know", onClick = { onChoose(null) })
        if (feedback != null) {
            if (revealFeedback) {
                Text(
                    if (feedback.correct) "✓ Correct" else "✗ The answer is: ${prompt.options[prompt.correctIndex]}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(prompt.explanation, style = MaterialTheme.typography.bodyMedium)
            }
            PrimaryAction("Continue", onClick = onContinue)
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Recall the German word, then reveal", style = MaterialTheme.typography.labelLarge)
        Text(prompt.front, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        prompt.frontHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (!revealed) {
            PrimaryAction("Reveal", onClick = onFlip)
        } else {
            InfoCard {
                Text(prompt.back, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                prompt.backDetail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                AudioButton("Play word", onClick = { onPlay(prompt.audioId, prompt.back) })
            }
            Text("How well did you recall it?", style = MaterialTheme.typography.labelLarge)
            Rating.entries.forEach { rating ->
                SecondaryAction(ratingExplanations.getValue(rating), onClick = { onRate(rating) }, enabled = !busy)
            }
        }
        error?.let { ErrorMessage(it) }
    }
}

package com.vocab5000.core.content

import com.vocab5000.core.model.AnswerSpec
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.ChoicePrompt
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.ExerciseMode
import com.vocab5000.core.model.FlashcardPrompt
import com.vocab5000.core.model.IntroductionPrompt
import com.vocab5000.core.model.LanguagePack
import com.vocab5000.core.model.Prompt
import com.vocab5000.core.model.ScheduledAnswerStyle
import com.vocab5000.core.model.TypedPrompt
import com.vocab5000.core.learning.TextSpans
import kotlin.random.Random

/** Builds prompt snapshots from content. Language rules come only from [languagePack]. */
class PromptFactory(private val content: ContentPack, private val languagePack: LanguagePack) {
    private val renderer = languagePack.grammarRenderer

    fun introduction(entry: Entry, card: CardState) = IntroductionPrompt(entry.entryId, card.cardId, entry.revision)

    fun scheduled(entry: Entry, card: CardState, style: ScheduledAnswerStyle): Prompt = when (card.skill) {
        CardSkill.LISTENING -> listening(entry, card)
        CardSkill.PRODUCTIVE -> if (style == ScheduledAnswerStyle.FLASHCARD) flashcard(entry, card) else typedRecall(entry, card)
    }

    private fun recallCue(entry: Entry): Pair<String, String> {
        val sense = entry.primarySense
        val context = buildList {
            add(renderer.partOfSpeechLabel(entry.pos))
            sense.register?.let(::add)
            sense.region?.let(::add)
        }.joinToString(" · ")
        return sense.glossEn to context
    }

    fun typedRecall(entry: Entry, card: CardState?, scheduled: Boolean = card != null): TypedPrompt {
        val (cue, context) = recallCue(entry)
        return TypedPrompt(
            entryId = entry.entryId,
            cardId = card?.cardId,
            scheduled = scheduled,
            mode = ExerciseMode.TYPED_RECALL,
            promptRevision = entry.revision,
            instruction = "Type the German word",
            cue = cue,
            contextHint = context,
            answer = languagePack.exerciseProvider.recallAnswerSpec(entry, entry.primarySense),
            hint = "Starts with “${entry.lemma.take(1)}”",
            explanation = renderer.headline(entry),
        )
    }

    fun flashcard(entry: Entry, card: CardState?, scheduled: Boolean = card != null): FlashcardPrompt {
        val (cue, context) = recallCue(entry)
        return FlashcardPrompt(
            entryId = entry.entryId,
            cardId = card?.cardId,
            scheduled = scheduled,
            mode = ExerciseMode.FLASHCARD,
            promptRevision = entry.revision,
            front = cue,
            frontHint = context,
            back = renderer.headline(entry),
            backDetail = entry.primaryExample?.let { "${it.textDe}\n${it.textEn}" },
            audioId = entry.wordAudioId,
        )
    }

    fun listening(entry: Entry, card: CardState?, scheduled: Boolean = card != null) = TypedPrompt(
        entryId = entry.entryId,
        cardId = card?.cardId,
        scheduled = scheduled,
        mode = ExerciseMode.LISTENING,
        promptRevision = entry.revision,
        instruction = "Listen and type the German word you hear",
        cue = "Play the recording",
        audioId = entry.wordAudioId,
        answer = languagePack.exerciseProvider.recallAnswerSpec(entry, entry.primarySense),
        explanation = "${renderer.headline(entry)} — ${entry.primarySense.glossEn}",
    )

    /** German → English meaning. Null when no safe distractors exist (use a flashcard instead). */
    fun recognition(entry: Entry, random: Random, allowDontKnow: Boolean = false, listening: Boolean = false): ChoicePrompt? {
        val distractors = distractorsFor(entry, random) ?: return null
        val options = (distractors + entry.primarySense.glossEn).shuffled(random)
        return ChoicePrompt(
            entryId = entry.entryId,
            cardId = null,
            scheduled = false,
            mode = if (listening) ExerciseMode.LISTENING else ExerciseMode.RECOGNITION,
            promptRevision = entry.revision,
            instruction = if (listening) "Listen and choose the meaning" else "Choose the meaning",
            cue = if (listening) "Play the recording" else renderer.citationForm(entry),
            audioId = if (listening) entry.wordAudioId else null,
            options = options,
            correctIndex = options.indexOf(entry.primarySense.glossEn),
            explanation = "${renderer.headline(entry)} — ${entry.primarySense.glossEn}",
            allowDontKnow = allowDontKnow,
        )
    }

    fun hasSafeDistractors(entry: Entry): Boolean = candidateDistractors(entry).size >= DISTRACTOR_COUNT

    private fun candidateDistractors(entry: Entry): List<String> {
        val own = entry.senses.flatMap { listOf(it.glossEn) + it.acceptedMeaningsEn }.map(ContentPack::searchKey).toSet()
        return content.entriesWithPos(entry.pos)
            .asSequence()
            .filter { it.entryId != entry.entryId }
            .map { it.primarySense.glossEn }
            .filter { ContentPack.searchKey(it) !in own }
            .distinctBy(ContentPack::searchKey)
            .toList()
    }

    private fun distractorsFor(entry: Entry, random: Random): List<String>? {
        val pool = candidateDistractors(entry)
        if (pool.size < DISTRACTOR_COUNT) return null
        return pool.shuffled(random).take(DISTRACTOR_COUNT)
    }

    /** Sentence with the target removed; only the exact form in the sentence is accepted. */
    fun cloze(entry: Entry): TypedPrompt? {
        val example = entry.primaryExample ?: return null
        val span = example.targetSpans.singleOrNull() ?: return null
        val range = TextSpans.toUtf16Range(example.textDe, span) ?: return null
        val target = example.textDe.substring(range)
        return TypedPrompt(
            entryId = entry.entryId,
            cardId = null,
            scheduled = false,
            mode = ExerciseMode.CLOZE,
            promptRevision = entry.revision,
            instruction = "Complete the sentence",
            cue = example.textEn,
            contextHint = "${entry.primarySense.glossEn} · ${renderer.partOfSpeechLabel(entry.pos)}",
            clozeBefore = example.textDe.substring(0, range.first),
            clozeAfter = example.textDe.substring(range.last + 1),
            audioId = null,
            answer = AnswerSpec(canonical = target, caseSensitive = true),
            explanation = example.textDe,
        )
    }

    fun grammar(entry: Entry): List<Prompt> = languagePack.exerciseProvider.grammarPrompts(entry, entry.revision)

    /** Assessment production item: English → German, typed, no partial credit. */
    fun assessmentProduction(entry: Entry): TypedPrompt = typedRecall(entry, card = null, scheduled = false).copy(hint = null)

    companion object {
        const val DISTRACTOR_COUNT = 3
    }
}

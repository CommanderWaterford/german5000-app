package com.vocab5000.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Rules for evaluating a typed answer. Built by the language pack so that German
 * specifics (articles, umlaut alternatives, reflexive forms) stay out of the engine.
 */
@Serializable
data class AnswerSpec(
    val canonical: String,
    /** Fully accepted answers, compared after normalization (canonical is implicitly accepted). */
    val accepted: List<String> = emptyList(),
    /** Explicitly approved keyboard alternatives (ae/oe/ue/ss). */
    val keyboardAlternatives: List<String> = emptyList(),
    /** When false, case-only differences are accepted with corrective feedback. */
    val caseSensitive: Boolean = false,
    /** Leading words that may be included, e.g. the correct article or `sich`. */
    val optionalPrefixes: List<String> = emptyList(),
    /** Leading words that make the answer wrong when included, e.g. a wrong article. */
    val forbiddenPrefixes: List<String> = emptyList(),
)

@Serializable
sealed interface Prompt {
    val entryId: String
    /** Scheduled card being asked, or null for practice/assessment prompts. */
    val cardId: String?
    val scheduled: Boolean
    val mode: ExerciseMode
    val promptRevision: Int
}

/** First presentation of a new entry: teaches it; never graded. */
@Serializable
@SerialName("introduction")
data class IntroductionPrompt(
    override val entryId: String,
    override val cardId: String,
    override val promptRevision: Int,
) : Prompt {
    override val scheduled: Boolean get() = true
    override val mode: ExerciseMode get() = ExerciseMode.INTRODUCTION
}

@Serializable
@SerialName("typed")
data class TypedPrompt(
    override val entryId: String,
    override val cardId: String?,
    override val scheduled: Boolean,
    override val mode: ExerciseMode,
    override val promptRevision: Int,
    val instruction: String,
    val cue: String,
    val contextHint: String? = null,
    val audioId: String? = null,
    /** For cloze prompts: German sentence parts around the removed target. */
    val clozeBefore: String? = null,
    val clozeAfter: String? = null,
    val answer: AnswerSpec,
    val hint: String? = null,
    val explanation: String,
) : Prompt

@Serializable
@SerialName("choice")
data class ChoicePrompt(
    override val entryId: String,
    override val cardId: String?,
    override val scheduled: Boolean,
    override val mode: ExerciseMode,
    override val promptRevision: Int,
    val instruction: String,
    val cue: String,
    val audioId: String? = null,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String,
    /** Assessment and practice prompts may offer "I don't know". */
    val allowDontKnow: Boolean = false,
) : Prompt

@Serializable
@SerialName("flashcard")
data class FlashcardPrompt(
    override val entryId: String,
    override val cardId: String?,
    override val scheduled: Boolean,
    override val mode: ExerciseMode,
    override val promptRevision: Int,
    val front: String,
    val frontHint: String? = null,
    val back: String,
    val backDetail: String? = null,
    val audioId: String? = null,
) : Prompt

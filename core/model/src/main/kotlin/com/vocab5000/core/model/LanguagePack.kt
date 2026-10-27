package com.vocab5000.core.model

/** A labelled grammar fact, rendered as text (never colour alone). */
data class GrammarLine(val label: String, val value: String)

interface GrammarRenderer {
    /** Headword as shown to learners, e.g. `der Tisch · die Tische`. */
    fun headline(entry: Entry): String

    /** Headword as spoken/typed without secondary forms, e.g. `der Tisch`. */
    fun citationForm(entry: Entry): String

    fun grammarLines(entry: Entry): List<GrammarLine>

    fun partOfSpeechLabel(pos: PartOfSpeech): String
}

interface AnswerNormalizer {
    /** Language-specific canonicalisation applied after the generic NFC/whitespace step. */
    fun normalize(input: String): String

    /** Lowercasing rule for case-insensitive comparisons. */
    fun foldCase(input: String): String
}

interface ExerciseProvider {
    /** Answer rules for producing [entry] from an English cue for [sense]. */
    fun recallAnswerSpec(entry: Entry, sense: Sense): AnswerSpec

    /** Language-specific grammar practice prompts (practice only in v1). */
    fun grammarPrompts(entry: Entry, revision: Int): List<Prompt>
}

/** Validation hook for language-specific required grammar fields (spec §5.3). */
fun interface GrammarValidator {
    fun validate(entry: Entry): List<String>
}

interface MediaLookup {
    fun audioPath(audioId: String): String?
}

/**
 * Everything language-specific. The scheduler, queue and screens depend only on this
 * interface so that a second language pack reuses the shared engine unchanged.
 */
interface LanguagePack {
    val languageId: String
    val displayName: String
    val grammarRenderer: GrammarRenderer
    val answerNormalizer: AnswerNormalizer
    val exerciseProvider: ExerciseProvider
    val grammarValidator: GrammarValidator
    /** Characters offered as input helpers next to the system keyboard. */
    val specialCharacters: List<String>
    /** BCP-47 tag used for optional text-to-speech fallback. */
    val speechLocaleTag: String
}

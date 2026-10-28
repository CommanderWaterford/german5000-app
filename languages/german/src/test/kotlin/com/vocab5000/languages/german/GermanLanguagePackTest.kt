package com.vocab5000.languages.german

import com.vocab5000.core.learning.AnswerEvaluator
import com.vocab5000.core.learning.AnswerVerdict
import com.vocab5000.core.model.EditorialStatus
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.PartOfSpeech
import com.vocab5000.core.model.Sense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GermanLanguagePackTest {
    private val evaluator = AnswerEvaluator(GermanLanguagePack.answerNormalizer)

    private fun entry(lemma: String, pos: PartOfSpeech, grammar: Map<String, String>, sense: Sense = Sense("s1", "gloss")) = Entry(
        entryId = "de.test.$lemma", curriculumRank = 1, lemma = lemma, pos = pos, languageId = "de-DE",
        primarySenseId = sense.senseId, grammar = grammar, senses = listOf(sense), examples = emptyList(),
        wordAudioId = null, revision = 1, editorialStatus = EditorialStatus.DRAFT,
    )

    private val tisch = entry(
        "Tisch", PartOfSpeech.NOUN,
        mapOf("gender" to "masculine", "article" to "der", "singular" to "Tisch", "plural" to "Tische", "pluralStatus" to "has_plural", "weakNoun" to "false"),
        Sense("s1", "table"),
    )

    @Test fun nounHeadlineShowsArticleAndPlural() {
        assertEquals("der Tisch · die Tische", GermanGrammarRenderer.headline(tisch))
        assertEquals("der Tisch", GermanGrammarRenderer.citationForm(tisch))
    }

    @Test fun pluralOnlyNounDoesNotRequireAnExplicitArticle() {
        val leute = entry("Leute", PartOfSpeech.NOUN, mapOf("pluralStatus" to "plural_only"))
        assertEquals("die Leute (plural only)", GermanGrammarRenderer.headline(leute))
        assertEquals("die Leute", GermanGrammarRenderer.citationForm(leute))
    }

    @Test fun articleIsOptionalButMustBeCorrect() {
        val spec = GermanExerciseProvider.recallAnswerSpec(tisch, tisch.primarySense)
        assertEquals(AnswerVerdict.CORRECT, evaluator.evaluate("Tisch", spec).verdict)
        assertEquals(AnswerVerdict.CORRECT, evaluator.evaluate("der Tisch", spec).verdict)
        assertEquals(AnswerVerdict.INCORRECT, evaluator.evaluate("die Tisch", spec).verdict)
        assertEquals(AnswerVerdict.CORRECT_CASE_ONLY, evaluator.evaluate("tisch", spec).verdict)
    }

    /** AT-12: `schon` is not accepted for `schön` unless explicitly approved. */
    @Test fun schonIsNotSchoen() {
        val schoen = entry("schön", PartOfSpeech.ADJECTIVE, mapOf("gradable" to "true", "comparative" to "schöner", "superlative" to "am schönsten"), Sense("s1", "beautiful", keyboardAlternatives = listOf("schoen")))
        val spec = GermanExerciseProvider.recallAnswerSpec(schoen, schoen.primarySense)
        val result = evaluator.evaluate("schon", spec)
        assertFalse(result.verdict.isSuccess)
        assertEquals("schön", result.canonical)
        assertEquals(AnswerVerdict.CORRECT_KEYBOARD_ALTERNATIVE, evaluator.evaluate("schoen", spec).verdict)
    }

    @Test fun eszettIsNeverFoldedToSs() {
        val gross = entry("groß", PartOfSpeech.ADJECTIVE, mapOf("gradable" to "true", "comparative" to "größer", "superlative" to "am größten"))
        val spec = GermanExerciseProvider.recallAnswerSpec(gross, gross.primarySense)
        assertFalse(evaluator.evaluate("gross", spec).verdict.isSuccess)
        assertEquals(AnswerVerdict.CORRECT, evaluator.evaluate("groß", spec).verdict)
    }

    @Test fun reflexiveVerbsAcceptSich() {
        val freuen = entry("freuen", PartOfSpeech.VERB, mapOf("present3sg" to "freut", "preterite" to "freute", "participle2" to "gefreut", "auxiliary" to "haben", "separablePrefix" to "not_applicable", "reflexive" to "required"))
        val spec = GermanExerciseProvider.recallAnswerSpec(freuen, freuen.primarySense)
        assertEquals(AnswerVerdict.CORRECT, evaluator.evaluate("sich freuen", spec).verdict)
        assertEquals(AnswerVerdict.CORRECT, evaluator.evaluate("freuen", spec).verdict)
        assertEquals("sich freuen", GermanGrammarRenderer.citationForm(freuen))
    }

    @Test fun verbGrammarLinesIncludePerfectWithAuxiliary() {
        val gehen = entry("gehen", PartOfSpeech.VERB, mapOf("present3sg" to "geht", "preterite" to "ging", "participle2" to "gegangen", "auxiliary" to "sein", "separablePrefix" to "not_applicable", "reflexive" to "no"))
        val lines = GermanGrammarRenderer.grammarLines(gehen).associate { it.label to it.value }
        assertEquals("ist gegangen", lines["Perfect"])
        assertEquals("no", lines["Separable"])
    }

    @Test fun incompleteDraftGrammarDoesNotInventFactsOrDisplayNull() {
        val noun = tisch.copy(grammar = emptyMap(), corpusDraft = true, assessmentEligible = false)
        assertEquals("Tisch", GermanGrammarRenderer.headline(noun))
        assertEquals("Tisch", GermanGrammarRenderer.citationForm(noun))
        assertTrue(GermanGrammarRenderer.grammarLines(noun).isEmpty())
        val verb = entry("gehen", PartOfSpeech.VERB, mapOf("participle2" to "gegangen"))
        val lines = GermanGrammarRenderer.grammarLines(verb).associate { it.label to it.value }
        assertEquals("gegangen", lines["Perfect"])
        assertFalse("Separable" in lines)
        assertTrue(GermanExerciseProvider.grammarPrompts(verb, 1).all {
            it is com.vocab5000.core.model.TypedPrompt && !it.explanation.contains("null")
        })
    }

    @Test fun validatorFlagsMissingAndInconsistentGrammar() {
        assertTrue(GermanGrammarValidator.validate(tisch).isEmpty())
        val wrongArticle = tisch.copy(grammar = tisch.grammar + ("article" to "die"))
        assertTrue(GermanGrammarValidator.validate(wrongArticle).any { "does not match gender" in it })
        val noPlural = tisch.copy(grammar = tisch.grammar - "plural")
        assertTrue(GermanGrammarValidator.validate(noPlural).isNotEmpty())
        val prep = entry("mit", PartOfSpeech.PREPOSITION, mapOf("governedCases" to "instrumental"))
        assertTrue(GermanGrammarValidator.validate(prep).isNotEmpty())
    }

    @Test fun grammarPromptsForNounsAndPrepositions() {
        val prompts = GermanExerciseProvider.grammarPrompts(tisch, 1)
        assertEquals(2, prompts.size)
        val mit = entry("mit", PartOfSpeech.PREPOSITION, mapOf("governedCases" to "dative"), Sense("s1", "with"))
        assertEquals(1, GermanExerciseProvider.grammarPrompts(mit, 1).size)
    }
}

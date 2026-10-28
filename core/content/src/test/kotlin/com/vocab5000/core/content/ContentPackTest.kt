package com.vocab5000.core.content

import com.vocab5000.core.learning.AnswerEvaluator
import com.vocab5000.core.learning.PlannedItem
import com.vocab5000.core.learning.PlannerInput
import com.vocab5000.core.learning.SessionPlanner
import com.vocab5000.core.learning.SrsV1
import com.vocab5000.core.model.AnswerNormalizer
import com.vocab5000.core.model.AnswerSpec
import com.vocab5000.core.model.CardSkill
import com.vocab5000.core.model.CardState
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.ExerciseProvider
import com.vocab5000.core.model.GrammarLine
import com.vocab5000.core.model.GrammarRenderer
import com.vocab5000.core.model.GrammarValidator
import com.vocab5000.core.model.LanguagePack
import com.vocab5000.core.model.PartOfSpeech
import com.vocab5000.core.model.Prompt
import com.vocab5000.core.model.ScheduledAnswerStyle
import com.vocab5000.core.model.Sense
import com.vocab5000.core.model.SessionMode
import com.vocab5000.core.model.TypedPrompt
import com.vocab5000.languages.german.GermanLanguagePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

private fun entryJson(id: String, rank: Int, lemma: String, pos: String, gloss: String, grammar: String = "{}", lang: String = "de-DE") = """
    {"entryId":"$id","curriculumRank":$rank,"lemma":"$lemma","pos":"$pos","languageId":"$lang","primarySenseId":"$id.s1",
     "grammar":$grammar,"senses":[{"senseId":"$id.s1","glossEn":"$gloss"}],
     "examples":[{"exampleId":"$id.ex1","senseId":"$id.s1","textDe":"Das ist $lemma.","textEn":"That is $gloss.","targetSpanCodepoints":[[8,${8 + lemma.length}]],"audioId":null}],
     "wordAudioId":null,"revision":1,"editorialStatus":"draft"}
""".trimIndent()

private fun packJson(entries: List<String>, lang: String = "de-DE", free: Int = 2, mode: String = "pilot") = """
    {"manifest":{"packId":"test","schemaVersion":1,"contentVersion":"t1","languageId":"$lang","entryCount":${entries.size},
      "schedulerCompatibility":["g5000_srs_v1"],"rightsManifestId":"r","freeEntryLimit":$free,"bandSize":2,"buildMode":"$mode","rankSource":"placeholder"},
     "entries":[${entries.joinToString(",")}],"audio":[]}
""".trimIndent()

class ContentPackTest {
    private val adverbs = listOf(
        entryJson("de.adv.hier.01", 1, "hier", "ADVERB", "here"),
        entryJson("de.adv.heute.01", 2, "heute", "ADVERB", "today"),
        entryJson("de.adv.sehr.01", 3, "sehr", "ADVERB", "very"),
        entryJson("de.adv.auch.01", 4, "auch", "ADVERB", "also"),
    )

    @Test fun loadsAndSearches() {
        val pack = ContentPack.load(packJson(adverbs), GermanLanguagePack)
        assertEquals(4, pack.entries.size)
        assertEquals("de.adv.heute.01", pack.search("heu").first().entryId)
        assertEquals("de.adv.sehr.01", pack.search("very").first().entryId)
    }

    @Test fun rejectsNonContiguousRanksAndWrongLanguage() {
        val gap = adverbs.take(2) + entryJson("de.adv.sehr.01", 5, "sehr", "ADVERB", "very")
        val e = runCatching { ContentPack.load(packJson(gap), GermanLanguagePack) }.exceptionOrNull() as ContentValidationException
        assertTrue(e.errors.any { "contiguous" in it })
        assertTrue(runCatching { ContentPack.load(packJson(adverbs, lang = "es-ES"), GermanLanguagePack) }.isFailure)
    }

    @Test fun releaseModeRequiresFullApprovedCatalog() {
        val e = runCatching { ContentPack.load(packJson(adverbs, mode = "release"), GermanLanguagePack) }.exceptionOrNull() as ContentValidationException
        assertTrue(e.errors.any { "exactly 5000" in it })
        assertTrue(e.errors.any { "not approved" in it })
    }

    @Test fun rejectsMultipleExamplesAndUnreviewedAssessmentContent() {
        val dto = PackFormat.decode(packJson(adverbs))
        val first = dto.entries.first()
        val multiple = dto.copy(entries = listOf(first.copy(examples = first.examples + first.examples.first())) + dto.entries.drop(1))
        val error = runCatching { ContentPack.load(PackFormat.encode(multiple), GermanLanguagePack) }.exceptionOrNull() as ContentValidationException
        assertTrue(error.errors.any { "exactly one example" in it })
        val unreviewed = dto.copy(entries = listOf(first.copy(corpusDraft = true)) + dto.entries.drop(1))
        val assessmentError = runCatching { ContentPack.load(PackFormat.encode(unreviewed), GermanLanguagePack) }.exceptionOrNull() as ContentValidationException
        assertTrue(assessmentError.errors.any { "assessment eligible" in it })
    }

    @Test fun draftsWithMissingTargetsStayPilotOnly() {
        val dto = PackFormat.decode(packJson(adverbs))
        val first = dto.entries.first().copy(
            corpusDraft = true, assessmentEligible = false,
            examples = dto.entries.first().examples.map { it.copy(targetSpanCodepoints = emptyList()) },
        )
        val draft = dto.copy(entries = listOf(first) + dto.entries.drop(1))
        assertEquals(4, ContentPack.load(PackFormat.encode(draft), GermanLanguagePack).entries.size)
        val error = runCatching { ContentPack.load(PackFormat.encode(draft), GermanLanguagePack, requireRelease = true) }.exceptionOrNull() as ContentValidationException
        assertTrue(error.errors.any { "corpus draft needs linguistic review" in it })
        assertTrue(error.errors.any { "has no target span" in it })
        val incomplete = draft.copy(entries = listOf(first.copy(pos = "NOUN")) + dto.entries.drop(1))
        val grammarError = runCatching { ContentPack.load(PackFormat.encode(incomplete), GermanLanguagePack) }.exceptionOrNull() as ContentValidationException
        assertTrue(grammarError.errors.any { "missing grammar field" in it })
    }

    @Test fun catalogLocksAndCandidates() {
        val catalog = StudyCatalog(ContentPack.load(packJson(adverbs), GermanLanguagePack), GermanLanguagePack)
        assertEquals(listOf("de.adv.hier.01", "de.adv.heute.01"), catalog.newCandidates(emptySet(), premium = false, startBand = 1, limit = 10))
        assertEquals(4, catalog.newCandidates(emptySet(), premium = true, startBand = 1, limit = 10).size)
        // Starting at band 2 continues from rank 3, then wraps to earlier unstudied ranks.
        assertEquals(listOf("de.adv.sehr.01", "de.adv.auch.01", "de.adv.hier.01", "de.adv.heute.01"), catalog.newCandidates(emptySet(), true, 2, 10))
        val lockedCard = CardState.newCard("de.adv.sehr.01", CardSkill.PRODUCTIVE, SrsV1.VERSION)
        assertTrue(catalog.schedulableCards(listOf(lockedCard), premium = false, listeningEnabled = false).isEmpty())
    }

    @Test fun recognitionNeedsThreeSafeDistractors() {
        val pack = ContentPack.load(packJson(adverbs), GermanLanguagePack)
        val factory = PromptFactory(pack, GermanLanguagePack)
        val prompt = factory.recognition(pack.byRank.first(), kotlin.random.Random(1))
        assertNotNull(prompt)
        assertEquals(4, prompt!!.options.toSet().size)
        assertEquals("here", prompt.options[prompt.correctIndex])
        val small = ContentPack.load(packJson(adverbs.take(3), free = 2), GermanLanguagePack)
        assertFalse(PromptFactory(small, GermanLanguagePack).hasSafeDistractors(small.byRank.first()))
    }

    @Test fun clozeUsesCodepointSpan() {
        val pack = ContentPack.load(packJson(adverbs), GermanLanguagePack)
        val cloze = PromptFactory(pack, GermanLanguagePack).cloze(pack.byRank.first())!!
        assertEquals("Das ist ", cloze.clozeBefore)
        assertEquals(".", cloze.clozeAfter)
        assertEquals("hier", cloze.answer.canonical)
    }
}

/**
 * Architecture check (spec §2.1, §16.1): a tiny synthetic language pack drives the shared
 * catalog, prompt factory, planner and evaluator with no German rules involved.
 */
class SyntheticLanguagePackTest {
    private object Toki : LanguagePack {
        override val languageId = "tok"
        override val displayName = "Synthetic"
        override val specialCharacters = emptyList<String>()
        override val speechLocaleTag = "und"
        override val grammarRenderer = object : GrammarRenderer {
            override fun headline(entry: Entry) = entry.lemma
            override fun citationForm(entry: Entry) = entry.lemma
            override fun grammarLines(entry: Entry) = emptyList<GrammarLine>()
            override fun partOfSpeechLabel(pos: PartOfSpeech) = pos.name
        }
        override val answerNormalizer = object : AnswerNormalizer {
            override fun normalize(input: String) = input
            override fun foldCase(input: String) = input.lowercase(Locale.ROOT)
        }
        override val exerciseProvider = object : ExerciseProvider {
            override fun recallAnswerSpec(entry: Entry, sense: Sense) = AnswerSpec(entry.lemma)
            override fun grammarPrompts(entry: Entry, revision: Int) = emptyList<Prompt>()
        }
        override val grammarValidator = GrammarValidator { emptyList() }
    }

    @Test fun sharedEngineWorksWithAnotherLanguage() {
        val entries = listOf(
            entryJson("tok.noun.jan.01", 1, "jan", "NOUN", "person", lang = "tok"),
            entryJson("tok.noun.telo.01", 2, "telo", "NOUN", "water", lang = "tok"),
        )
        val catalog = StudyCatalog(ContentPack.load(packJson(entries, lang = "tok"), Toki), Toki)
        val now = 1_800_000_000_000L
        val next = SessionPlanner.next(PlannerInput(now, SessionMode.LEARN_NEW, emptyList(), catalog.newCandidates(emptySet(), false, 1, 1), 1, 0))
        assertEquals(PlannedItem.Introduce("tok.noun.jan.01"), next)
        val card = SrsV1.introduce(CardState.newCard("tok.noun.jan.01", CardSkill.PRODUCTIVE, SrsV1.VERSION), now)
        val prompt = catalog.promptFactory.scheduled(catalog.content.requireEntry("tok.noun.jan.01"), card, ScheduledAnswerStyle.TYPED) as TypedPrompt
        assertEquals("person", prompt.cue)
        assertTrue(AnswerEvaluator(Toki.answerNormalizer).evaluate("JAN", prompt.answer).verdict.isSuccess)
    }
}

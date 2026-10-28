package com.vocab5000.tools.content

import com.vocab5000.core.content.ContentPack
import com.vocab5000.core.content.PackFormat
import com.vocab5000.core.content.PackValidator
import com.vocab5000.core.content.PromptFactory
import com.vocab5000.core.model.PartOfSpeech
import kotlinx.serialization.json.Json
import com.vocab5000.languages.german.GermanLanguagePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the real editorial sources through the pipeline (spec §16.1 content checks). */
class ContentPipelineTest {
    private val source = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "content/german5000/source") }
        .first { it.isDirectory }

    @Test fun pilotSourcesBuildWithoutErrors() {
        val result = ContentPipeline(GermanLanguagePack).build(source, PackValidator.Mode.PILOT)
        assertEquals(emptyList<String>(), result.errors)
        val pack = ContentPack.load(PackFormat.encode(result.pack), GermanLanguagePack)
        assertEquals((1..pack.entries.size).toList(), pack.byRank.map { it.curriculumRank })
        assertEquals(4010, pack.entries.size)
        assertEquals(1000, pack.manifest.freeEntryLimit)
        assertEquals(1000, pack.manifest.bandSize)
        assertTrue(pack.entries.all { it.examples.size == 1 })
        assertTrue(pack.entries.all { it.primarySense.glossEn.isNotBlank() && it.primaryExample!!.textEn.isNotBlank() })
        assertTrue(pack.entries.filter { it.corpusDraft }.all { !it.assessmentEligible })
        assertEquals("segeln", pack.byRank.last().lemma)
        assertTrue(pack.search("Hbemd").any { it.lemma == "Hemd" })
        assertTrue(pack.search("Welle").any { it.lemma == "Weile" })
        val originalIds = Json.decodeFromString(SourceFile.serializer(), File(source, "pilot-entries.json").readText()).entries.map { it.entryId }
        assertTrue(originalIds.all { pack.entry(it) != null })
        val wollen = pack.entries.first { it.lemma == "wollen" }
        assertEquals(PartOfSpeech.VERB, wollen.pos)
        val ueberlegen = pack.entries.first { it.lemma == "überlegen" }
        assertEquals(PartOfSpeech.VERB, ueberlegen.pos)
        assertTrue(ueberlegen.primarySense.glossEn.contains("think"))
        val schwein = pack.entries.first { it.lemma == "Schwein" }
        val factory = PromptFactory(pack, GermanLanguagePack)
        assertEquals("Schweine", factory.cloze(schwein)!!.answer.canonical)
        assertEquals(schwein.primarySense.glossEn, factory.typedRecall(schwein, null).cue)
        // Separable verbs: both parts of the split form are target spans.
        assertEquals(2, pack.entry("de.verb.anfangen.01")!!.primaryExample!!.targetSpans.size)
        // Derived keyboard alternative for schön, none for schon.
        assertEquals(listOf("schoen"), pack.entry("de.adj.schoen.01")!!.primarySense.keyboardAlternatives)
        assertTrue(pack.entry("de.adv.schon.01")!!.primarySense.keyboardAlternatives.isEmpty())
    }

    @Test fun pilotSourcesAreNotReleasable() {
        val result = ContentPipeline(GermanLanguagePack).build(source, PackValidator.Mode.RELEASE)
        assertTrue(result.errors.any { "exactly 5000" in it })
        assertTrue(result.errors.any { "rights not cleared" in it })
        assertTrue(result.errors.any { "audio file missing" in it })
    }
}

package com.vocab5000.core.content

import com.vocab5000.core.learning.TextSpans
import com.vocab5000.core.model.AudioAsset
import com.vocab5000.core.model.ContentManifest
import com.vocab5000.core.model.Entry
import com.vocab5000.core.model.LanguagePack
import java.text.Normalizer

data class ValidationReport(val errors: List<String>, val warnings: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

/**
 * Structural and editorial validation of a content pack (spec §5, §9, §16.1).
 * Release mode is the launch gate: exactly 5,000 approved, rights-cleared entries with
 * audio. Pilot mode permits draft content and missing audio but still enforces structure.
 */
class PackValidator(private val languagePack: LanguagePack) {

    enum class Mode {
        PILOT, RELEASE;

        companion object {
            fun fromManifest(manifest: ContentManifest) = if (manifest.isReleasePack) RELEASE else PILOT
        }
    }

    fun validate(manifest: ContentManifest, entries: List<Entry>, audio: List<AudioAsset>, mode: Mode): ValidationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val release = mode == Mode.RELEASE

        if (manifest.languageId != languagePack.languageId) errors += "manifest language ${manifest.languageId} does not match pack ${languagePack.languageId}"
        if (manifest.schemaVersion != PackFormat.SCHEMA_VERSION) errors += "unsupported schema version ${manifest.schemaVersion}"
        if (manifest.entryCount != entries.size) errors += "manifest entryCount ${manifest.entryCount} != ${entries.size} entries"
        if (manifest.bandSize <= 0) errors += "bandSize must be positive"
        if (manifest.freeEntryLimit !in 0..entries.size) errors += "freeEntryLimit ${manifest.freeEntryLimit} out of range"
        if (release) {
            if (entries.size != RELEASE_ENTRY_COUNT) errors += "release pack must contain exactly $RELEASE_ENTRY_COUNT active entries, found ${entries.size}"
            if (manifest.freeEntryLimit != RELEASE_FREE_LIMIT) errors += "release freeEntryLimit must be $RELEASE_FREE_LIMIT"
            if (manifest.bandSize != RELEASE_BAND_SIZE) errors += "release bandSize must be $RELEASE_BAND_SIZE"
            if (manifest.rankSource == "placeholder") errors += "release ranks must come from the corpus pipeline, not placeholders"
        }

        // Identity and ranks.
        val ids = entries.groupingBy { it.entryId }.eachCount().filterValues { it > 1 }.keys
        ids.forEach { errors += "duplicate entryId $it" }
        val ranks = entries.map { it.curriculumRank }.sorted()
        if (ranks != (1..entries.size).toList()) errors += "curriculumRank must be unique and contiguous from 1 to ${entries.size}"
        val lemmaPos = entries.groupingBy { it.lemma to it.pos }.eachCount().filterValues { it > 1 }.keys
        lemmaPos.forEach { errors += "duplicate lemma/POS ${it.first}/${it.second}" }

        val audioIds = audio.associateBy { it.audioId }
        val allSenseIds = mutableSetOf<String>()
        for (entry in entries) {
            val where = entry.entryId
            if (entry.languageId != languagePack.languageId) errors += "$where: wrong languageId"
            if (entry.lemma.isBlank()) errors += "$where: blank lemma"
            if (!isNfc(entry.lemma)) errors += "$where: lemma is not NFC"
            if (entry.senses.isEmpty()) errors += "$where: no senses"
            if (entry.senses.size > 4) errors += "$where: more than one primary + three secondary senses"
            entry.senses.forEach { sense ->
                if (!allSenseIds.add(sense.senseId)) errors += "$where: duplicate senseId ${sense.senseId}"
                if (sense.glossEn.isBlank()) errors += "$where: blank gloss in ${sense.senseId}"
            }
            if (entry.senses.none { it.senseId == entry.primarySenseId }) errors += "$where: primarySenseId ${entry.primarySenseId} not found"
            languagePack.grammarValidator.validate(entry).forEach { errors += "$where: $it" }
            if (entry.corpusDraft && (entry.assessmentEligible || entry.editorialStatus.shippableInRelease)) {
                errors += "$where: corpus drafts cannot be assessment eligible or approved"
            }
            if (release && entry.corpusDraft) errors += "$where: corpus draft needs linguistic review"
            if (release && entry.pos == com.vocab5000.core.model.PartOfSpeech.UNSPECIFIED) errors += "$where: part of speech is unspecified"

            if (entry.examples.size != 1) errors += "$where: needs exactly one example sentence"
            if (entry.examples.none { it.senseId == entry.primarySenseId }) warnings += "$where: no example for the primary sense"
            val senseIds = entry.senses.map { it.senseId }.toSet()
            entry.examples.forEach { ex ->
                if (ex.senseId !in senseIds) errors += "$where: example ${ex.exampleId} references unknown sense ${ex.senseId}"
                if (!isNfc(ex.textDe)) errors += "$where: example ${ex.exampleId} is not NFC"
                if (ex.textEn.isBlank()) errors += "$where: example ${ex.exampleId} lacks a translation"
                if (ex.textDe.isBlank()) errors += "$where: example ${ex.exampleId} is blank"
                if (ex.targetSpans.isEmpty()) {
                    val message = "$where: example ${ex.exampleId} has no target span"
                    if (entry.corpusDraft && !release) warnings += message else errors += message
                }
                val sorted = ex.targetSpans.sortedBy { it.start }
                sorted.zipWithNext().forEach { (a, b) -> if (b.start < a.end) errors += "$where: overlapping spans in ${ex.exampleId}" }
                ex.targetSpans.forEach { span ->
                    if (TextSpans.extract(ex.textDe, span).isNullOrBlank()) errors += "$where: span [${span.start},${span.end}) does not resolve in ${ex.exampleId}"
                }
                checkAudio(where, ex.audioId, audioIds, release, errors, warnings, "example audio")
            }
            checkAudio(where, entry.wordAudioId, audioIds, release, errors, warnings, "word audio")

            if (release) {
                if (!entry.editorialStatus.shippableInRelease) errors += "$where: editorial status ${entry.editorialStatus.wireName} is not approved"
                if (entry.rightsIds.isEmpty() || entry.rightsIds.any { it.contains("pending") }) errors += "$where: rights not cleared"
            }
        }
        audio.forEach { asset ->
            if (asset.path.isBlank() || asset.path.startsWith("/") || asset.path.contains("..")) errors += "audio ${asset.audioId}: unsafe path '${asset.path}'"
            if (release && asset.checksumSha256.isNullOrBlank()) errors += "audio ${asset.audioId}: missing checksum"
        }
        return ValidationReport(errors, warnings)
    }

    private fun checkAudio(
        where: String,
        audioId: String?,
        audio: Map<String, AudioAsset>,
        release: Boolean,
        errors: MutableList<String>,
        warnings: MutableList<String>,
        label: String,
    ) {
        when {
            audioId == null && release -> errors += "$where: missing $label"
            audioId == null -> warnings += "$where: missing $label"
            audioId !in audio -> errors += "$where: $label $audioId has no audio asset record"
        }
    }

    private fun isNfc(text: String) = Normalizer.isNormalized(text, Normalizer.Form.NFC)

    companion object {
        const val RELEASE_ENTRY_COUNT = 5000
        const val RELEASE_FREE_LIMIT = 1000
        const val RELEASE_BAND_SIZE = 1000
    }
}

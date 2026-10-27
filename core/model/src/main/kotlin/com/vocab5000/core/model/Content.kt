package com.vocab5000.core.model

/**
 * Read-only learning content. Identity is [Entry.entryId]; [Entry.curriculumRank] is a
 * curriculum position that may change between content versions and is never persisted
 * as a reference in user data.
 */
enum class PartOfSpeech {
    NOUN, VERB, ADJECTIVE, ADVERB, PREPOSITION, PRONOUN, DETERMINER, ARTICLE,
    CONJUNCTION, NUMERAL, PARTICLE, INTERJECTION,
}

enum class EditorialStatus(val wireName: String) {
    DRAFT("draft"),
    LINGUISTIC_REVIEW("linguistic_review"),
    AUDIO_REVIEW("audio_review"),
    APPROVED("approved"),
    PUBLISHED("published");

    val shippableInRelease: Boolean get() = this == APPROVED || this == PUBLISHED

    companion object {
        fun fromWire(value: String): EditorialStatus =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("Unknown editorial status '$value'")
    }
}

data class Sense(
    val senseId: String,
    val glossEn: String,
    val register: String? = null,
    val region: String? = null,
    /** Additional German answers accepted for recall prompts of this sense. */
    val acceptedAnswers: List<String> = emptyList(),
    /** Explicitly approved keyboard spellings such as `schoen` for `schön`. */
    val keyboardAlternatives: List<String> = emptyList(),
    /** Additional English meanings accepted in recognition/meaning prompts. */
    val acceptedMeaningsEn: List<String> = emptyList(),
)

/** Zero-based, half-open Unicode code point span in NFC text. */
data class CodepointSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0 && end > start) { "Invalid span [$start, $end)" }
    }
}

data class Example(
    val exampleId: String,
    val senseId: String,
    val textDe: String,
    val textEn: String,
    val targetSpans: List<CodepointSpan>,
    val audioId: String?,
)

data class Entry(
    val entryId: String,
    val curriculumRank: Int,
    val lemma: String,
    val pos: PartOfSpeech,
    val languageId: String,
    val primarySenseId: String,
    /** Language-specific grammar facts; interpreted by the language pack's renderer. */
    val grammar: Map<String, String>,
    val senses: List<Sense>,
    val examples: List<Example>,
    val wordAudioId: String?,
    val revision: Int,
    val editorialStatus: EditorialStatus,
    val register: String? = null,
    val region: String? = null,
    val cefrTag: String? = null,
    val notes: String? = null,
    val searchAliases: List<String> = emptyList(),
    val rightsIds: List<String> = emptyList(),
    val assessmentEligible: Boolean = true,
) {
    val primarySense: Sense get() = senses.first { it.senseId == primarySenseId }
    val primaryExample: Example? get() = examples.firstOrNull { it.senseId == primarySenseId } ?: examples.firstOrNull()
}

data class AudioAsset(
    val audioId: String,
    val path: String,
    val spokenText: String,
    val format: String,
    val durationMs: Long?,
    val checksumSha256: String?,
    val rightsId: String?,
)

data class ContentManifest(
    val packId: String,
    val schemaVersion: Int,
    val contentVersion: String,
    val languageId: String,
    val entryCount: Int,
    val schedulerCompatibility: List<String>,
    val rightsManifestId: String,
    /** Entries ranked 1..freeEntryLimit are available without premium. */
    val freeEntryLimit: Int,
    /** Width of a placement/browsing rank band. */
    val bandSize: Int,
    /** `release` for a launch-ready pack, `pilot` for development content. */
    val buildMode: String,
    val rankSource: String,
    val packChecksumSha256: String? = null,
) {
    val isReleasePack: Boolean get() = buildMode == "release"
    val bandCount: Int get() = if (bandSize <= 0) 0 else (entryCount + bandSize - 1) / bandSize
}
